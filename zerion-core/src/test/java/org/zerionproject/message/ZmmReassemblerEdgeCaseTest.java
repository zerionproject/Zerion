package org.zerionproject.message;

import org.zerionproject.core.util.ByteUtils;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Fragment records with hostile header fields: duplicates, indexes outside
 * the count, a zero count, a short header, a count that changes between
 * fragments of the same message, an oversized message and random input. None
 * of them completes a message wrongly, throws, or leaves a partial behind
 * that blocks the contact's reassembly slots.
 */
public class ZmmReassemblerEdgeCaseTest {

	private static final int CONTACT = 3;
	private static final int RANDOM_INPUTS = 4000;

	private final Random random = new Random(11);

	@Test
	public void duplicateFragmentIsDroppedAndTheFirstCopyIsKept() {
		ZmmReassembler r = new ZmmReassembler();
		byte[] first = fragment(1, 0, 2, "first".getBytes());
		byte[] duplicate = fragment(1, 0, 2, "other".getBytes());
		byte[] last = fragment(1, 1, 2, "-last".getBytes());
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT, first));
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT, duplicate));
		ZmmReassembler.Message m = r.receive(CONTACT,
				ZmmConstants.TYPE_FRAGMENT, last);
		assertNotNull(m);
		assertEquals(ZmmConstants.TYPE_TEXT, m.type);
		assertArrayEquals("first-last".getBytes(), m.payload);
	}

	@Test
	public void indexOutsideTheCountIsDroppedWithoutOpeningAPartial() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(2, 2, 2, "x".getBytes())));
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(2, 65535, 2, "x".getBytes())));
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(2, 0, 0, "x".getBytes())));
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				new byte[ZmmFragmenter.FRAGMENT_HEADER_LENGTH - 1]));
		assertSlotsFree(r);
	}

	@Test
	public void zeroLengthFragmentsCompleteAnEmptyMessage() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(4, 0, 2, new byte[0])));
		ZmmReassembler.Message m = r.receive(CONTACT,
				ZmmConstants.TYPE_FRAGMENT, fragment(4, 1, 2, new byte[0]));
		assertNotNull(m);
		assertEquals(0, m.payload.length);
	}

	@Test
	public void countThatChangesBetweenFragmentsIsDropped() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(5, 0, 2, "a".getBytes())));
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(5, 1, 3, "b".getBytes())));
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragmentOfType(ZmmConstants.TYPE_GROUP_POST, 5, 1, 2,
						"b".getBytes())));
		ZmmReassembler.Message m = r.receive(CONTACT,
				ZmmConstants.TYPE_FRAGMENT, fragment(5, 1, 2, "b".getBytes()));
		assertNotNull(m);
		assertArrayEquals("ab".getBytes(), m.payload);
	}

	@Test
	public void oversizedMessageIsDroppedAndItsSlotFreed() {
		ZmmReassembler r = new ZmmReassembler();
		byte[] big = new byte[ZmmReassembler.MAX_MESSAGE_BYTES / 2 + 1];
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(6, 0, 2, big)));
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(6, 1, 2, big)));
		assertSlotsFree(r);
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(6, 0, 2, "a".getBytes())));
		ZmmReassembler.Message m = r.receive(CONTACT,
				ZmmConstants.TYPE_FRAGMENT, fragment(6, 1, 2, "b".getBytes()));
		assertNotNull(m);
		assertArrayEquals("ab".getBytes(), m.payload);
	}

	@Test
	public void randomFragmentRecordsNeverThrowOrCompleteAForeignType() {
		ZmmReassembler r = new ZmmReassembler();
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			byte[] payload = new byte[random.nextInt(64)];
			random.nextBytes(payload);
			ZmmReassembler.Message m = r.receive(random.nextInt(4),
					ZmmConstants.TYPE_FRAGMENT, payload);
			if (m != null) {
				assertTrue(m.type != ZmmConstants.TYPE_FRAGMENT);
			}
		}
	}

	/** All per-contact slots are free when a full set of new partials opens. */
	private static void assertSlotsFree(ZmmReassembler r) {
		for (int id = 100; id < 100 + ZmmReassembler.MAX_PARTIAL_MESSAGES_PER_CONTACT; id++) {
			assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
					fragment(id, 0, 2, "p".getBytes())));
		}
		for (int id = 100; id < 100 + ZmmReassembler.MAX_PARTIAL_MESSAGES_PER_CONTACT; id++) {
			assertNotNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
					fragment(id, 1, 2, "q".getBytes())));
		}
	}

	private static byte[] fragment(long messageId, int index, int count,
			byte[] chunk) {
		return fragmentOfType(ZmmConstants.TYPE_TEXT, messageId, index, count,
				chunk);
	}

	private static byte[] fragmentOfType(int origType, long messageId,
			int index, int count, byte[] chunk) {
		byte[] body = new byte[ZmmFragmenter.FRAGMENT_HEADER_LENGTH + chunk.length];
		ByteUtils.writeUint16(origType, body, 0);
		ByteUtils.writeUint32(messageId, body, 2);
		ByteUtils.writeUint16(index, body, 6);
		ByteUtils.writeUint16(count, body, 8);
		System.arraycopy(chunk, 0, body, ZmmFragmenter.FRAGMENT_HEADER_LENGTH,
				chunk.length);
		return body;
	}
}
