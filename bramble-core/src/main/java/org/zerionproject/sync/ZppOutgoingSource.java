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
import java.util.Collection;
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

/**
 * The send side of ZPP for one online contact: pulls the delivery-DAG records
 * (Ack/Message/Offer/Request) from the database and enqueues them into the
 * contact's constant-rate {@link ZppSendScheduler}. This mirrors the proven
 * outgoing sync logic - the same generate calls, the same triggering events, the
 * same {@code getNextSendTime} retransmission timer - but writes records into the
 * scheduler instead of a stream and never emits cover or manages slots itself,
 * because ZPP's scheduler fills every idle slot with cover at a constant rate.
 *
 * <p>Records too large for one frame are fragmented before being enqueued. On a
 * heavy backlog the scheduler queue front-loads and a record can reach its
 * retransmission timer before it has been sent, producing a duplicate send; that
 * is harmless because the receiver deduplicates.
 */
@ThreadSafe
@NotNullByDefault
public class ZppOutgoingSource implements EventListener {

	private static final int BATCH_CAPACITY =
			(RECORD_HEADER_BYTES + MAX_MESSAGE_LENGTH) * 2;
	private static final int MAX_QUEUE_DEPTH = 512;
	private static final long BACKPRESSURE_RETRY_MS = 2_000L;

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

	private final AtomicBoolean generateAckQueued = new AtomicBoolean(false);
	private final AtomicBoolean generateBatchQueued = new AtomicBoolean(false);
	private final AtomicBoolean generateOfferQueued = new AtomicBoolean(false);
	private final AtomicBoolean generateRequestQueued = new AtomicBoolean(false);
	private final AtomicLong nextSendTime = new AtomicLong(Long.MAX_VALUE);
	private final AtomicLong messageIdCounter = new AtomicLong(0);
	private volatile boolean stopped = false;

	private final Object retransmitLock = new Object();
	@GuardedBy("retransmitLock")
	@Nullable
	private Cancellable retransmitTask;

	public ZppOutgoingSource(DatabaseComponent db, Executor dbExecutor,
			EventBus eventBus, TaskScheduler taskScheduler, Clock clock,
			ZmmSyncCodec codec, ContactId contactId, long maxLatency,
			int maxRecordBytes, ZppSendScheduler scheduler) {
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

	/** Registers for events and does the initial generation pass. */
	public void start() {
		eventBus.addListener(this);
		generateAck();
		generateBatch();
		generateOffer();
		generateRequest();
	}

	/** Deregisters and cancels the retransmission timer. */
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
				enqueue(codec.encodeAck(a));
				generateAck();
			}
		} catch (DbException | IOException e) {
			// Drop this generation pass; a later event or timer retries.
		}
	}

	@DatabaseExecutor
	private void runGenerateBatch() {
		if (stopped) return;
		generateBatchQueued.set(false);
		if (!proceedOrDefer(this::generateBatch)) return;
		try {
			Collection<Message> b = db.transactionWithNullableResult(false,
					txn -> {
						Collection<Message> batch = db.generateRequestedBatch(txn,
								contactId, BATCH_CAPACITY, maxLatency);
						setNextSendTime(db.getNextSendTime(txn, contactId,
								maxLatency));
						return batch;
					});
			if (b != null) {
				for (Message m : b) enqueue(codec.encodeMessage(m));
				generateBatch();
			}
		} catch (DbException | IOException e) {
			// Drop this generation pass.
		}
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
				enqueue(codec.encodeOffer(o));
				generateOffer();
			}
		} catch (DbException | IOException e) {
			// Drop this generation pass.
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
				enqueue(codec.encodeRequest(r));
				generateRequest();
			}
		} catch (DbException | IOException e) {
			// Drop this generation pass.
		}
	}

	private void enqueue(byte[] syncRecord) throws IOException {
		long id = messageIdCounter.getAndIncrement();
		for (byte[] frame : ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
				syncRecord, id, maxRecordBytes)) {
			scheduler.enqueueRecord(frame);
		}
	}

	/**
	 * @return true if generation may proceed; false if the send queue is already
	 * at capacity, in which case the given trigger is re-run after a short delay
	 * so a backlog cannot front-load into memory faster than it is drained.
	 */
	private boolean proceedOrDefer(Runnable retrigger) {
		if (scheduler.getQueueDepth() < MAX_QUEUE_DEPTH) return true;
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
