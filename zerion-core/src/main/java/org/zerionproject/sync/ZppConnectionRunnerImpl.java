package org.zerionproject.sync;

import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmRecord;
import org.zerionproject.tor.TorActivitySink;
import org.zerionproject.tor.TorActivitySink.Activity;
import org.zerionproject.transport.ZppConnectionRunner;
import org.zerionproject.transport.ZtpReadTimeoutControl;
import org.zerionproject.transport.ZwfControlHandler;
import org.zerionproject.transport.ZwfDuplexConnection;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import javax.annotation.Nullable;

@NotNullByDefault
public class ZppConnectionRunnerImpl implements ZppConnectionRunner {

	private static final int JITTER_DIVISOR = 3;
	static final long REPLY_WINDOW_MS = 10 * 60_000L;
	static final long RECEIVE_ACTIVATION_INTERVAL_MS = 10 * 60_000L;
	static final long OPENING_WINDOW_INTERVAL_MS = 10 * 60_000L;
	static final long NEVER = -1L;
	static final long TICKER_JOIN_MS = 5_000L;
	static final long TICKER_FORCE_JOIN_MS = 30_000L;

	public static final long SPARSE_KEEPALIVE_LEGACY_MS = 9_000L;
	public static final long SPARSE_KEEPALIVE_MS = 30_000L;
	public static final int SPARSE_READ_TIMEOUT_MS = 90_000;
	public static final int SPARSE_REFRESH_RECV_FRAMES = 16;
	static final long SPARSE_WAIT_CHUNK_MS = 250L;

	private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();

	private final ZppRecordSink recordSink;
	private final ZppConnectionRegistry registry;
	private final ZppPacing pacing;
	private final LongSupplier now;
	private final TorActivitySink activity;
	private final SecureRandom random = new SecureRandom();
	private final Map<Integer, ReceiveActivityGate> rations =
			new ConcurrentHashMap<>();
	private final Map<Integer, ReceiveActivityGate> gates =
			new ConcurrentHashMap<>();

	public ZppConnectionRunnerImpl(ZppRecordSink recordSink,
			ZppConnectionRegistry registry, long tickIntervalMs) {
		this(recordSink, registry, new ZppPacing() {
			@Override
			public long activeIntervalMs() {
				return tickIntervalMs;
			}

			@Override
			public long idleIntervalMs() {
				return tickIntervalMs;
			}

			@Override
			public long idleAfterMs() {
				return Long.MAX_VALUE;
			}
		});
	}

	public ZppConnectionRunnerImpl(ZppRecordSink recordSink,
			ZppConnectionRegistry registry, ZppPacing pacing) {
		this(recordSink, registry, pacing, System::currentTimeMillis);
	}

	public ZppConnectionRunnerImpl(ZppRecordSink recordSink,
			ZppConnectionRegistry registry, ZppPacing pacing,
			TorActivitySink activity) {
		this(recordSink, registry, pacing, System::currentTimeMillis,
				activity);
	}

	ZppConnectionRunnerImpl(ZppRecordSink recordSink,
			ZppConnectionRegistry registry, ZppPacing pacing,
			LongSupplier now) {
		this(recordSink, registry, pacing, now, TorActivitySink.NONE);
	}

	ZppConnectionRunnerImpl(ZppRecordSink recordSink,
			ZppConnectionRegistry registry, ZppPacing pacing,
			LongSupplier now, TorActivitySink activity) {
		this.recordSink = recordSink;
		this.registry = registry;
		this.pacing = pacing;
		this.now = now;
		this.activity = activity;
	}

	boolean grantsOpeningWindow(int contactId, long at) {
		return rationFor(contactId).admitOpening(at);
	}

	ReceiveActivityGate rationFor(int contactId) {
		return rations.computeIfAbsent(contactId, id ->
				new ReceiveActivityGate(now, REPLY_WINDOW_MS,
						OPENING_WINDOW_INTERVAL_MS));
	}

