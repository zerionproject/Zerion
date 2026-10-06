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

@ThreadSafe
@NotNullByDefault
public class ZppOutgoingSource implements EventListener {

	private static final int BATCH_CAPACITY =
			(RECORD_HEADER_BYTES + MAX_MESSAGE_LENGTH) * 2;
	private static final int MAX_LANE_DEPTH = 512;
	private static final long BACKPRESSURE_RETRY_MS = 2_000L;
	private static final long BULK_ROOM_RETRY_MS = 10_000L;
	static final long FRESH_MESSAGE_MS = 10 * 60_000L;

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
		if (!proceedOrDefer(this::generateAck)) return;
		try {
			Ack a = db.transactionWithNullableResult(false, txn ->
					db.generateAck(txn, contactId, MAX_MESSAGE_IDS));
			if (a != null) {
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
				for (Message m : b) {
					if (!inFlight.add(m.getId())) continue;
					enqueueMessage(m, isFresh(m, now));
				}
				generateBatch();
			} else if (scheduler.getBulkDepth() > 0) {
				retryWhenBulkLaneHasRoom();
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
		if (!proceedOrDefer(this::generateOffer)) return;
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
					enqueue(codec.encodeOffer(new Offer(ids)), false);
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
		if (!proceedOrDefer(this::generateRequest)) return;
		try {
			Request r = db.transactionWithNullableResult(false, txn ->
					db.generateRequest(txn, contactId, MAX_MESSAGE_IDS));
			if (r != null) {
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

	private void enqueue(byte[] syncRecord, boolean userOriginated)
			throws IOException {
		enqueue(syncRecord, userOriginated, null);
	}

	private void enqueue(byte[] syncRecord, boolean userOriginated,
			@Nullable Runnable onSent) throws IOException {
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
			scheduler.enqueueRecord(frames.get(i), userOriginated, bulkLane,
					i == last ? onSent : null);
		}
	}

	private void enqueueMessage(Message m, boolean userOriginated)
			throws IOException {
		MessageId id = m.getId();
		try {
			enqueue(codec.encodeMessage(m), userOriginated,
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
