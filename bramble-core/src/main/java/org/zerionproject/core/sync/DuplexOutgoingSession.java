package org.zerionproject.core.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.event.LifecycleEvent;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.event.TransportInactiveEvent;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Priority;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.SyncRecordWriter;
import org.zerionproject.core.api.sync.SyncSession;
import org.zerionproject.core.api.sync.Versions;
import org.zerionproject.core.api.sync.event.CloseSyncConnectionsEvent;
import org.zerionproject.core.api.sync.event.GroupVisibilityUpdatedEvent;
import org.zerionproject.core.api.sync.event.MessageRequestedEvent;
import org.zerionproject.core.api.sync.event.MessageSharedEvent;
import org.zerionproject.core.api.sync.event.MessageToAckEvent;
import org.zerionproject.core.api.sync.event.MessageToRequestEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static java.lang.Boolean.TRUE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState.STOPPING;
import static org.zerionproject.core.api.record.Record.RECORD_HEADER_BYTES;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_IDS;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.SUPPORTED_VERSIONS;

@ThreadSafe
@NotNullByDefault
class DuplexOutgoingSession implements SyncSession, EventListener {
	private static final ThrowingRunnable<IOException> CLOSE = () -> {
	};
	private static final ThrowingRunnable<IOException>
			NEXT_SEND_TIME_DECREASED = () -> {
	};

	private static final int BATCH_CAPACITY =
			(RECORD_HEADER_BYTES + MAX_MESSAGE_LENGTH) * 2;

	private static final long COVER_INTERVAL_MS_BASE = 700L;
	private static final long COVER_INTERVAL_MS_JITTER_MEAN = 70L;
	private static final long COVER_INTERVAL_MS_CAP =
			COVER_INTERVAL_MS_BASE + 280L;
	private static final int SLOT_BYTES_BASE = 2048;
	private static final int SLOT_BYTES_JITTER = 256;
	private static final long MIN_PEER_IDLE_TIME_MS =
			COVER_INTERVAL_MS_CAP * 2L;
	private static final double SYNTHETIC_OFFER_PROB = 0.08;
	private static final int SYNTHETIC_OFFER_BYTES =
			32 + RECORD_HEADER_BYTES;

	private final DatabaseComponent db;
	private final Executor dbExecutor;
	private final EventBus eventBus;
	private final Clock clock;
	private final ContactId contactId;
	private final TransportId transportId;
	private final long maxLatency;
	private final long coverIntervalMs;
	private final int slotBytes;
	private final StreamWriter streamWriter;
	private final SyncRecordWriter recordWriter;
	@Nullable
	private final Priority priority;
	private final BlockingQueue<ThrowingRunnable<IOException>> writerTasks;

	private final AtomicBoolean generateAckQueued = new AtomicBoolean(false);
	private final AtomicBoolean generateBatchQueued = new AtomicBoolean(false);
	private final AtomicBoolean generateOfferQueued = new AtomicBoolean(false);
	private final AtomicBoolean generateRequestQueued =
			new AtomicBoolean(false);
	private final AtomicLong nextSendTime = new AtomicLong(Long.MAX_VALUE);

	private volatile boolean interrupted = false;

	DuplexOutgoingSession(DatabaseComponent db, Executor dbExecutor,
			EventBus eventBus, Clock clock, ContactId contactId,
			TransportId transportId, long maxLatency, int maxIdleTime,
			StreamWriter streamWriter, SyncRecordWriter recordWriter,
			@Nullable Priority priority) {
		if (maxIdleTime > 0 && maxIdleTime < MIN_PEER_IDLE_TIME_MS) {
			throw new IllegalArgumentException(
					"maxIdleTime " + maxIdleTime
							+ "ms below MIN_PEER_IDLE_TIME_MS "
							+ MIN_PEER_IDLE_TIME_MS + "ms");
		}
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.eventBus = eventBus;
		this.clock = clock;
		this.contactId = contactId;
		this.transportId = transportId;
		this.maxLatency = maxLatency;
		this.streamWriter = streamWriter;
		this.recordWriter = recordWriter;
		this.priority = priority;
		java.util.concurrent.ThreadLocalRandom rng =
				java.util.concurrent.ThreadLocalRandom.current();
		this.coverIntervalMs = drawCoverInterval(rng,
				COVER_INTERVAL_MS_BASE,
				COVER_INTERVAL_MS_JITTER_MEAN,
				COVER_INTERVAL_MS_CAP);
		this.slotBytes = SLOT_BYTES_BASE
				+ rng.nextInt(-SLOT_BYTES_JITTER,
						SLOT_BYTES_JITTER + 1);
		writerTasks = new LinkedBlockingQueue<>();
	}

