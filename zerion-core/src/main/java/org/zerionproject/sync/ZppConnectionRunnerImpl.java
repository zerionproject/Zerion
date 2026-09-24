package org.zerionproject.sync;

import org.zerionproject.message.ZmmRecord;
import org.zerionproject.transport.ZppConnectionRunner;
import org.zerionproject.transport.ZwfDuplexConnection;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Drives a live connection with the Zerion Pull Protocol's constant-rate rhythm.
 *
 * <p>The send side emits exactly one frame per slot through a
 * {@link ZppSendScheduler}: the next queued record, or a cover record when idle.
 * Because a real frame and a cover frame are the same fixed-size ZWF frame,
 * "sending a message" and "sitting idle" are indistinguishable on the wire
 * within a regime. The receive side decodes each incoming frame, drops cover,
 * and hands real records to the {@link ZppRecordSink}.
 *
 * <p>The slot cadence has two constant regimes supplied by {@link ZppPacing}:
 * the active interval while an application record has been sent or received
 * within the pacing window (or records are queued), and a slower idle interval
 * afterwards. Both regimes are constant-rate with zero-mean jitter, so within a
 * regime the wire pattern leaks nothing about content; an observer of an
 * established connection can see at most the coarse regime transitions. When a
 * record is queued or received during an idle gap, the current gap is shortened
 * to the active interval measured from the previous frame, never less, so
 * activity onset cannot produce a frame spacing tighter than the active
 * cadence.
 *
 * <p>A received record extends the active regime only while this side is
 * itself taking part: within {@link #REPLY_WINDOW_MS} of its own last real
 * send every receipt extends the window, otherwise receipts alone may start
 * one active window per {@link #RECEIVE_ACTIVATION_INTERVAL_MS}. A peer that
 * merely keeps sending therefore cannot hold this side at the active cadence
 * indefinitely; its records are still delivered, only the cover cadence
 * stays idle.
 *
 * <p>The scheduler is registered while the connection is open so the message
 * layer can enqueue records for the contact, and unregistered when it ends.
 */
@NotNullByDefault
public class ZppConnectionRunnerImpl implements ZppConnectionRunner {

	private static final int JITTER_DIVISOR = 3;
	static final long REPLY_WINDOW_MS = 10 * 60_000L;
	static final long RECEIVE_ACTIVATION_INTERVAL_MS = 10 * 60_000L;

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
		registry.onConnectionOpened(contactId, scheduler,
				connection.getMaxMessageLength());
		Thread ticker = new Thread(
				() -> tickLoop(scheduler, clock, gate, running),
				"zpp-send-" + contactId);
		ticker.start();
		try {
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
				if (record.length >= 2 && !ZmmRecord.isCover(record)) {
					if (gate.admitReceipt()) clock.noteActivity();
					recordSink.deliver(contactId, ZmmRecord.getType(record),
							ZmmRecord.getPayload(record));
				}
			}
		} finally {
			running.set(false);
			ticker.interrupt();
			try {
				ticker.join(5000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			registry.onConnectionClosed(contactId, scheduler);
			recordSink.onDisconnected(contactId);
		}
	}

	private void tickLoop(ZppSendScheduler scheduler, SlotClock clock,
			ReceiveActivityGate gate, AtomicBoolean running) {
		try {
			while (running.get()) {
				boolean realSent = scheduler.tick();
				long now = System.currentTimeMillis();
				if (realSent) {
					clock.lastRealMs = now;
					if (scheduler.lastRealFrameWasUserOriginated()) {
						gate.noteLocalSend();
					}
				}
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

	/**
	 * Decides whether a received record may extend the active regime. The
	 * peer's records never carry the decision on their own: receipts extend
	 * the regime while this side has sent a real record within the reply
	 * window, and otherwise start at most one active window per activation
	 * interval.
	 */
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

	/**
	 * Per-connection slot state. {@link #awaitNextSlot} sleeps until the slot
	 * deadline; activity noted during the wait shortens the deadline to the
	 * active-cadence spacing measured from the previous frame, never less.
	 */
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

	/**
	 * Returns the next inter-frame delay: {@code base} plus a uniform jitter in
	 * {@code [-jitterMs, +jitterMs]}. The jitter is zero-mean so the average
	 * cadence stays {@code base}, and the result is clamped to at least 1ms so a
	 * frame is never sent back-to-back (no bursting). The offset is independent
	 * of message content, so it leaks nothing.
	 */
	static long computeInterval(long base, long jitterMs, Random random) {
		if (jitterMs <= 0) return Math.max(1, base);
		long offset = random.nextInt((int) (2 * jitterMs + 1)) - jitterMs;
		return Math.max(1, base + offset);
	}
}