	ReceiveActivityGate gateFor(int contactId) {
		return gates.computeIfAbsent(contactId, id ->
				new ReceiveActivityGate(now, REPLY_WINDOW_MS,
						RECEIVE_ACTIVATION_INTERVAL_MS));
	}

	static void wire(ZppSendScheduler scheduler, SlotClock clock) {
		scheduler.setWakeListener(clock::snap);
	}

	static boolean isActive(int queueDepth, long lastRealMs, long now,
			long idleAfterMs) {
		if (queueDepth > 0) return true;
		return lastRealMs != NEVER && now - lastRealMs < idleAfterMs;
	}

	static long keepaliveSlot(long lastSentAt, long periodMs) {
		long earliest = lastSentAt + periodMs / 2;
		long slots = (earliest + periodMs - 1) / periodMs;
		return slots * periodMs;
	}

	static boolean sparseFrameDue(long lastSentAt, int receivedSinceSend,
			long now, long periodMs) {
		if (lastSentAt == NEVER) return true;
		if (receivedSinceSend >= SPARSE_REFRESH_RECV_FRAMES) return true;
		if (now < lastSentAt) return true;
		return now >= keepaliveSlot(lastSentAt, periodMs);
	}

	static boolean echoWanted(long lastSentAt, long now, long periodMs) {
		if (lastSentAt == NEVER) return false;
		if (now < lastSentAt) return true;
		return now - lastSentAt >= periodMs / 2;
	}

	static boolean sparseSendDue(long lastSentAt, int receivedSinceSend,
			long now, long periodMs, boolean echo) {
		if (sparseFrameDue(lastSentAt, receivedSinceSend, now, periodMs)) {
			return true;
		}
		return echo && echoWanted(lastSentAt, now, periodMs);
	}

	@Override
	public void run(int contactId, ZwfDuplexConnection connection)
			throws IOException {
		run(contactId, connection, null);
	}