	private static long drawCoverInterval(
			java.util.concurrent.ThreadLocalRandom rng, long base,
			long jitterMean, long cap) {
		double u = rng.nextDouble();
		if (u <= 0d) u = 1e-12;
		long expDraw = Math.round(-jitterMean * Math.log(1d - u));
		long bounded = Math.min(cap - base, Math.max(0L, expDraw));
		return base + bounded;
	}

	private static org.zerionproject.core.api.sync.Offer
	buildSyntheticOffer() {
		byte[] randomId = new byte[
				org.zerionproject.core.api.UniqueId.LENGTH];
		java.util.concurrent.ThreadLocalRandom.current().nextBytes(randomId);
		return new org.zerionproject.core.api.sync.Offer(
				java.util.Collections.singletonList(
						new org.zerionproject.core.api.sync.MessageId(
								randomId)));
	}

	@IoExecutor
	@Override
	public void run() throws IOException {
		eventBus.addListener(this);
		try {
			recordWriter.writeVersions(new Versions(SUPPORTED_VERSIONS));
			if (priority != null) recordWriter.writePriority(priority);
			generateAck();
			generateBatch();
			generateOffer();
			generateRequest();
			long now = clock.currentTimeMillis();
			long nextFlush = now;
			long slotStartBytes = recordWriter.getBytesWritten();
			boolean slotOverCap = false;
			try {
				while (!interrupted) {
					now = clock.currentTimeMillis();
					long flushWait = Math.max(0, nextFlush - now);
					long sendWait = Math.max(0, nextSendTime.get() - now);
					long wait = Math.min(flushWait, sendWait);
					ThrowingRunnable<IOException> task;
					if (slotOverCap) {
						task = writerTasks.peek() == CLOSE
								? writerTasks.poll(0, MILLISECONDS)
								: null;
						if (task == null && wait > 0) {
							Thread.sleep(wait);
						}
					} else {
						task = writerTasks.poll(wait, MILLISECONDS);
					}
					if (task == null) {
						now = clock.currentTimeMillis();
						if (now >= nextSendTime.get()) {
							setNextSendTime(Long.MAX_VALUE);
							generateBatch();
							generateOffer();
						}
						if (now >= nextFlush) {
							long written = recordWriter.getBytesWritten()
									- slotStartBytes;
							long room = slotBytes - written
									- RECORD_HEADER_BYTES;
							if (room >= SYNTHETIC_OFFER_BYTES
									&& java.util.concurrent.ThreadLocalRandom
									.current().nextDouble()
									< SYNTHETIC_OFFER_PROB) {
								recordWriter.writeOffer(
										buildSyntheticOffer());
								written = recordWriter.getBytesWritten()
										- slotStartBytes;
								room = slotBytes - written
										- RECORD_HEADER_BYTES;
							}
							if (room > 0) {
								recordWriter.writeCover((int) Math.min(
										room, Integer.MAX_VALUE));
							}
							recordWriter.flush();
							slotStartBytes = recordWriter.getBytesWritten();
							nextFlush = now + coverIntervalMs;
							slotOverCap = false;
						}
					} else if (task == CLOSE) {
						break;
					} else if (task == NEXT_SEND_TIME_DECREASED) {
					} else {
						task.run();
						long written = recordWriter.getBytesWritten()
								- slotStartBytes;
						if (written >= slotBytes) {
							slotOverCap = true;
						}
					}
				}
				streamWriter.sendEndOfStream();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		} finally {
			eventBus.removeListener(this);
		}
	}

	private void generateAck() {
		if (generateAckQueued.compareAndSet(false, true))
			dbExecutor.execute(new GenerateAck());
	}

	private void generateBatch() {
		if (generateBatchQueued.compareAndSet(false, true))
			dbExecutor.execute(new GenerateBatch());
	}

	private void generateOffer() {
		if (generateOfferQueued.compareAndSet(false, true))
			dbExecutor.execute(new GenerateOffer());
	}

	private void generateRequest() {
		if (generateRequestQueued.compareAndSet(false, true))
			dbExecutor.execute(new GenerateRequest());
	}

	private void setNextSendTime(long time) {
		long old = nextSendTime.getAndSet(time);
		if (time < old) writerTasks.add(NEXT_SEND_TIME_DECREASED);
	}

	@Override
	public void interrupt() {
		interrupted = true;
		writerTasks.add(CLOSE);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactRemovedEvent) {
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			if (c.getContactId().equals(contactId)) interrupt();
		} else if (e instanceof MessageSharedEvent) {
			MessageSharedEvent m = (MessageSharedEvent) e;
			if (m.getGroupVisibility().get(contactId) == TRUE) {
				generateOffer();
			}
		} else if (e instanceof GroupVisibilityUpdatedEvent) {
			GroupVisibilityUpdatedEvent g = (GroupVisibilityUpdatedEvent) e;
			if (g.getVisibility() == SHARED &&
					g.getAffectedContacts().contains(contactId)) {
				generateOffer();
			}
		} else if (e instanceof MessageRequestedEvent) {
			if (((MessageRequestedEvent) e).getContactId().equals(contactId))
				generateBatch();
		} else if (e instanceof MessageToAckEvent) {
			if (((MessageToAckEvent) e).getContactId().equals(contactId))
				generateAck();
		} else if (e instanceof MessageToRequestEvent) {
			if (((MessageToRequestEvent) e).getContactId().equals(contactId))
				generateRequest();
		} else if (e instanceof LifecycleEvent) {
			LifecycleEvent l = (LifecycleEvent) e;
			if (l.getLifecycleState() == STOPPING) interrupt();
		} else if (e instanceof CloseSyncConnectionsEvent) {
			CloseSyncConnectionsEvent c = (CloseSyncConnectionsEvent) e;
			if (c.getTransportId().equals(transportId)) interrupt();
		} else if (e instanceof TransportInactiveEvent) {
			TransportInactiveEvent t = (TransportInactiveEvent) e;
			if (t.getTransportId().equals(transportId)) interrupt();
		}
	}

