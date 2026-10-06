package org.zerionproject.message;

import org.zerionproject.core.util.ByteUtils;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ZmmReassemblerSessionTest {

	private static final int CONTACT = 9;
	private static final long A = 101L;
	private static final long B = 202L;

	@Test
	public void twoSessionsReusingAMessageIdKeepTheirRecordsApart() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 0, 2, "a0".getBytes())));
		assertNull(r.receive(CONTACT, B, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 0, 3, "b0".getBytes())));
		assertNull(r.receive(CONTACT, B, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 1, 3, "b1".getBytes())));
		ZmmReassembler.Message a = r.receive(CONTACT, A,
				ZmmConstants.TYPE_FRAGMENT, fragment(0, 1, 2, "a1".getBytes()));
		ZmmReassembler.Message b = r.receive(CONTACT, B,
				ZmmConstants.TYPE_FRAGMENT, fragment(0, 2, 3, "b2".getBytes()));
		assertNotNull(a);
		assertNotNull(b);
		assertArrayEquals("a0a1".getBytes(), a.payload);
		assertArrayEquals("b0b1b2".getBytes(), b.payload);
	}

	@Test
	public void sameSizedRecordsOnTwoSessionsAreNotMixed() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 0, 2, "AA".getBytes())));
		assertNull(r.receive(CONTACT, B, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 1, 2, "bb".getBytes())));
		ZmmReassembler.Message a = r.receive(CONTACT, A,
				ZmmConstants.TYPE_FRAGMENT, fragment(0, 1, 2, "aa".getBytes()));
		ZmmReassembler.Message b = r.receive(CONTACT, B,
				ZmmConstants.TYPE_FRAGMENT, fragment(0, 0, 2, "BB".getBytes()));
		assertArrayEquals("AAaa".getBytes(), a.payload);
		assertArrayEquals("BBbb".getBytes(), b.payload);
	}

	@Test
	public void closingSessionAKeepsSessionBsPartialRecord() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 0, 2, "a0".getBytes())));
		assertNull(r.receive(CONTACT, B, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 0, 2, "b0".getBytes())));
		r.sessionClosed(A);
		ZmmReassembler.Message b = r.receive(CONTACT, B,
				ZmmConstants.TYPE_FRAGMENT, fragment(0, 1, 2, "b1".getBytes()));
		assertNotNull("session B completes its record", b);
		assertArrayEquals("b0b1".getBytes(), b.payload);
	}

	@Test
	public void closingSessionBKeepsSessionAsPartialRecord() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 0, 2, "a0".getBytes())));
		assertNull(r.receive(CONTACT, B, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 0, 2, "b0".getBytes())));
		r.sessionClosed(B);
		ZmmReassembler.Message a = r.receive(CONTACT, A,
				ZmmConstants.TYPE_FRAGMENT, fragment(0, 1, 2, "a1".getBytes()));
		assertNotNull("session A completes its record", a);
		assertArrayEquals("a0a1".getBytes(), a.payload);
	}

	@Test
	public void aClosedSessionsRecordIsCompletedOnlyByItsRetransmission() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 0, 2, "x0".getBytes())));
		r.sessionClosed(A);
		assertNull("a fragment of another session never completes it",
				r.receive(CONTACT, B, ZmmConstants.TYPE_FRAGMENT,
						fragment(0, 1, 2, "x1".getBytes())));
		assertNull(r.receive(CONTACT, B, ZmmConstants.TYPE_FRAGMENT,
				fragment(1, 0, 2, "x0".getBytes())));
		ZmmReassembler.Message x = r.receive(CONTACT, B,
				ZmmConstants.TYPE_FRAGMENT, fragment(1, 1, 2, "x1".getBytes()));
		assertNotNull("the retransmission completes on session B", x);
		assertArrayEquals("x0x1".getBytes(), x.payload);
	}

	@Test
	public void duplicateAndOutOfOrderFragmentsYieldTheRecordOnce() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
				fragment(4, 2, 3, "c".getBytes())));
		assertNull(r.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
				fragment(4, 0, 3, "a".getBytes())));
		assertNull("a duplicate fragment is ignored",
				r.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
						fragment(4, 0, 3, "X".getBytes())));
		ZmmReassembler.Message m = r.receive(CONTACT, A,
				ZmmConstants.TYPE_FRAGMENT, fragment(4, 1, 3, "b".getBytes()));
		assertArrayEquals("abc".getBytes(), m.payload);
		assertNull("a late duplicate does not yield the record again",
				r.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
						fragment(4, 1, 3, "b".getBytes())));
	}

	@Test
	public void thePartialRecordBoundOfAContactSpansItsSessions() {
		ZmmReassembler r = new ZmmReassembler();
		int limit = ZmmReassembler.MAX_PARTIAL_MESSAGES_PER_CONTACT;
		for (int i = 0; i < limit; i++) {
			assertNull(r.receive(CONTACT, i % 2 == 0 ? A : B,
					ZmmConstants.TYPE_FRAGMENT, fragment(i, 0, 2,
							"p".getBytes())));
		}
		assertNull(r.receive(CONTACT, B, ZmmConstants.TYPE_FRAGMENT,
				fragment(limit, 0, 2, "q".getBytes())));
		assertNull("the refused record holds no state",
				r.receive(CONTACT, B, ZmmConstants.TYPE_FRAGMENT,
						fragment(limit, 1, 2, "q".getBytes())));
		r.sessionClosed(A);
		assertNull(r.receive(CONTACT, B, ZmmConstants.TYPE_FRAGMENT,
				fragment(limit + 1, 0, 2, "s".getBytes())));
		assertNotNull("closing A freed its share of the bound",
				r.receive(CONTACT, B, ZmmConstants.TYPE_FRAGMENT,
						fragment(limit + 1, 1, 2, "t".getBytes())));
	}

	@Test
	public void anotherContactIsUnaffected() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(CONTACT + 1, B, ZmmConstants.TYPE_FRAGMENT,
				fragment(5, 0, 2, "a".getBytes())));
		assertNull(r.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
				fragment(5, 0, 2, "z".getBytes())));
		r.sessionClosed(A);
		assertNotNull(r.receive(CONTACT + 1, B, ZmmConstants.TYPE_FRAGMENT,
				fragment(5, 1, 2, "b".getBytes())));
	}

	@Test
	public void aRestartForgetsPartialRecordsAndTheRetransmissionCompletes() {
		ZmmReassembler before = new ZmmReassembler();
		assertNull(before.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 0, 2, "r0".getBytes())));
		ZmmReassembler after = new ZmmReassembler();
		assertNull(after.receive(CONTACT, A, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 1, 2, "r1".getBytes())));
		assertNull(after.receive(CONTACT, A + 1, ZmmConstants.TYPE_FRAGMENT,
				fragment(0, 0, 2, "r0".getBytes())));
		ZmmReassembler.Message m = after.receive(CONTACT, A + 1,
				ZmmConstants.TYPE_FRAGMENT, fragment(0, 1, 2, "r1".getBytes()));
		assertArrayEquals("r0r1".getBytes(), m.payload);
		assertEquals(ZmmConstants.TYPE_TEXT, m.type);
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
