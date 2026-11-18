package org.zerionproject.sync;

import org.junit.Test;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.NEVER;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.SPARSE_KEEPALIVE_LEGACY_MS;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.SPARSE_KEEPALIVE_MS;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.SPARSE_READ_TIMEOUT_MS;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.SPARSE_REFRESH_RECV_FRAMES;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.keepaliveSlot;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.sparseFrameDue;

public class ZppSparseModeTest {

	@Test
	public void coverTrafficIsOffByDefault() {
		assertFalse(ZppPacingPolicy.DEFAULT_COVER_TRAFFIC);
		assertFalse(new ZppPacingPolicy().coverTraffic());
		ZppPacingPolicy policy = new ZppPacingPolicy();
		policy.setCoverTraffic(true);
		assertTrue(policy.coverTraffic());
	}

	@Test
	public void sparseTickSendsNothingWhenIdleAndNotDue() throws IOException {
		List<byte[]> frames = new ArrayList<>();
		ZppSendScheduler scheduler = new ZppSendScheduler(frames::add);
		assertEquals(ZppSendScheduler.Sent.NONE, scheduler.tickSparse(false));
		assertTrue(frames.isEmpty());
		assertEquals(0, scheduler.getCoverFrameCount());
	}

	@Test
	public void sparseTickSendsCoverOnlyWhenForced() throws IOException {
		List<byte[]> frames = new ArrayList<>();
		ZppSendScheduler scheduler = new ZppSendScheduler(frames::add);
		assertEquals(ZppSendScheduler.Sent.COVER, scheduler.tickSparse(true));
		assertEquals(1, frames.size());
		assertTrue(ZmmRecord.isCover(frames.get(0)));
	}

	@Test
	public void sparseTickSendsQueuedRecordWithoutCover() throws IOException {
		List<byte[]> frames = new ArrayList<>();
		ZppSendScheduler scheduler = new ZppSendScheduler(frames::add);
		byte[] record = ZmmRecord.encode(ZmmConstants.TYPE_TEXT,
				new byte[] {1, 2, 3});
		scheduler.enqueueRecord(record, false);
		assertEquals(ZppSendScheduler.Sent.REAL, scheduler.tickSparse(false));
		assertEquals(1, frames.size());
		assertArrayEquals(record, frames.get(0));
		assertEquals(ZppSendScheduler.Sent.NONE, scheduler.tickSparse(false));
		assertEquals(1, frames.size());
	}

	@Test
	public void denseTickStillSendsExactlyOneFrame() throws IOException {
		List<byte[]> frames = new ArrayList<>();
		ZppSendScheduler scheduler = new ZppSendScheduler(frames::add);
		assertFalse(scheduler.tick());
		scheduler.enqueue(ZmmConstants.TYPE_TEXT, new byte[] {9});
		assertTrue(scheduler.tick());
		assertFalse(scheduler.tick());
		assertEquals(3, frames.size());
		assertEquals(2, scheduler.getCoverFrameCount());
		assertEquals(1, scheduler.getRealFrameCount());
	}

	@Test
	public void nonUserRecordsWakeTheTickerOnlyInSparseMode() {
		ZppSendScheduler scheduler = new ZppSendScheduler(f -> {
		});
		AtomicInteger wakes = new AtomicInteger();
		scheduler.setWakeListener(wakes::incrementAndGet);
		byte[] record = ZmmRecord.encode(ZmmConstants.TYPE_ACK, new byte[0]);
		scheduler.enqueueRecord(record, false);
		assertEquals(0, wakes.get());
		scheduler.setWakeOnAnyEnqueue(true);
		scheduler.enqueueRecord(record, false);
		assertEquals(1, wakes.get());
		scheduler.enqueueRecord(record, true);
		assertEquals(2, wakes.get());
	}

	@Test
	public void firstSparseFrameIsAlwaysDue() {
		assertTrue(sparseFrameDue(NEVER, 0, 1_000, SPARSE_KEEPALIVE_MS));
	}

	@Test
	public void refreshFrameIsDueAfterSixteenReceivedFrames() {
		long last = 100_000;
		assertFalse(sparseFrameDue(last, SPARSE_REFRESH_RECV_FRAMES - 1,
				last + 1, SPARSE_KEEPALIVE_MS));
		assertTrue(sparseFrameDue(last, SPARSE_REFRESH_RECV_FRAMES, last + 1,
				SPARSE_KEEPALIVE_MS));
	}

	@Test
	public void keepaliveSlotsAlignAcrossConnections() {
		long p = SPARSE_KEEPALIVE_MS;
		long a = 10 * p + 1_000;
		long b = 10 * p + 7_000;
		assertEquals(keepaliveSlot(a, p), keepaliveSlot(b, p));
		assertEquals(0, keepaliveSlot(a, p) % p);
	}

