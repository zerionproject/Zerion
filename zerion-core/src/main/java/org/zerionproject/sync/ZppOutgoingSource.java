package org.zerionproject.sync;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.event.GroupVisibilityUpdatedEvent;
import org.zerionproject.core.api.sync.event.MessageRequestedEvent;
import org.zerionproject.core.api.sync.event.MessageSharedEvent;
import org.zerionproject.core.api.sync.event.MessageToAckEvent;
import org.zerionproject.core.api.sync.event.MessageToRequestEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmFragmenter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import static java.lang.Boolean.TRUE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.api.record.Record.RECORD_HEADER_BYTES;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_IDS;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_LENGTH;
import static org.zerionproject.sync.ZppSendScheduler.RANK_AUTOMATIC;
import static org.zerionproject.sync.ZppSendScheduler.RANK_REPLY;
import static org.zerionproject.sync.ZppSendScheduler.RANK_USER;

@ThreadSafe
@NotNullByDefault
public class ZppOutgoingSource implements EventListener {

	private static final int BATCH_CAPACITY =
			(RECORD_HEADER_BYTES + MAX_MESSAGE_LENGTH) * 2;
	private static final int MAX_LANE_DEPTH = 512;
	private static final long BACKPRESSURE_RETRY_MS = 2_000L;
	private static final long BULK_ROOM_RETRY_MS = 10_000L;
	static final long FRESH_MESSAGE_MS = 10 * 60_000L;
	private static final int REPLY_OVERHEAD_BYTES = 96;

	private final DatabaseComponent db;
	private final Executor dbExecutor;
	private final EventBus eventBus;
	private final TaskScheduler taskScheduler;
	private final Clock clock;
	private final ZmmSyncCodec codec;
	private final ContactId contactId;
	private final long maxLatency;
	private final int maxRecordBytes;
	private final ZppSendScheduler scheduler;
	private final LocalMessageLog localMessages;

	private final AtomicBoolean generateAckQueued = new AtomicBoolean(false);
	private final AtomicBoolean generateBatchQueued = new AtomicBoolean(false);
	private final AtomicBoolean generateOfferQueued = new AtomicBoolean(false);
	private final AtomicBoolean generateRequestQueued = new AtomicBoolean(false);
	private final AtomicBoolean bulkRoomRetryQueued = new AtomicBoolean(false);
	private final AtomicBoolean ackWaiting = new AtomicBoolean(false);
	private final AtomicBoolean requestWaiting = new AtomicBoolean(false);
	private final AtomicBoolean offerWaiting = new AtomicBoolean(false);
	private volatile boolean startingUp = true;
	private final AtomicLong nextSendTime = new AtomicLong(Long.MAX_VALUE);
	private final AtomicLong messageIdCounter = new AtomicLong(0);
	private volatile boolean stopped = false;

	private final Set<MessageId> inFlight = ConcurrentHashMap.newKeySet();

	private final Object retransmitLock = new Object();
	@GuardedBy("retransmitLock")
	@Nullable
	private Cancellable retransmitTask;

	public ZppOutgoingSource(DatabaseComponent db, Executor dbExecutor,
			EventBus eventBus, TaskScheduler taskScheduler, Clock clock,
			ZmmSyncCodec codec, ContactId contactId, long maxLatency,
			int maxRecordBytes, ZppSendScheduler scheduler,
			LocalMessageLog localMessages) {
		this.localMessages = localMessages;
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.eventBus = eventBus;
		this.taskScheduler = taskScheduler;
		this.clock = clock;
		this.codec = codec;
		this.contactId = contactId;
		this.maxLatency = maxLatency;
		this.maxRecordBytes = maxRecordBytes;
		this.scheduler = scheduler;
	}

	public void start() {
		scheduler.setUserWaiting(() -> localMessages.hasWanted(contactId));
		eventBus.addListener(this);
		generateAck();
		generateBatch();
		generateOffer();
		generateRequest();
	}

	public void stop() {
		stopped = true;
		eventBus.removeListener(this);
		cancelRetransmit();
	}

	private void generateAck() {
		if (generateAckQueued.compareAndSet(false, true)) {
			dbExecutor.execute(this::runGenerateAck);
		}
	}

	private void generateBatch() {
		if (generateBatchQueued.compareAndSet(false, true)) {
			dbExecutor.execute(this::runGenerateBatch);
		}
	}

	private void generateOffer() {
		if (generateOfferQueued.compareAndSet(false, true)) {
			dbExecutor.execute(this::runGenerateOffer);
		}
	}

	private void generateRequest() {
		if (generateRequestQueued.compareAndSet(false, true)) {
			dbExecutor.execute(this::runGenerateRequest);
		}
	}