	@Override
	public void run(int contactId, ZwfDuplexConnection connection,
			@Nullable ZtpReadTimeoutControl readTimeout) throws IOException {
		AtomicBoolean running = new AtomicBoolean(true);
		ZppSendScheduler scheduler =
				new ZppSendScheduler(connection::sendMessage,
						connection::isPqReady);
		long openedAt = now.getAsLong();
		SlotClock clock = new SlotClock(now,
				grantsOpeningWindow(contactId, openedAt) ? openedAt : NEVER);
		ReceiveActivityGate gate = gateFor(contactId);
		ReceiveActivityGate ration = rationFor(contactId);
		wire(scheduler, clock);
		LinkState link = new LinkState();
		AtomicInteger receivedSinceSend = new AtomicInteger();
		AtomicLong lastSentAtRef = new AtomicLong(NEVER);
		AtomicBoolean echoDue = new AtomicBoolean(false);
		long sessionId = NEXT_SESSION_ID.incrementAndGet();
		ZwfControlHandler control = connection.getControlHandler();
		boolean opened = false;
		boolean pqReadySeen = false;
		AtomicBoolean authenticated = new AtomicBoolean(false);
		Object sourceLock = new Object();
		boolean[] source = new boolean[2];
		Runnable startSource = () -> {
			if (!authenticated.get()) return;
			if (control != null && control.holdsUserRecords()) return;
			synchronized (sourceLock) {
				if (source[0] || source[1]) return;
				source[0] = true;
				registry.onConnectionOpened(contactId, scheduler,
						connection.getMaxMessageLength());
			}
			scheduler.wake();
		};
		Thread ticker = new Thread(
				() -> tickLoop(scheduler, clock, gate, ration, running, link,
						receivedSinceSend, lastSentAtRef, echoDue, readTimeout),
				"zpp-send-" + contactId);
		ticker.start();
		try {
			if (control != null) {
				scheduler.setHold(control::holdsUserRecords);
				control.start(new ZwfControlHandler.Sender() {
					@Override
					public void send(byte[] payload) {
						scheduler.enqueueControl(ZmmRecord.encode(
								ZmmConstants.TYPE_ROOT_EVOLUTION, payload));
					}

					@Override
					public void sendWhenDue(Supplier<byte[]> builder) {
						scheduler.enqueueControlWhenDue(() -> {
							byte[] payload = builder.get();
							return payload == null ? null : ZmmRecord.encode(
									ZmmConstants.TYPE_ROOT_EVOLUTION, payload);
						});
					}

					@Override
					public void close() {
						running.set(false);
						connection.closeStreams();
					}

					@Override
					public void released() {
						startSource.run();
					}
				});
			}
			while (running.get()) {
				byte[] record;
				try {
					record = connection.receiveMessage();
				} catch (IOException e) {
					break;
				}
				if (record == null) {
					break;
				}
				if (!opened) {
					opened = true;
					authenticated.set(true);
					recordSink.onConnected(contactId, sessionId);
				}
				startSource.run();
				int received = receivedSinceSend.incrementAndGet();
				if (!link.dense(pacing.coverTraffic())) {
					boolean echo = link.peerTolerant() && echoWanted(
							lastSentAtRef.get(), now.getAsLong(),
							link.keepalivePeriodMs());
					if (echo) {
						activity.noteActivity(Activity.KEEPALIVE);
						echoDue.set(true);
					}
					if (echo || received >= SPARSE_REFRESH_RECV_FRAMES) {
						clock.snap();
					}
					if (!pqReadySeen && connection.isPqReady()) {
						pqReadySeen = true;
						clock.snap();
					}
				}
				if (record.length < 2 || ZmmRecord.isCover(record)) continue;
				activity.noteActivity(Activity.CONNECTION_DATA);
				if (!connection.lastFrameCarriedPqSecret()) continue;
				int type = ZmmRecord.getType(record);
				if (type == ZmmConstants.TYPE_ROOT_EVOLUTION) {
					if (control != null) {
						control.onRecord(ZmmRecord.getPayload(record));
					}
					continue;
				}
				if (type == ZmmConstants.TYPE_LINK_PARAMS) {
					ZppLinkParams params =
							ZppLinkParams.decode(ZmmRecord.getPayload(record));
					if (params != null) {
						boolean localCover = pacing.coverTraffic();
						boolean before = link.dense(localCover);
						link.onPeerParams(params);
						boolean after = link.dense(localCover);
						if (after != before || !after) clock.snap();
					}
					continue;
				}
				boolean admitted = gate.admitReceipt();
				if (link.dense(pacing.coverTraffic())) {
					if (ration.admitReceipt()) clock.noteActivity();
				} else if (admitted) {
					clock.snap();
				}
				recordSink.deliver(contactId, sessionId, type,
						ZmmRecord.getPayload(record));
			}
		} finally {
			running.set(false);
			ticker.interrupt();
			joinTicker(ticker, connection);
			if (control != null) control.close();
			boolean started;
			synchronized (sourceLock) {
				started = source[0];
				source[1] = true;
			}
			if (started) registry.onConnectionClosed(contactId, scheduler);
			if (opened) recordSink.onDisconnected(contactId, sessionId);
		}
	}