	@Test
	public void keepaliveGapNeverExceedsOneAndAHalfPeriods() {
		for (long p : new long[] {SPARSE_KEEPALIVE_LEGACY_MS,
				SPARSE_KEEPALIVE_MS}) {
			for (long last = 50 * p; last < 52 * p; last += 97) {
				long slot = keepaliveSlot(last, p);
				assertTrue(slot > last);
				assertTrue(slot - last >= p / 2);
				assertTrue(slot - last <= p + p / 2);
				assertEquals(0, slot % p);
			}
		}
	}

	@Test
	public void sparseKeepaliveStaysInsideTheRaisedReadTimeout() {
		long maxGap = SPARSE_KEEPALIVE_MS + SPARSE_KEEPALIVE_MS / 2;
		assertTrue(2 * maxGap <= SPARSE_READ_TIMEOUT_MS);
	}

	@Test
	public void refreshKeepsThePeerBelowItsKeyPairCutoff() {
		assertTrue(SPARSE_REFRESH_RECV_FRAMES * 4
				< org.zerionproject.core.api.crypto.pcs.PcsConstants
				.MODE3_FULL_MAX_RECV_FRAMES_PER_KEY_PAIR);
		assertTrue(SPARSE_REFRESH_RECV_FRAMES
				>= org.zerionproject.core.api.crypto.pcs.PcsConstants
				.MODE3_FULL_RECV_ROTATION_INTERVAL);
	}

	@Test
	public void linkParamsRoundTrip() {
		ZppLinkParams on = ZppLinkParams.decode(ZppLinkParams.encode(true));
		assertNotNull(on);
		assertTrue(on.sparseTolerant);
		assertTrue(on.coverWanted);
		ZppLinkParams off = ZppLinkParams.decode(ZppLinkParams.encode(false));
		assertNotNull(off);
		assertTrue(off.sparseTolerant);
		assertFalse(off.coverWanted);
		assertNull(ZppLinkParams.decode(new byte[] {1}));
		assertNull(ZppLinkParams.decode(new byte[] {0, 3}));
		ZppLinkParams future = ZppLinkParams.decode(new byte[] {2, 3, 7, 7});
		assertNotNull(future);
		assertTrue(future.coverWanted);
	}

	@Test
	public void linkIsDenseOnlyWhenBothSidesWantCover() {
		ZppConnectionRunnerImpl.LinkState link =
				new ZppConnectionRunnerImpl.LinkState();
		assertTrue(link.dense(true));
		assertFalse(link.dense(false));
		assertFalse(link.peerTolerant());
		assertEquals(SPARSE_KEEPALIVE_LEGACY_MS, link.keepalivePeriodMs());
		link.onPeerParams(new ZppLinkParams(true, false));
		assertFalse(link.dense(true));
		assertTrue(link.peerTolerant());
		assertEquals(SPARSE_KEEPALIVE_MS, link.keepalivePeriodMs());
		link.onPeerParams(new ZppLinkParams(true, true));
		assertTrue(link.dense(true));
		assertFalse(link.dense(false));
	}

	@Test
	public void sparseWaitHonoursAWakeThatArrivedBeforeIt() throws Exception {
		ZppConnectionRunnerImpl.SlotClock clock =
				new ZppConnectionRunnerImpl.SlotClock();
		AtomicBoolean running = new AtomicBoolean(true);
		clock.snap();
		long start = System.currentTimeMillis();
		clock.awaitSparse(running, start + 60_000);
		assertTrue(System.currentTimeMillis() - start < 5_000);
	}

	@Test
	public void sparseWaitReturnsAtTheDeadline() throws Exception {
		ZppConnectionRunnerImpl.SlotClock clock =
				new ZppConnectionRunnerImpl.SlotClock();
		AtomicBoolean running = new AtomicBoolean(true);
		long start = System.currentTimeMillis();
		clock.awaitSparse(running, start + 200);
		long elapsed = System.currentTimeMillis() - start;
		assertTrue("returned too early: " + elapsed, elapsed >= 180);
	}

	@Test
	public void sparseWaitWakesOnActivity() throws Exception {
		ZppConnectionRunnerImpl.SlotClock clock =
				new ZppConnectionRunnerImpl.SlotClock();
		AtomicBoolean running = new AtomicBoolean(true);
		long start = System.currentTimeMillis();
		Thread waiter = new Thread(() -> {
			try {
				clock.awaitSparse(running, start + 60_000);
			} catch (InterruptedException ignored) {
			}
		});
		waiter.start();
		Thread.sleep(100);
		clock.snap();
		waiter.join(10_000);
		assertFalse(waiter.isAlive());
		running.set(false);
	}

	@Test
	public void linkParamsRecordTypeIsOutsideTheApplicationRange() {
		assertEquals(0xF5, ZmmConstants.TYPE_LINK_PARAMS);
		assertTrue(ZmmConstants.TYPE_LINK_PARAMS > ZmmConstants.TYPE_ROOT_EVOLUTION);
	}
}