	@DatabaseExecutor
	private void runGenerateAck() {
		if (stopped) return;
		generateAckQueued.set(false);
		boolean paced = scheduler.isRanked();
		if (paced && ackWaiting.get()) return;
		if (!paced && !proceedOrDefer(this::generateAck)) return;
		int max = paced ? idsPerFrame() : MAX_MESSAGE_IDS;
		try {
			Ack a = db.transactionWithNullableResult(false, txn ->
					db.generateAck(txn, contactId, max));
			if (a != null && paced) {
				ackWaiting.set(true);
				enqueue(codec.encodeAck(a), false, false, RANK_REPLY, () -> {
					ackWaiting.set(false);
					generateAck();
				});
			} else if (a != null) {
				enqueue(codec.encodeAck(a), false);
				generateAck();
			}
		} catch (DbException | IOException e) {
		}
	}

	@DatabaseExecutor
	private void runGenerateBatch() {
		if (stopped) return;
		generateBatchQueued.set(false);
		boolean paced = scheduler.isRanked();
		if (!proceedOrDefer(this::generateBatch)) return;
		long capacity = batchCapacity();
		try {
			Collection<Message> b = db.transactionWithNullableResult(false,
					txn -> {
						Collection<Message> batch = db.generateRequestedBatch(txn,
								contactId, capacity, maxLatency);
						setNextSendTime(db.getNextSendTime(txn, contactId,
								maxLatency));
						return batch;
					});
			if (b != null) {
				long now = clock.currentTimeMillis();
				boolean wake = false;
				for (Message m : b) {
					if (!inFlight.add(m.getId())) {
						localMessages.forgetWanted(contactId, m.getId());
						continue;
					}
					boolean unannounced = isUnannounced(m, now);
					boolean fresh = isFresh(m, now);
					boolean user = localMessages.takeUserTraffic(contactId,
							m.getId());
					enqueueMessage(m, fresh, unannounced,
							user || unannounced ? RANK_USER : RANK_AUTOMATIC);
					if (user && startingUp) wake = true;
				}
				if (wake && paced) scheduler.wake();
				generateBatch();
			} else {
				startingUp = false;
				if (scheduler.getBulkDepth() > 0) retryWhenBulkLaneHasRoom();
				else localMessages.forgetWanted(contactId);
			}
		} catch (DbException | IOException e) {
		}
	}

	private long batchCapacity() {
		long room = (long) (MAX_LANE_DEPTH - scheduler.getBulkDepth())
				* maxRecordBytes;
		return Math.max(maxRecordBytes, Math.min(BATCH_CAPACITY, room));
	}

	private void retryWhenBulkLaneHasRoom() {
		if (stopped) return;
		if (!bulkRoomRetryQueued.compareAndSet(false, true)) return;
		taskScheduler.schedule(() -> {
			bulkRoomRetryQueued.set(false);
			if (!stopped) generateBatch();
		}, dbExecutor, BULK_ROOM_RETRY_MS, MILLISECONDS);
	}

	@DatabaseExecutor
	private void runGenerateOffer() {
		if (stopped) return;
		generateOfferQueued.set(false);
		boolean paced = scheduler.isRanked();
		boolean urgent = paced && localMessages.hasUnannounced();
		if (paced && !urgent && offerWaiting.get()) return;
		if (!urgent && !proceedOrDefer(this::generateOffer)) return;
		try {
			Offer o = db.transactionWithNullableResult(false, txn -> {
				Offer offer = db.generateOffer(txn, contactId, MAX_MESSAGE_IDS,
						maxLatency);
				setNextSendTime(db.getNextSendTime(txn, contactId, maxLatency));
				return offer;
			});
			if (o != null) {
				List<MessageId> ids = new ArrayList<>();
				for (MessageId id : o.getMessageIds()) {
					if (!inFlight.contains(id)) ids.add(id);
				}
				if (!ids.isEmpty()) {
					byte[] offer = codec.encodeOffer(new Offer(ids));
					boolean opens = announcesUserMessage(ids);
					if (paced && !opens) {
						offerWaiting.set(true);
						enqueue(offer, false, false, RANK_AUTOMATIC, () -> {
							offerWaiting.set(false);
							generateOffer();
						});
					} else {
						enqueue(offer, false, opens,
								opens ? RANK_USER : RANK_AUTOMATIC, null);
					}
				}
				generateOffer();
			}
		} catch (DbException | IOException e) {
		}
	}

	@DatabaseExecutor
	private void runGenerateRequest() {
		if (stopped) return;
		generateRequestQueued.set(false);
		boolean paced = scheduler.isRanked();
		if (paced && requestWaiting.get()) return;
		if (!paced && !proceedOrDefer(this::generateRequest)) return;
		int max = paced ? idsPerFrame() : MAX_MESSAGE_IDS;
		try {
			Request r = db.transactionWithNullableResult(false, txn ->
					db.generateRequest(txn, contactId, max));
			if (r != null && paced) {
				requestWaiting.set(true);
				enqueue(codec.encodeRequest(r), false, false, RANK_REPLY,
						() -> {
							requestWaiting.set(false);
							generateRequest();
						});
			} else if (r != null) {
				enqueue(codec.encodeRequest(r), false);
				generateRequest();
			}
		} catch (DbException | IOException e) {
		}
	}