	private class GenerateAck implements Runnable {

		@DatabaseExecutor
		@Override
		public void run() {
			if (interrupted) return;
			if (!generateAckQueued.getAndSet(false)) throw new AssertionError();
			try {
				Ack a = db.transactionWithNullableResult(false, txn ->
						db.generateAck(txn, contactId, MAX_MESSAGE_IDS));
				if (a != null) writerTasks.add(new WriteAck(a));
			} catch (DbException e) {
				interrupt();
			}
		}
	}

	private class WriteAck implements ThrowingRunnable<IOException> {

		private final Ack ack;

		private WriteAck(Ack ack) {
			this.ack = ack;
		}

		@IoExecutor
		@Override
		public void run() throws IOException {
			if (interrupted) return;
			recordWriter.writeAck(ack);
			generateAck();
		}
	}

	private class GenerateBatch implements Runnable {

		@DatabaseExecutor
		@Override
		public void run() {
			if (interrupted) return;
			if (!generateBatchQueued.getAndSet(false))
				throw new AssertionError();
			try {
				Collection<Message> b =
						db.transactionWithNullableResult(false, txn -> {
							Collection<Message> batch =
									db.generateRequestedBatch(txn, contactId,
											BATCH_CAPACITY, maxLatency);
							setNextSendTime(db.getNextSendTime(txn, contactId,
									maxLatency));
							return batch;
						});
				if (b != null) writerTasks.add(new WriteBatch(b));
			} catch (DbException e) {
				interrupt();
			}
		}
	}

	private class WriteBatch implements ThrowingRunnable<IOException> {

		private final Collection<Message> batch;

		private WriteBatch(Collection<Message> batch) {
			this.batch = batch;
		}

		@IoExecutor
		@Override
		public void run() throws IOException {
			if (interrupted) return;
			for (Message m : batch) recordWriter.writeMessage(m);
			generateBatch();
		}
	}

	private class GenerateOffer implements Runnable {

		@DatabaseExecutor
		@Override
		public void run() {
			if (interrupted) return;
			if (!generateOfferQueued.getAndSet(false))
				throw new AssertionError();
			try {
				Offer o = db.transactionWithNullableResult(false, txn -> {
					Offer offer = db.generateOffer(txn, contactId,
							MAX_MESSAGE_IDS, maxLatency);
					setNextSendTime(db.getNextSendTime(txn, contactId,
							maxLatency));
					return offer;
				});
				if (o != null) writerTasks.add(new WriteOffer(o));
			} catch (DbException e) {
				interrupt();
			}
		}
	}

	private class WriteOffer implements ThrowingRunnable<IOException> {

		private final Offer offer;

		private WriteOffer(Offer offer) {
			this.offer = offer;
		}

		@IoExecutor
		@Override
		public void run() throws IOException {
			if (interrupted) return;
			recordWriter.writeOffer(offer);
			generateOffer();
		}
	}

	private class GenerateRequest implements Runnable {

		@DatabaseExecutor
		@Override
		public void run() {
			if (interrupted) return;
			if (!generateRequestQueued.getAndSet(false))
				throw new AssertionError();
			try {
				Request r = db.transactionWithNullableResult(false, txn ->
						db.generateRequest(txn, contactId, MAX_MESSAGE_IDS));
				if (r != null) writerTasks.add(new WriteRequest(r));
			} catch (DbException e) {
				interrupt();
			}
		}
	}

	private class WriteRequest implements ThrowingRunnable<IOException> {

		private final Request request;

		private WriteRequest(Request request) {
			this.request = request;
		}

		@IoExecutor
		@Override
		public void run() throws IOException {
			if (interrupted) return;
			recordWriter.writeRequest(request);
			generateRequest();
		}
	}
}
