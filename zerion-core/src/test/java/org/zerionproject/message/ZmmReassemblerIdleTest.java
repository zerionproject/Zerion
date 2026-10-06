package org.zerionproject.message;

import org.zerionproject.core.test.SettableClock;
import org.zerionproject.core.util.ByteUtils;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ZmmReassemblerIdleTest {

	private static final int CONTACT = 5;
	private static final long SESSION = 55L;

	private final AtomicLong now = new AtomicLong(1_000_000L);

	private ZmmReassembler reassembler() {
		return new ZmmReassembler(new SettableClock(now));
	}

	@Test
	public void aPartialIdleForTheTimeoutIsDropped() {
		ZmmReassembler r = reassembler();
		assertNull(r.receive(CONTACT, SESSION, ZmmConstants.TYPE_FRAGMENT,
				fragment(1, 0, 2, "a".getBytes())));
		now.addAndGet(ZmmReassembler.PARTIAL_IDLE_TIMEOUT_MS);
		assertNull("the idle partial was dropped",
				r.receive(CONTACT, SESSION, ZmmConstants.TYPE_FRAGMENT,
						fragment(1, 1, 2, "b".getBytes())));
	}

	@Test
	public void idlePartialsGiveBackTheContactsSlots() {
		ZmmReassembler r = reassembler();
		for (int id = 0; id < ZmmReassembler.MAX_PARTIAL_MESSAGES_PER_CONTACT;
				id++) {
			assertNull(r.receive(CONTACT, SESSION, ZmmConstants.TYPE_FRAGMENT,
					fragment(id, 0, 2, "p".getBytes())));
		}
		assertNull(r.receive(CONTACT, SESSION, ZmmConstants.TYPE_FRAGMENT,
				fragment(100, 0, 2, "q".getBytes())));
		assertNull("the slots are still held", r.receive(CONTACT, SESSION,
				ZmmConstants.TYPE_FRAGMENT, fragment(100, 1, 2, "r".getBytes())));
		now.addAndGet(ZmmReassembler.PARTIAL_IDLE_TIMEOUT_MS);
		assertNull(r.receive(CONTACT, SESSION, ZmmConstants.TYPE_FRAGMENT,
				fragment(200, 0, 2, "s".getBytes())));
		ZmmReassembler.Message m = r.receive(CONTACT, SESSION,
				ZmmConstants.TYPE_FRAGMENT, fragment(200, 1, 2, "t".getBytes()));
		assertNotNull("the idle partials gave their slots back", m);
		assertArrayEquals("st".getBytes(), m.payload);
	}

	@Test
	public void aPartialThatKeepsGrowingIsNotDroppedForItsAge() {
		ZmmReassembler r = reassembler();
		int count = 4;
		for (int i = 0; i < count - 1; i++) {
			assertNull(r.receive(CONTACT, SESSION, ZmmConstants.TYPE_FRAGMENT,
					fragment(3, i, count, new byte[] {(byte) i})));
			now.addAndGet(ZmmReassembler.PARTIAL_IDLE_TIMEOUT_MS - 1);
		}
		ZmmReassembler.Message m = r.receive(CONTACT, SESSION,
				ZmmConstants.TYPE_FRAGMENT,
				fragment(3, count - 1, count, new byte[] {3}));
		assertNotNull(m);
		assertArrayEquals(new byte[] {0, 1, 2, 3}, m.payload);
	}

	private static byte[] fragment(long messageId, int index, int count,
			byte[] chunk) {
		byte[] body = new byte[ZmmFragmenter.FRAGMENT_HEADER_LENGTH + chunk.length];
		ByteUtils.writeUint16(ZmmConstants.TYPE_TEXT, body, 0);
		ByteUtils.writeUint32(messageId, body, 2);
		ByteUtils.writeUint16(index, body, 6);
		ByteUtils.writeUint16(count, body, 8);
		System.arraycopy(chunk, 0, body, ZmmFragmenter.FRAGMENT_HEADER_LENGTH,
				chunk.length);
		return body;
	}
}
