package org.zerionproject.sync;

import org.junit.Test;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZppSendSchedulerHoldTest {

	private static String kind(byte[] record) {
		if (ZmmRecord.isCover(record)) return "cover";
		int type = ZmmRecord.getType(record);
		return type == ZmmConstants.TYPE_ROOT_EVOLUTION ? "control"
				: type == ZmmConstants.TYPE_TEXT ? "text" : "chunk";
	}

	@Test
	public void whileHeldOnlyControlRecordsAndCoverLeave() throws Exception {
		List<String> sent = new ArrayList<>();
		AtomicBoolean held = new AtomicBoolean(true);
		ZppSendScheduler s = new ZppSendScheduler(r -> sent.add(kind(r)));
		s.setHold(held::get);
		s.enqueue(ZmmConstants.TYPE_TEXT, new byte[] {1});
		s.enqueueRecord(ZmmRecord.encode(ZmmConstants.TYPE_MEDIA_CHUNK,
				new byte[] {2}), true, true);
		s.enqueueControl(ZmmRecord.encode(ZmmConstants.TYPE_ROOT_EVOLUTION,
				new byte[] {3}));
		s.enqueueControlWhenDue(() -> ZmmRecord.encode(
				ZmmConstants.TYPE_ROOT_EVOLUTION, new byte[] {4}));
		assertEquals(4, s.getQueueDepth());
		assertTrue(s.tick());
		assertTrue(s.tick());
		assertFalse(s.tick());
		assertFalse(s.tick());
		assertEquals(Arrays.asList("control", "control", "cover", "cover"),
				sent);
		assertEquals(2, s.getQueueDepth());
		s.enqueueControl(ZmmRecord.encode(ZmmConstants.TYPE_ROOT_EVOLUTION,
				new byte[] {5}));
		assertTrue(s.tick());
		assertFalse(s.tick());
		held.set(false);
		assertTrue(s.tick());
		assertTrue(s.tick());
		assertFalse(s.tick());
		assertEquals(Arrays.asList("control", "control", "cover", "cover",
				"control", "cover", "text", "chunk", "cover"), sent);
	}

	@Test
	public void aControlRecordLeavesBeforeRecordsThatWereQueuedEarlier()
			throws Exception {
		List<String> sent = new ArrayList<>();
		ZppSendScheduler s = new ZppSendScheduler(r -> sent.add(kind(r)));
		s.enqueue(ZmmConstants.TYPE_TEXT, new byte[] {1});
		s.enqueueControl(ZmmRecord.encode(ZmmConstants.TYPE_ROOT_EVOLUTION,
				new byte[] {2}));
		s.tick();
		s.tick();
		assertEquals(Arrays.asList("control", "text"), sent);
	}

	@Test
	public void nothingLeavesBeforeTheConnectionIsReadyHeldOrNot()
			throws Exception {
		List<String> sent = new ArrayList<>();
		AtomicBoolean ready = new AtomicBoolean(false);
		ZppSendScheduler s =
				new ZppSendScheduler(r -> sent.add(kind(r)), ready::get);
		s.enqueueControl(ZmmRecord.encode(ZmmConstants.TYPE_ROOT_EVOLUTION,
				new byte[] {1}));
		s.enqueue(ZmmConstants.TYPE_TEXT, new byte[] {2});
		assertFalse(s.tick());
		ready.set(true);
		assertTrue(s.tick());
		assertTrue(s.tick());
		assertEquals(Arrays.asList("cover", "control", "text"), sent);
	}
}