	private static void joinTicker(Thread ticker,
			ZwfDuplexConnection connection) {
		try {
			ticker.join(TICKER_JOIN_MS);
			if (!ticker.isAlive()) return;
			connection.closeStreams();
			long deadline = System.currentTimeMillis() + TICKER_FORCE_JOIN_MS;
			while (ticker.isAlive()
					&& System.currentTimeMillis() < deadline) {
				ticker.join(1000);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void tickLoop(ZppSendScheduler scheduler, SlotClock clock,
			ReceiveActivityGate gate, ReceiveActivityGate ration,
			AtomicBoolean running, LinkState link,
			AtomicInteger receivedSinceSend, AtomicLong lastSentAtRef,
			AtomicBoolean echoDue,
			@Nullable ZtpReadTimeoutControl readTimeout) {
		long lastSentAt = NEVER;
		Boolean announced = null;
		boolean timeoutRaised = false;
		try {
			while (running.get()) {
				boolean localCover = pacing.coverTraffic();
				if (announced == null || announced != localCover) {
					announced = localCover;
					scheduler.enqueueControl(ZmmRecord.encode(
							ZmmConstants.TYPE_LINK_PARAMS,
							ZppLinkParams.encode(localCover)));
				}
				boolean dense = link.dense(localCover);
				if (scheduler.getQueueDepth() > 0) {
					activity.noteActivity(Activity.SYNC_PENDING);
				}
				if (!dense && !timeoutRaised && link.peerTolerant()
						&& readTimeout != null) {
					timeoutRaised = true;
					try {
						readTimeout.setReadTimeout(SPARSE_READ_TIMEOUT_MS);
					} catch (IOException ignored) {
					}
				}
				if (dense) {
					scheduler.setWakeOnAnyEnqueue(false);
					scheduler.setRanked(true);
					activity.noteActivity(Activity.COVER_TRAFFIC);
					boolean realSent = scheduler.tick();
					if (realSent) activity.noteActivity(Activity.CONNECTION_DATA);
					long sentAt = now.getAsLong();
					lastSentAt = sentAt;
					lastSentAtRef.set(sentAt);
					echoDue.set(false);
					receivedSinceSend.set(0);
					afterFrame(clock, ration, realSent,
							scheduler.lastRealFrameOpenedWindow(), sentAt);
					noteMarkedSend(gate, realSent,
							scheduler.lastRealFrameWasUserOriginated());
					boolean active = isActive(scheduler.getUrgentDepth(),
							clock.lastRealMs, sentAt, pacing.idleAfterMs());
					long activeDelay = computeInterval(pacing.activeIntervalMs(),
							pacing.activeIntervalMs() / JITTER_DIVISOR, random);
					long delay = active ? activeDelay
							: computeInterval(pacing.idleIntervalMs(),
									pacing.idleIntervalMs() / JITTER_DIVISOR,
									random);
					clock.awaitNextSlot(running, sentAt, delay, activeDelay);
				} else {
					scheduler.setWakeOnAnyEnqueue(true);
					scheduler.setRanked(false);
					long period = link.keepalivePeriodMs();
					boolean echo = echoDue.getAndSet(false);
					boolean due = sparseSendDue(lastSentAt,
							receivedSinceSend.get(), now.getAsLong(), period,
							echo);
					ZppSendScheduler.Sent sent = scheduler.tickSparse(due);
					if (sent == ZppSendScheduler.Sent.REAL) {
						activity.noteActivity(Activity.CONNECTION_DATA);
					}
					long sentAt = now.getAsLong();
					if (sent != ZppSendScheduler.Sent.NONE) {
						lastSentAt = sentAt;
						lastSentAtRef.set(sentAt);
						echoDue.set(false);
						receivedSinceSend.set(0);
						noteMarkedSend(gate,
								sent == ZppSendScheduler.Sent.REAL,
								scheduler.lastRealFrameWasUserOriginated());
					}
					long deadline;
					if (scheduler.getQueueDepth() > 0) {
						long activeDelay = computeInterval(
								pacing.activeIntervalMs(),
								pacing.activeIntervalMs() / JITTER_DIVISOR,
								random);
						long base = sent == ZppSendScheduler.Sent.NONE
								? sentAt : lastSentAt;
						deadline = base + activeDelay;
					} else {
						deadline = keepaliveSlot(lastSentAt, period);
					}
					clock.awaitSparse(running, deadline);
				}
			}
		} catch (IOException e) {
			running.set(false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	static void afterFrame(SlotClock clock, ReceiveActivityGate gate,
			boolean realSent, boolean userOriginated, long now) {
		if (realSent && userOriginated) {
			clock.lastRealMs = now;
			gate.noteLocalSend();
		}
	}

	static void noteMarkedSend(ReceiveActivityGate gate, boolean realSent,
			boolean userOriginated) {
		if (realSent && userOriginated) gate.noteLocalSend();
	}

	static final class LinkState {

		private volatile boolean peerAnnounced;
		private volatile boolean peerTolerant;
		private volatile boolean peerCoverWanted;

		void onPeerParams(ZppLinkParams params) {
			peerTolerant = params.sparseTolerant;
			peerCoverWanted = params.coverWanted;
			peerAnnounced = true;
		}

		boolean peerTolerant() {
			return peerTolerant;
		}

		boolean dense(boolean localCover) {
			if (!localCover) return false;
			return !peerAnnounced || peerCoverWanted;
		}

		long keepalivePeriodMs() {
			return peerTolerant ? SPARSE_KEEPALIVE_MS
					: SPARSE_KEEPALIVE_LEGACY_MS;
		}
	}

	static final class ReceiveActivityGate {

		private final LongSupplier clock;
		private final long replyWindowMs;
		private final long activationIntervalMs;
		private long lastLocalSendMs;
		private long lastActivationMs;
		private boolean everSent = false;
		private boolean everActivated = false;

		ReceiveActivityGate(LongSupplier clock, long replyWindowMs,
				long activationIntervalMs) {
			this.clock = clock;
			this.replyWindowMs = replyWindowMs;
			this.activationIntervalMs = activationIntervalMs;
		}

		synchronized void noteLocalSend() {
			lastLocalSendMs = clock.getAsLong();
			everSent = true;
		}

		synchronized boolean admitOpening(long at) {
			if (everActivated && at >= lastActivationMs
					&& at - lastActivationMs < activationIntervalMs) {
				return false;
			}
			everActivated = true;
			lastActivationMs = at;
			return true;
		}

		synchronized boolean admitReceipt() {
			long now = clock.getAsLong();
			if (everSent && now - lastLocalSendMs <= replyWindowMs) {
				return true;
			}
			if (!everActivated
					|| now - lastActivationMs >= activationIntervalMs) {
				everActivated = true;
				lastActivationMs = now;
				return true;
			}
			return false;
		}
	}

	static final class SlotClock {

		private final Object lock = new Object();
		private final LongSupplier clock;
		private boolean activityFlag = false;
		volatile long lastRealMs;

		SlotClock() {
			this(System::currentTimeMillis, System.currentTimeMillis());
		}

		SlotClock(LongSupplier clock, long lastRealMs) {
			this.clock = clock;
			this.lastRealMs = lastRealMs;
		}

		void noteActivity() {
			lastRealMs = clock.getAsLong();
			snap();
		}

		void snap() {
			synchronized (lock) {
				activityFlag = true;
				lock.notifyAll();
			}
		}

		void awaitNextSlot(AtomicBoolean running, long frameSentAt, long delay,
				long activeDelay) throws InterruptedException {
			long deadline = frameSentAt + delay;
			synchronized (lock) {
				activityFlag = false;
				while (running.get()) {
					long remaining = deadline - clock.getAsLong();
					if (remaining <= 0) return;
					if (activityFlag) {
						activityFlag = false;
						long snapped = frameSentAt + activeDelay;
						if (snapped < deadline) deadline = snapped;
						continue;
					}
					lock.wait(remaining);
				}
			}
		}

		void awaitSparse(AtomicBoolean running, long deadline)
				throws InterruptedException {
			synchronized (lock) {
				while (running.get()) {
					if (activityFlag) {
						activityFlag = false;
						return;
					}
					long remaining = deadline - clock.getAsLong();
					if (remaining <= 0) return;
					lock.wait(Math.min(remaining, SPARSE_WAIT_CHUNK_MS));
				}
			}
		}
	}

	static long computeInterval(long base, long jitterMs, Random random) {
		if (jitterMs <= 0) return Math.max(1, base);
		long offset = random.nextInt((int) (2 * jitterMs + 1)) - jitterMs;
		return Math.max(1, base + offset);
	}
}
