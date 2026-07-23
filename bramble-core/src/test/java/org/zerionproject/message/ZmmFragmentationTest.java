package org.zerionproject.message;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Round-trips records through {@link ZmmFragmenter} and {@link ZmmReassembler}:
 * a large record splits into several frame-sized fragments and rejoins exactly,
 * regardless of arrival order, while a small record passes through untouched.
 */
public class ZmmFragmentationTest {

	private static final int CONTACT = 7;

	private static byte[] randomBytes(int n, long seed) {
		byte[] b = new byte[n];
		new Random(seed).nextBytes(b);
		return b;
	}

	private static ZmmReassembler.Message feed(ZmmReassembler r, byte[] record) {
		return r.receive(CONTACT, ZmmRecord.getType(record),
				ZmmRecord.getPayload(record));
	}

	@Test
	public void smallRecordPassesThroughUnfragmented() {
		byte[] payload = randomBytes(50, 1);
		List<byte[]> records = ZmmFragmenter.fragment(ZmmConstants.TYPE_TEXT,
				payload, 1, 1698);
		assertEquals(1, records.size());
		assertEquals(ZmmConstants.TYPE_TEXT, ZmmRecord.getType(records.get(0)));

		ZmmReassembler r = new ZmmReassembler();
		ZmmReassembler.Message m = feed(r, records.get(0));
		assertEquals(ZmmConstants.TYPE_TEXT, m.type);
		assertArrayEquals(payload, m.payload);
	}

	@Test
	public void largeRecordSplitsAndRejoinsInOrder() {
		byte[] payload = randomBytes(5000, 2);
		List<byte[]> records = ZmmFragmenter.fragment(
				ZmmConstants.TYPE_GROUP_POST, payload, 42, 200);
		assertTrue("should split into several fragments", records.size() > 1);
		for (byte[] rec : records) {
			assertEquals(ZmmConstants.TYPE_FRAGMENT, ZmmRecord.getType(rec));
			assertTrue("each record fits", rec.length <= 200);
		}

		ZmmReassembler r = new ZmmReassembler();
		ZmmReassembler.Message done = null;
		for (int i = 0; i < records.size(); i++) {
			ZmmReassembler.Message m = feed(r, records.get(i));
			if (i < records.size() - 1) {
				assertNull("incomplete until last fragment", m);
			} else {
				done = m;
			}
		}
		assertEquals(ZmmConstants.TYPE_GROUP_POST, done.type);
		assertArrayEquals(payload, done.payload);
	}

	@Test
	public void reassemblesOutOfOrder() {
		byte[] payload = randomBytes(3333, 3);
		List<byte[]> records = new ArrayList<>(ZmmFragmenter.fragment(
				ZmmConstants.TYPE_MEDIA_MANIFEST, payload, 9, 128));
		assertTrue(records.size() > 2);
		Collections.shuffle(records, new Random(99));

		ZmmReassembler r = new ZmmReassembler();
		ZmmReassembler.Message done = null;
		for (byte[] rec : records) {
			ZmmReassembler.Message m = feed(r, rec);
			if (m != null) done = m;
		}
		assertEquals(ZmmConstants.TYPE_MEDIA_MANIFEST, done.type);
		assertArrayEquals(payload, done.payload);
	}

	@Test
	public void reassemblesTwoInterleavedMessages() {
		byte[] a = randomBytes(2000, 4);
		byte[] b = randomBytes(2500, 5);
		List<byte[]> ra = ZmmFragmenter.fragment(ZmmConstants.TYPE_TEXT, a, 100,
				150);
		List<byte[]> rb = ZmmFragmenter.fragment(ZmmConstants.TYPE_GROUP_POST, b,
				200, 150);

		ZmmReassembler r = new ZmmReassembler();
		ZmmReassembler.Message doneA = null, doneB = null;
		int i = 0, j = 0;
		while (i < ra.size() || j < rb.size()) {
			if (i < ra.size()) {
				ZmmReassembler.Message m = feed(r, ra.get(i++));
				if (m != null) doneA = m;
			}
			if (j < rb.size()) {
				ZmmReassembler.Message m = feed(r, rb.get(j++));
				if (m != null) doneB = m;
			}
		}
		assertEquals(ZmmConstants.TYPE_TEXT, doneA.type);
		assertArrayEquals(a, doneA.payload);
		assertEquals(ZmmConstants.TYPE_GROUP_POST, doneB.type);
		assertArrayEquals(b, doneB.payload);
	}

