package org.zerionproject.message;

import org.zerionproject.core.util.ByteUtils;
import org.zerionproject.crypto.ZwfMode3FullStreamEncrypter;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ZmmReassemblerBudgetTest {

	private static final int ATTACKER = 1;
	private static final int VICTIM = 2;
	private static final long ATTACKER_SESSION = 11L;
	private static final long VICTIM_SESSION = 22L;
	private static final int FRAME_CHUNK =
			ZwfMode3FullStreamEncrypter.maxMessageLength() - 2
					- ZmmFragmenter.FRAGMENT_HEADER_LENGTH;

	@Test
	public void oneContactFillingItsPartialsDoesNotStarveAnother() {
		ZmmReassembler r = new ZmmReassembler();
		for (int m = 0; m < ZmmReassembler.MAX_PARTIAL_MESSAGES_PER_CONTACT;
				m++) {
			fillIncomplete(r, ATTACKER, ATTACKER_SESSION, m,
					ZmmReassembler.MAX_MESSAGE_BYTES);
		}
		assertNull(r.receive(VICTIM, VICTIM_SESSION,
				ZmmConstants.TYPE_FRAGMENT, fragment(0, 0, 2,
						"victim-".getBytes())));
		ZmmReassembler.Message m = r.receive(VICTIM, VICTIM_SESSION,
				ZmmConstants.TYPE_FRAGMENT, fragment(0, 1, 2,
						"record".getBytes()));
		assertNotNull("the other contact's record completes", m);
		assertArrayEquals("victim-record".getBytes(), m.payload);
	}

	@Test
	public void contactsFillingTheBufferLoseAPartialToANewcomer() {
		ZmmReassembler r = new ZmmReassembler();
		int heavy = ZmmReassembler.MAX_TOTAL_BUFFERED_BYTES
				/ ZmmReassembler.MAX_MESSAGE_BYTES / 2;
		for (int c = 0; c < heavy; c++) {
			for (int m = 0; m < 2; m++) {
				fillIncomplete(r, 100 + c, 1000L + c, m,
						ZmmReassembler.MAX_MESSAGE_BYTES);
			}
		}
		assertNull(r.receive(VICTIM, VICTIM_SESSION,
				ZmmConstants.TYPE_FRAGMENT, fragment(0, 0, 2,
						"new".getBytes())));
		ZmmReassembler.Message m = r.receive(VICTIM, VICTIM_SESSION,
				ZmmConstants.TYPE_FRAGMENT, fragment(0, 1, 2,
						"comer".getBytes()));
		assertNotNull("the newcomer's record completes", m);
		assertArrayEquals("newcomer".getBytes(), m.payload);
	}

	@Test
	public void aContactOverItsByteBoundLosesItsOwnOldestPartial() {
		ZmmReassembler r = new ZmmReassembler();
		int nearlyFull = ZmmReassembler.MAX_MESSAGE_BYTES - 100;
		int chunks = chunksFor(nearlyFull);
		fillIncomplete(r, ATTACKER, ATTACKER_SESSION, 0, nearlyFull,
				chunks + 1);
		fillIncomplete(r, ATTACKER, ATTACKER_SESSION, 1, nearlyFull,
				chunks + 1);
		assertNull(r.receive(ATTACKER, ATTACKER_SESSION,
				ZmmConstants.TYPE_FRAGMENT, fragment(2, 0, 2, new byte[300])));
		assertNull("the oldest partial was dropped",
				r.receive(ATTACKER, ATTACKER_SESSION,
						ZmmConstants.TYPE_FRAGMENT,
						fragment(0, chunks, chunks + 1, new byte[100])));
		ZmmReassembler.Message kept = r.receive(ATTACKER, ATTACKER_SESSION,
				ZmmConstants.TYPE_FRAGMENT,
				fragment(1, chunks, chunks + 1, new byte[100]));
		assertNotNull("the newer partial is intact", kept);
	}

	@Test
	public void zeroLengthFragmentsNeverOpenAPartial() {
		ZmmReassembler r = new ZmmReassembler();
		for (int id = 0; id < ZmmReassembler.MAX_PARTIAL_MESSAGES_PER_CONTACT;
				id++) {
			assertNull(r.receive(VICTIM, ZmmConstants.TYPE_FRAGMENT,
					fragment(id, 0, 2, new byte[0])));
		}
		assertNull("an empty chunk is not part of the record",
				r.receive(VICTIM, ZmmConstants.TYPE_FRAGMENT,
						fragment(0, 1, 2, "b".getBytes())));
		assertNull(r.receive(VICTIM, ZmmConstants.TYPE_FRAGMENT,
				fragment(500, 0, 2, "c".getBytes())));
		assertNotNull("the contact's slots are free",
				r.receive(VICTIM, ZmmConstants.TYPE_FRAGMENT,
						fragment(500, 1, 2, "d".getBytes())));
	}

	@Test
	public void aChunkLargerThanAFrameIsRejectedWithoutOpeningAPartial() {
		ZmmReassembler r = new ZmmReassembler();
		assertNull(r.receive(VICTIM, ZmmConstants.TYPE_FRAGMENT,
				fragment(7, 0, 2, new byte[FRAME_CHUNK + 1])));
		assertNull("the oversized chunk is not part of the record",
				r.receive(VICTIM, ZmmConstants.TYPE_FRAGMENT,
						fragment(7, 1, 2, "b".getBytes())));
		assertNull(r.receive(VICTIM, ZmmConstants.TYPE_FRAGMENT,
				fragment(8, 0, 2, new byte[FRAME_CHUNK])));
		assertNotNull("a chunk of exactly one frame is accepted",
				r.receive(VICTIM, ZmmConstants.TYPE_FRAGMENT,
						fragment(8, 1, 2, "b".getBytes())));
	}

	private static int chunksFor(int bytes) {
		return (bytes + FRAME_CHUNK - 1) / FRAME_CHUNK;
	}

	private static void fillIncomplete(ZmmReassembler r, int contactId,
			long sessionId, long messageId, int bytes) {
		fillIncomplete(r, contactId, sessionId, messageId, bytes,
				ZmmReassembler.MAX_FRAGMENTS_PER_MESSAGE);
	}

	private static void fillIncomplete(ZmmReassembler r, int contactId,
			long sessionId, long messageId, int bytes, int count) {
		int full = bytes / FRAME_CHUNK;
		int rest = bytes % FRAME_CHUNK;
		byte[] chunk = new byte[FRAME_CHUNK];
		for (int i = 0; i < full; i++) {
			assertNull(r.receive(contactId, sessionId,
					ZmmConstants.TYPE_FRAGMENT,
					fragment(messageId, i, count, chunk)));
		}
		if (rest > 0) {
			assertNull(r.receive(contactId, sessionId,
					ZmmConstants.TYPE_FRAGMENT,
					fragment(messageId, full, count, new byte[rest])));
		}
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
