package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.util.ByteUtils;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmRecord;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
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

	public enum Sent {
		REAL, COVER, NONE
	}

	public static final int RANK_USER = 0;
	public static final int RANK_REPLY = 1;
	public static final int RANK_AUTOMATIC = 2;

	private final FrameSink sink;
	private final BooleanSupplier pqReady;
	private static final class Entry {
		@javax.annotation.Nullable
		final byte[] record;
		@javax.annotation.Nullable
		final Supplier<byte[]> builder;
		final boolean userOriginated;
		final boolean opensWindow;
		final int rank;
		final long fragmentOf;
		final boolean lastFragment;
		@javax.annotation.Nullable
		final Runnable onSent;

		Entry(@javax.annotation.Nullable byte[] record,
				@javax.annotation.Nullable Supplier<byte[]> builder,
				boolean userOriginated, boolean opensWindow, int rank,
				@javax.annotation.Nullable Runnable onSent) {
			this.record = record;
			this.builder = builder;
			this.userOriginated = userOriginated;
			this.opensWindow = opensWindow;
			this.rank = rank;
			this.onSent = onSent;
			if (record != null && record.length >= 12
					&& ZmmRecord.getType(record)
					== ZmmConstants.TYPE_FRAGMENT) {
				fragmentOf = ByteUtils.readUint32(record, 4);
				lastFragment = ByteUtils.readUint16(record, 8) + 1
						>= ByteUtils.readUint16(record, 10);
			} else {
				fragmentOf = -1L;
				lastFragment = true;
			}
		}

		@javax.annotation.Nullable
		byte[] materialise() {
			if (record != null) return record;
			Supplier<byte[]> b = builder;
			return b == null ? null : b.get();
		}
	}

	private final Queue<Entry> control = new ConcurrentLinkedQueue<>();

	private final Queue<Entry> outgoing = new ConcurrentLinkedQueue<>();

	private final Queue<Entry> bulk = new ConcurrentLinkedQueue<>();
	private final AtomicLong realFrames = new AtomicLong();
	private final AtomicLong coverFrames = new AtomicLong();
	private final AtomicInteger userQueued = new AtomicInteger();
	private volatile boolean ranked = false;
	@javax.annotation.Nullable
	private Entry started;
	private boolean owedUser = false;
	private boolean tookBulk = false;
	private volatile boolean lastUserFrame = false;
	private volatile boolean lastRealUserOriginated = false;
	private volatile boolean lastRealOpenedWindow = false;

	@javax.annotation.Nullable
	private volatile Runnable wakeListener;

	public void setWakeListener(@javax.annotation.Nullable Runnable listener) {
		wakeListener = listener;
	}

	private volatile BooleanSupplier held = () -> false;
	private volatile boolean wakeOnAnyEnqueue = false;

	public void setWakeOnAnyEnqueue(boolean wake) {
		wakeOnAnyEnqueue = wake;
	}

	public void setHold(BooleanSupplier held) {
		this.held = held;
	}

	public void setRanked(boolean ranked) {
		this.ranked = ranked;
	}

	public boolean isRanked() {
		return ranked;
	}

	public int getUserDepth() {
		return userQueued.get();
	}

	public int getControlDepth() {
		return control.size();
	}

	public void enqueueControl(byte[] record) {
		control.add(new Entry(record, null, false, false, RANK_USER,
				null));
		wake();
	}

	public void enqueueControlWhenDue(Supplier<byte[]> builder) {
		control.add(new Entry(null, builder, false, false, RANK_USER,
				null));
		wake();
	}

	public void wake() {
		Runnable listener = wakeListener;
		if (listener != null) listener.run();
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
		enqueueRecord(record, userOriginated, userOriginated, bulkLane,
				onSent);
	}

	public void enqueueRecord(byte[] record, boolean userOriginated,
			boolean opensWindow, boolean bulkLane,
			@javax.annotation.Nullable Runnable onSent) {
		enqueueRecord(record, userOriginated, opensWindow,
				userOriginated || opensWindow ? RANK_USER : RANK_AUTOMATIC,
				bulkLane, onSent);
	}

	public void enqueueRecord(byte[] record, boolean userOriginated,
			boolean opensWindow, int rank, boolean bulkLane,
			@javax.annotation.Nullable Runnable onSent) {
		if (rank == RANK_USER) userQueued.incrementAndGet();
		(bulkLane ? bulk : outgoing).add(new Entry(record, null,
				userOriginated, opensWindow, rank, onSent));
		if (!opensWindow && !wakeOnAnyEnqueue) return;
		Runnable listener = wakeListener;
		if (listener != null) listener.run();
	}

	public boolean lastRealFrameWasUserOriginated() {
		return lastRealUserOriginated;
	}

	public boolean lastRealFrameOpenedWindow() {
		return lastRealOpenedWindow;
	}

	public boolean lastFrameWasUsers() {
		return lastUserFrame;
	}

	public boolean tick() throws IOException {
		return tickSparse(true) == Sent.REAL;
	}

	public Sent tickSparse(boolean coverWhenIdle) throws IOException {
		byte[] bytes = null;
		Entry record = null;
		boolean users = false;
		if (pqReady.getAsBoolean()) {
			while (bytes == null) {
				users = false;
				tookBulk = false;
				record = control.poll();
				if (record == null && !held.getAsBoolean()) {
					record = ranked ? pollRanked() : outgoing.poll();
					if (record == null && !ranked) record = bulk.poll();
					if (record != null && record.rank == RANK_USER) {
						userQueued.decrementAndGet();
						users = true;
					} else if (tookBulk && userQueued.get() > 0) {
						users = true;
					}
				}
				if (record == null) break;
				bytes = record.materialise();
			}
		}
		lastUserFrame = users && record != null && bytes != null;
		if (record != null && bytes != null) {
			lastRealUserOriginated = record.userOriginated;
			lastRealOpenedWindow = record.opensWindow;
			sink.send(bytes);
			realFrames.incrementAndGet();
			Runnable onSent = record.onSent;
			if (onSent != null) onSent.run();
			return Sent.REAL;
		}
		if (!coverWhenIdle) return Sent.NONE;
		sink.send(ZmmRecord.cover());
		coverFrames.incrementAndGet();
		return Sent.COVER;
	}

	@javax.annotation.Nullable
	private Entry pollRanked() {
		Entry small = firstByRank(outgoing);
		if (small == null || (owedUser && small.rank != RANK_USER)) {
			Entry big = nextOfStarted();
			if (big == null) big = firstByRank(bulk);
			if (big != null && (small == null || big.rank == RANK_USER)) {
				bulk.remove(big);
				tookBulk = true;
				started = big.lastFragment ? null : big;
				if (big.rank == RANK_USER) owedUser = false;
				return big;
			}
		}
		if (small == null) return null;
		outgoing.remove(small);
		owedUser = small.rank != RANK_USER;
		return small;
	}

	@javax.annotation.Nullable
	private Entry nextOfStarted() {
		Entry from = started;
		if (from == null) return null;
		for (Entry e : bulk) {
			if (e.fragmentOf == from.fragmentOf) return e;
		}
		started = null;
		return null;
	}

	@javax.annotation.Nullable
	private static Entry firstByRank(Queue<Entry> lane) {
		Entry first = null;
		for (Entry e : lane) {
			if (first == null || e.rank < first.rank) first = e;
			if (first.rank == RANK_USER) break;
		}
		return first;
	}

	public long getRealFrameCount() {
		return realFrames.get();
	}

	public long getCoverFrameCount() {
		return coverFrames.get();
	}

	public int getQueueDepth() {
		return control.size() + outgoing.size() + bulk.size();
	}

	public int getSmallDepth() {
		return outgoing.size();
	}

	public int getBulkDepth() {
		return bulk.size();
	}
}
