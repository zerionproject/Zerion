package org.zerionproject.sync;

import org.zerionproject.message.ZmmRecord;
import org.zerionproject.transport.ZppConnectionRunner;
import org.zerionproject.transport.ZwfDuplexConnection;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives a live connection with the Zerion Pull Protocol's constant-rate rhythm.
 *
 * <p>The send side emits exactly one frame per fixed slot through a
 * {@link ZppSendScheduler}: the next queued record, or a cover record when idle.
 * Because a real frame and a cover frame are the same fixed-size ZWF frame,
 * "sending a message" and "sitting idle" are indistinguishable on the wire, which
 * is what defeats the statistical-disclosure timing attacks. The receive side
 * decodes each incoming frame, drops cover, and hands real records to the
 * {@link ZppRecordSink}.
 *
 * <p>The scheduler is registered while the connection is open so the message
 * layer can enqueue records for the contact, and unregistered when it ends.
 */
@NotNullByDefault
public class ZppConnectionRunnerImpl implements ZppConnectionRunner {

	private static final int JITTER_DIVISOR = 3;

	private final ZppRecordSink recordSink;
	private final ZppConnectionRegistry registry;
	private final long tickIntervalMs;
	private final SecureRandom random = new SecureRandom();

	public ZppConnectionRunnerImpl(ZppRecordSink recordSink,
			ZppConnectionRegistry registry, long tickIntervalMs) {
		this.recordSink = recordSink;
		this.registry = registry;
		this.tickIntervalMs = tickIntervalMs;
	}

	@Override
	public void run(int contactId, ZwfDuplexConnection connection)
			throws IOException {
		AtomicBoolean running = new AtomicBoolean(true);
		ZppSendScheduler scheduler =
				new ZppSendScheduler(connection::sendMessage);
		registry.onConnectionOpened(contactId, scheduler,
				connection.getMaxMessageLength());
		Thread ticker = new Thread(() -> tickLoop(scheduler, running),
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

	private void tickLoop(ZppSendScheduler scheduler, AtomicBoolean running) {
		try {
			while (running.get()) {
				scheduler.tick();
				Thread.sleep(computeInterval(tickIntervalMs,
						tickIntervalMs / JITTER_DIVISOR, random));
			}
		} catch (IOException e) {
			running.set(false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
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
