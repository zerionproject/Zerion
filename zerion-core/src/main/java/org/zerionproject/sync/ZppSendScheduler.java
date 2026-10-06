package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.message.ZmmRecord;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class ZppSendScheduler {

	public interface FrameSink {
		void send(byte[] zmmRecord) throws IOException;
	}

	private final FrameSink sink;
	private final BooleanSupplier pqReady;
	private static final class Entry {
		@javax.annotation.Nullable
		final byte[] record;
		@javax.annotation.Nullable
		final Supplier<byte[]> builder;
		final boolean userOriginated;
		@javax.annotation.Nullable
		final Runnable onSent;

		Entry(@javax.annotation.Nullable byte[] record,
				@javax.annotation.Nullable Supplier<byte[]> builder,
				boolean userOriginated,
				@javax.annotation.Nullable Runnable onSent) {
			this.record = record;
			this.builder = builder;
			this.userOriginated = userOriginated;
			this.onSent = onSent;
		}

		@javax.annotation.Nullable
		byte[] materialise() {
			if (record != null) return record;
			Supplier<byte[]> b = builder;
			return b == null ? null : b.get();
		}
	}

	private final Queue<Entry> outgoing = new ConcurrentLinkedQueue<>();

	private final Queue<Entry> bulk = new ConcurrentLinkedQueue<>();
	private final AtomicLong realFrames = new AtomicLong();
	private final AtomicLong coverFrames = new AtomicLong();
	private volatile boolean lastRealUserOriginated = false;

	@javax.annotation.Nullable
	private volatile Runnable wakeListener;

	public void setWakeListener(@javax.annotation.Nullable Runnable listener) {
		wakeListener = listener;
	}

	public ZppSendScheduler(FrameSink sink) {
		this(sink, () -> true);
	}

	public ZppSendScheduler(FrameSink sink, BooleanSupplier pqReady) {
		this.sink = sink;
		this.pqReady = pqReady;
	}

	public void enqueue(int type, byte[] payload) {
		enqueueRecord(ZmmRecord.encode(type, payload));
	}

	public void enqueueRecord(byte[] record) {
		enqueueRecord(record, true);
	}

	public void enqueueRecord(byte[] record, boolean userOriginated) {
		enqueueRecord(record, userOriginated, false);
	}

	public void enqueueRecord(byte[] record, boolean userOriginated,
			boolean bulkLane) {
		enqueueRecord(record, userOriginated, bulkLane, null);
	}

	public void enqueueRecord(byte[] record, boolean userOriginated,
			boolean bulkLane, @javax.annotation.Nullable Runnable onSent) {
		(bulkLane ? bulk : outgoing).add(
				new Entry(record, null, userOriginated, onSent));
		if (!userOriginated) return;
		Runnable listener = wakeListener;
		if (listener != null) listener.run();
	}

	public void enqueueRecordWhenDue(Supplier<byte[]> builder) {
		outgoing.add(new Entry(null, builder, false, null));
	}

	public boolean lastRealFrameWasUserOriginated() {
		return lastRealUserOriginated;
	}

	public boolean tick() throws IOException {
		byte[] bytes = null;
		Entry record = null;
		if (pqReady.getAsBoolean()) {
			while (bytes == null) {
				record = outgoing.poll();
				if (record == null) record = bulk.poll();
				if (record == null) break;
				bytes = record.materialise();
			}
		}
		if (record != null && bytes != null) {
			lastRealUserOriginated = record.userOriginated;
			sink.send(bytes);
			realFrames.incrementAndGet();
			Runnable onSent = record.onSent;
			if (onSent != null) onSent.run();
			return true;
		} else {
			sink.send(ZmmRecord.cover());
			coverFrames.incrementAndGet();
			return false;
		}
	}

	public long getRealFrameCount() {
		return realFrames.get();
	}

	public long getCoverFrameCount() {
		return coverFrames.get();
	}

	public int getQueueDepth() {
		return outgoing.size() + bulk.size();
	}

	public int getSmallDepth() {
		return outgoing.size();
	}

	public int getBulkDepth() {
		return bulk.size();
	}
}
