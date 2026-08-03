package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.message.ZmmRecord;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.concurrent.ThreadSafe;

/**
 * The send side of the Zerion Pull Protocol (ZPP): constant-rate emission.
 *
 * <p>Driven by a fixed-cadence clock, {@link #tick()} emits <em>exactly one</em>
 * frame every slot — the next queued application record, or a cover record if
 * the queue is empty. Because a real frame and a cover frame are the same
 * fixed-size ZWF frame, "I have a message" and "I am idle" are indistinguishable
 * on the wire. Surplus queued records wait for the next slot, so real traffic
 * never bursts the rate — the property that defeats the statistical-disclosure
 * timing attacks that broke sealed-sender designs.
 */
@ThreadSafe
@NotNullByDefault
public class ZppSendScheduler {

	/** Sends one ZMM record as one fixed-size ZWF frame (e.g. the connection). */
	public interface FrameSink {
		void send(byte[] zmmRecord) throws IOException;
	}

	private final FrameSink sink;
	private final Queue<byte[]> outgoing = new ConcurrentLinkedQueue<>();
	private final AtomicLong realFrames = new AtomicLong();
	private final AtomicLong coverFrames = new AtomicLong();

	public ZppSendScheduler(FrameSink sink) {
		this.sink = sink;
	}

	/** Queues an application record to be sent on a future slot. */
	public void enqueue(int type, byte[] payload) {
		enqueueRecord(ZmmRecord.encode(type, payload));
	}

	/**
	 * Queues an already-encoded record to be sent on a future slot. Used for
	 * fragments from {@link org.zerionproject.message.ZmmFragmenter}, which are
	 * emitted pre-encoded.
	 */
	public void enqueueRecord(byte[] record) {
		outgoing.add(record);
	}

	/**
	 * Emits one frame for this slot: the next queued record, or cover if idle.
	 * Call once per constant-rate clock tick.
	 */
	public void tick() throws IOException {
		byte[] record = outgoing.poll();
		if (record != null) {
			sink.send(record);
			realFrames.incrementAndGet();
		} else {
			sink.send(ZmmRecord.cover());
			coverFrames.incrementAndGet();
		}
	}

	public long getRealFrameCount() {
		return realFrames.get();
	}

	public long getCoverFrameCount() {
		return coverFrames.get();
	}

	public int getQueueDepth() {
		return outgoing.size();
	}
}