	/** Builds a raw TYPE_FRAGMENT record with attacker-chosen header fields. */
	private static byte[] mkFragment(int origType, long messageId, int index,
			int count, int chunkLen) {
		byte[] body = new byte[ZmmFragmenter.FRAGMENT_HEADER_LENGTH + chunkLen];
		org.zerionproject.core.util.ByteUtils.writeUint16(origType, body, 0);
		org.zerionproject.core.util.ByteUtils.writeUint32(messageId, body, 2);
		org.zerionproject.core.util.ByteUtils.writeUint16(index, body, 6);
		org.zerionproject.core.util.ByteUtils.writeUint16(count, body, 8);
		return ZmmRecord.encode(ZmmConstants.TYPE_FRAGMENT, body);
	}

	@Test
	public void rejectsExcessiveFragmentCount() {
		// A hostile peer claiming a huge fragment count must be dropped without
		// allocating, not accepted (the count field is a uint16 up to 65535).
		ZmmReassembler r = new ZmmReassembler();
		byte[] hostile = mkFragment(ZmmConstants.TYPE_TEXT, 1, 0,
				ZmmReassembler.MAX_FRAGMENTS_PER_MESSAGE + 1, 4);
		assertNull(feed(r, hostile));
		// A count at the bound is still accepted.
		byte[] ok = mkFragment(ZmmConstants.TYPE_TEXT, 2, 0,
				ZmmReassembler.MAX_FRAGMENTS_PER_MESSAGE, 4);
		assertNull(feed(r, ok)); // held (incomplete), not rejected
	}

	@Test
	public void enforcesPerContactPartialCapWithoutStarvingOthers() {
		ZmmReassembler r = new ZmmReassembler();
		// Fill one contact's partial cap with incomplete (count=2, only index 0)
		// messages.
		for (int m = 0; m < ZmmReassembler.MAX_PARTIAL_MESSAGES_PER_CONTACT; m++) {
			assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
					fragPayload(ZmmConstants.TYPE_TEXT, m, 0, 2, 4)));
		}
		// A further distinct message from the same contact cannot start: neither
		// fragment completes it (the partial is refused at creation).
		long over = ZmmReassembler.MAX_PARTIAL_MESSAGES_PER_CONTACT;
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragPayload(ZmmConstants.TYPE_TEXT, over, 0, 2, 4)));
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragPayload(ZmmConstants.TYPE_TEXT, over, 1, 2, 4)));
		// A different contact is unaffected — its two-fragment message completes.
		assertNull(r.receive(CONTACT + 1, ZmmConstants.TYPE_FRAGMENT,
				fragPayload(ZmmConstants.TYPE_TEXT, 99, 0, 2, 4)));
		assertEquals(ZmmConstants.TYPE_TEXT, r.receive(CONTACT + 1,
				ZmmConstants.TYPE_FRAGMENT,
				fragPayload(ZmmConstants.TYPE_TEXT, 99, 1, 2, 4)).type);
	}

	@Test
	public void clearContactDropsPartialsAndFreesTheCap() {
		ZmmReassembler r = new ZmmReassembler();
		for (int m = 0; m < ZmmReassembler.MAX_PARTIAL_MESSAGES_PER_CONTACT; m++) {
			r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
					fragPayload(ZmmConstants.TYPE_TEXT, m, 0, 2, 4));
		}
		r.clearContact(CONTACT);
		// After clearing, the contact can start fresh partials again and complete.
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragPayload(ZmmConstants.TYPE_TEXT, 500, 0, 2, 4)));
		assertEquals(ZmmConstants.TYPE_TEXT, r.receive(CONTACT,
				ZmmConstants.TYPE_FRAGMENT,
				fragPayload(ZmmConstants.TYPE_TEXT, 500, 1, 2, 4)).type);
	}

	private static byte[] fragPayload(int origType, long messageId, int index,
			int count, int chunkLen) {
		byte[] body = new byte[ZmmFragmenter.FRAGMENT_HEADER_LENGTH + chunkLen];
		org.zerionproject.core.util.ByteUtils.writeUint16(origType, body, 0);
		org.zerionproject.core.util.ByteUtils.writeUint32(messageId, body, 2);
		org.zerionproject.core.util.ByteUtils.writeUint16(index, body, 6);
		org.zerionproject.core.util.ByteUtils.writeUint16(count, body, 8);
		return body;
	}
}
