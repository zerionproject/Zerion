package org.zerionproject.sync;

import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmRecord;
import org.zerionproject.transport.ZppConnectionRunner;
import org.zerionproject.transport.ZwfControlHandler;
import org.zerionproject.transport.ZwfDuplexConnection;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@NotNullByDefault
public class ZppConnectionRunnerImpl implements ZppConnectionRunner {

	private static final int JITTER_DIVISOR = 3;
	static final long REPLY_WINDOW_MS = 10 * 60_000L;
	static final long RECEIVE_ACTIVATION_INTERVAL_MS = 10 * 60_000L;
	static final long TICKER_JOIN_MS = 5_000L;
	static final long TICKER_FORCE_JOIN_MS = 30_000L;

	private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();

	private final ZppRecordSink recordSink;
	private final ZppConnectionRegistry registry;
	private final ZppPacing pacing;
	private final SecureRandom random = new SecureRandom();

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
		this.recordSink = recordSink;
		this.registry = registry;
		this.pacing = pacing;
	}

	@Override
	public void run(int contactId, ZwfDuplexConnection connection)
			throws IOException {
		AtomicBoolean running = new AtomicBoolean(true);
		ZppSendScheduler scheduler =
				new ZppSendScheduler(connection::sendMessage,
						connection::isPqReady);
		SlotClock clock = new SlotClock();
		ReceiveActivityGate gate = new ReceiveActivityGate(
				System::currentTimeMillis, REPLY_WINDOW_MS,
				RECEIVE_ACTIVATION_INTERVAL_MS);
		scheduler.setWakeListener(clock::noteActivity);
		long sessionId = NEXT_SESSION_ID.incrementAndGet();
		ZwfControlHandler control = connection.getControlHandler();
		boolean opened = false;
		Thread ticker = new Thread(
				() -> tickLoop(scheduler, clock, gate, running),
				"zpp-send-" + contactId);
		ticker.start();
		try {
			if (control != null) {
				control.start(new ZwfControlHandler.Sender() {
					@Override
					public void send(byte[] payload) {
						scheduler.enqueueRecord(ZmmRecord.encode(
								ZmmConstants.TYPE_ROOT_EVOLUTION, payload),
								false);
					}

					@Override
					public void sendWhenDue(Supplier<byte[]> builder) {
						scheduler.enqueueRecordWhenDue(() -> {
							byte[] payload = builder.get();
							return payload == null ? null : ZmmRecord.encode(
									ZmmConstants.TYPE_ROOT_EVOLUTION, payload);
						});
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
					registry.onConnectionOpened(contactId, scheduler,
							connection.getMaxMessageLength());
					recordSink.onConnected(contactId, sessionId);
				}
				if (record.length < 2 || ZmmRecord.isCover(record)) continue;
				if (!connection.lastFrameCarriedPqSecret()) continue;
				int type = ZmmRecord.getType(record);
				if (type == ZmmConstants.TYPE_ROOT_EVOLUTION) {
					if (control != null) {
						control.onRecord(ZmmRecord.getPayload(record));
					}
					continue;
				}
				if (gate.admitReceipt()) clock.noteActivity();
				recordSink.deliver(contactId, sessionId, type,
						ZmmRecord.getPayload(record));
			}
		} finally {
			running.set(false);
			ticker.interrupt();
			joinTicker(ticker, connection);
			if (control != null) control.close();
			if (opened) {
				registry.onConnectionClosed(contactId, scheduler);
				recordSink.onDisconnected(contactId, sessionId);
			}
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
			ReceiveActivityGate gate, AtomicBoolean running) {
		try {
			while (running.get()) {
				boolean realSent = scheduler.tick();
				long now = System.currentTimeMillis();
				afterFrame(clock, gate, realSent,
						scheduler.lastRealFrameWasUserOriginated(), now);
				boolean active = scheduler.getQueueDepth() > 0
						|| now - clock.lastRealMs < pacing.idleAfterMs();
				long activeDelay = computeInterval(pacing.activeIntervalMs(),
						pacing.activeIntervalMs() / JITTER_DIVISOR, random);
				long delay = active ? activeDelay
						: computeInterval(pacing.idleIntervalMs(),
								pacing.idleIntervalMs() / JITTER_DIVISOR,
								random);
				clock.awaitNextSlot(running, now, delay, activeDelay);
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
		private boolean activityFlag = false;
		volatile long lastRealMs = System.currentTimeMillis();

		void noteActivity() {
			lastRealMs = System.currentTimeMillis();
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
					long remaining = deadline - System.currentTimeMillis();
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
	}

	static long computeInterval(long base, long jitterMs, Random random) {
		if (jitterMs <= 0) return Math.max(1, base);
		long offset = random.nextInt((int) (2 * jitterMs + 1)) - jitterMs;
		return Math.max(1, base + offset);
	}
}
