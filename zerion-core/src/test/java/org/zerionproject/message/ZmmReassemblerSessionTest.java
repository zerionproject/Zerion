package org.zerionproject.message;

import org.zerionproject.core.util.ByteUtils;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * EXT-13-H02: closing one of two connections to a contact must not drop
 * the partial records the other connection is still completing; the
 * partials go when the contact's last connection ends.
 */
public class ZmmReassemblerSessionTest {

	private static final int CONTACT = 9;

	@Test
	public void closingOneOfTwoSessionsKeepsThePartialRecord() {
		ZmmReassembler r = new ZmmReassembler();
		r.sessionOpened(CONTACT);
		r.sessionOpened(CONTACT);
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(1, 0, 2, "first".getBytes())));
		r.sessionClosed(CONTACT);
		ZmmReassembler.Message m = r.receive(CONTACT,
				ZmmConstants.TYPE_FRAGMENT, fragment(1, 1, 2, "-last".getBytes()));
		assertNotNull("the other session completes the record", m);
		assertArrayEquals("first-last".getBytes(), m.payload);
	}

	@Test
	public void closingTheLastSessionDropsThePartialRecord() {
		ZmmReassembler r = new ZmmReassembler();
		r.sessionOpened(CONTACT);
		r.sessionOpened(CONTACT);
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(1, 0, 2, "first".getBytes())));
		r.sessionClosed(CONTACT);
		r.sessionClosed(CONTACT);
		assertNull("the first fragment is gone, so the last one starts anew",
				r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
						fragment(1, 1, 2, "-last".getBytes())));
	}

	@Test
	public void aCloseWithoutAnOpenStillClears() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(1, 0, 2, "first".getBytes())));
		r.sessionClosed(CONTACT);
		assertNull(r.receive(CONTACT, ZmmConstants.TYPE_FRAGMENT,
				fragment(1, 1, 2, "-last".getBytes())));
	}

	@Test
	public void anotherContactIsUnaffected() {
		ZmmReassembler r = new ZmmReassembler();
		r.sessionOpened(CONTACT);
		r.sessionOpened(CONTACT + 1);
		assertNull(r.receive(CONTACT + 1, ZmmConstants.TYPE_FRAGMENT,
				fragment(5, 0, 2, "a".getBytes())));
		r.sessionClosed(CONTACT);
		assertNotNull(r.receive(CONTACT + 1, ZmmConstants.TYPE_FRAGMENT,
				fragment(5, 1, 2, "b".getBytes())));
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
