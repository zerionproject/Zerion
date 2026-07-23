package org.zerionproject.sync;

import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmRecord;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ZppSendSchedulerTest {

	private static class RecordingSink implements ZppSendScheduler.FrameSink {
		final List<byte[]> sent = new ArrayList<>();

		@Override
		public void send(byte[] zmmRecord) {
			sent.add(zmmRecord);
		}
	}

	@Test
	public void everyTickSendsExactlyOneFrame() throws Exception {
		RecordingSink sink = new RecordingSink();
		ZppSendScheduler s = new ZppSendScheduler(sink);
		for (int i = 0; i < 10; i++) s.tick();
		assertEquals(10, sink.sent.size());
	}

	@Test
	public void idleTicksSendCover() throws Exception {
		RecordingSink sink = new RecordingSink();
		ZppSendScheduler s = new ZppSendScheduler(sink);
		s.tick();
		s.tick();
		assertEquals(2, sink.sent.size());
		for (byte[] rec : sink.sent) {
			assertTrue(ZmmRecord.isCover(rec));
		}
		assertEquals(0, s.getRealFrameCount());
		assertEquals(2, s.getCoverFrameCount());
	}

	@Test
	public void queuedMessagesGoOutOneToASlotInOrderCoverFillsGaps()
			throws Exception {
		RecordingSink sink = new RecordingSink();
		ZppSendScheduler s = new ZppSendScheduler(sink);
		s.enqueue(ZmmConstants.TYPE_TEXT,
				"one".getBytes(StandardCharsets.UTF_8));
		s.enqueue(ZmmConstants.TYPE_TEXT,
				"two".getBytes(StandardCharsets.UTF_8));
		s.enqueue(ZmmConstants.TYPE_TEXT,
				"three".getBytes(StandardCharsets.UTF_8));

		// three real frames, then two cover frames - all one per slot
		for (int i = 0; i < 5; i++) s.tick();

		assertEquals(5, sink.sent.size());
		assertEquals(3, s.getRealFrameCount());
		assertEquals(2, s.getCoverFrameCount());

		assertEquals(ZmmConstants.TYPE_TEXT, ZmmRecord.getType(sink.sent.get(0)));
		assertEquals("one", new String(ZmmRecord.getPayload(sink.sent.get(0)),
				StandardCharsets.UTF_8));
		assertEquals("two", new String(ZmmRecord.getPayload(sink.sent.get(1)),
				StandardCharsets.UTF_8));
		assertEquals("three", new String(ZmmRecord.getPayload(sink.sent.get(2)),
				StandardCharsets.UTF_8));
		assertTrue(ZmmRecord.isCover(sink.sent.get(3)));
		assertTrue(ZmmRecord.isCover(sink.sent.get(4)));
	}

	@Test
	public void surplusWaitsForNextSlotNoBurst() throws Exception {
		RecordingSink sink = new RecordingSink();
		ZppSendScheduler s = new ZppSendScheduler(sink);
		// enqueue five, but only tick twice: exactly two go out, three remain.
		for (int i = 0; i < 5; i++) {
			s.enqueue(ZmmConstants.TYPE_TEXT, new byte[]{(byte) i});
		}
		s.tick();
		s.tick();
		assertEquals(2, sink.sent.size());
		assertEquals(3, s.getQueueDepth());
	}
}