	boolean isFresh(Message m, long now) {
		if (m.getTimestamp() - now > FRESH_MESSAGE_MS) return false;
		return localMessages.takeFirstSend(m.getId());
	}

	boolean isUnannounced(Message m, long now) {
		if (m.getTimestamp() - now > FRESH_MESSAGE_MS) return false;
		return localMessages.takeFirstOffer(m.getId());
	}

	boolean announcesUserMessage(Collection<MessageId> offered) {
		boolean announces = false;
		for (MessageId id : offered) {
			if (localMessages.takeFirstOffer(id)) announces = true;
		}
		return announces;
	}

	int idsPerFrame() {
		return Math.max(1, Math.min(MAX_MESSAGE_IDS,
				(maxRecordBytes - REPLY_OVERHEAD_BYTES) / MessageId.LENGTH));
	}

	private void enqueue(byte[] syncRecord, boolean userOriginated)
			throws IOException {
		enqueue(syncRecord, userOriginated, userOriginated,
				userOriginated ? RANK_USER : RANK_AUTOMATIC, null);
	}

	private void enqueue(byte[] syncRecord, boolean userOriginated,
			boolean opensWindow, int rank, @Nullable Runnable onSent)
			throws IOException {
		if (syncRecord.length > ZmmConstants.MAX_RECORD_BYTES) {
			if (onSent != null) onSent.run();
			return;
		}
		long id = messageIdCounter.getAndIncrement();
		List<byte[]> frames = ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				syncRecord, id, maxRecordBytes);
		boolean bulkLane = frames.size() > 1;
		int last = frames.size() - 1;
		for (int i = 0; i <= last; i++) {
			scheduler.enqueueRecord(frames.get(i), userOriginated,
					opensWindow, rank, bulkLane, i == last ? onSent : null);
		}
	}

	private void enqueueMessage(Message m, boolean userOriginated,
			boolean opensWindow, int rank) throws IOException {
		MessageId id = m.getId();
		try {
			enqueue(codec.encodeMessage(m), userOriginated, opensWindow, rank,
					() -> inFlight.remove(id));
		} catch (IOException | RuntimeException e) {
			inFlight.remove(id);
			throw e;
		}
	}

	private boolean proceedOrDefer(Runnable retrigger) {
		if (scheduler.getSmallDepth() < MAX_LANE_DEPTH) return true;
		if (!stopped) taskScheduler.schedule(() -> {
			if (!stopped) retrigger.run();
		}, dbExecutor, BACKPRESSURE_RETRY_MS, MILLISECONDS);
		return false;
	}

	private void setNextSendTime(long time) {
		long old = nextSendTime.getAndSet(time);
		if (time < old && time != Long.MAX_VALUE) scheduleRetransmit(time);
	}

	private void scheduleRetransmit(long absoluteTime) {
		long delay = Math.max(0, absoluteTime - clock.currentTimeMillis());
		synchronized (retransmitLock) {
			if (stopped) return;
			if (retransmitTask != null) retransmitTask.cancel();
			retransmitTask = taskScheduler.schedule(this::onRetransmitDue,
					dbExecutor, delay, MILLISECONDS);
		}
	}

	private void cancelRetransmit() {
		synchronized (retransmitLock) {
			if (retransmitTask != null) {
				retransmitTask.cancel();
				retransmitTask = null;
			}
		}
	}

	private void onRetransmitDue() {
		if (stopped) return;
		nextSendTime.set(Long.MAX_VALUE);
		generateBatch();
		generateOffer();
	}

	@Override
	public void eventOccurred(Event e) {
		if (stopped) return;
		if (e instanceof ContactRemovedEvent) {
			if (((ContactRemovedEvent) e).getContactId().equals(contactId)) {
				stopped = true;
				cancelRetransmit();
			}
		} else if (e instanceof MessageSharedEvent) {
			MessageSharedEvent m = (MessageSharedEvent) e;
			if (m.getGroupVisibility().get(contactId) == TRUE) generateOffer();
		} else if (e instanceof GroupVisibilityUpdatedEvent) {
			GroupVisibilityUpdatedEvent g = (GroupVisibilityUpdatedEvent) e;
			if (g.getVisibility() == SHARED
					&& g.getAffectedContacts().contains(contactId)) {
				generateOffer();
			}
		} else if (e instanceof MessageRequestedEvent) {
			if (((MessageRequestedEvent) e).getContactId().equals(contactId)) {
				generateBatch();
			}
		} else if (e instanceof MessageToAckEvent) {
			if (((MessageToAckEvent) e).getContactId().equals(contactId)) {
				generateAck();
			}
		} else if (e instanceof MessageToRequestEvent) {
			if (((MessageToRequestEvent) e).getContactId().equals(contactId)) {
				generateRequest();
			}
		}
	}
}
