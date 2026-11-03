package org.zerionproject.core.contact.request;

import org.junit.Test;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.data.BdfForTests;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.util.ByteUtils;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_REQUEST;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_WITHDRAW;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;

public class RequestPlaintextTest extends BrambleTestCase {

	private static final String EMOJI =
			new String(Character.toChars(0x1F600));

	private final BdfReaderFactory readers = BdfForTests.readers();
	private final BdfWriterFactory writers = BdfForTests.writers();
	private final byte[] requestId = getRandomBytes(16);
	private final byte[] linkId = getRandomBytes(32);
	private final byte[] pop = getRandomBytes(32);
	private final byte[] tag = getRandomBytes(32);

	private byte[] oneTimeLink() {
		byte[] b = getRandomBytes(65);
		b[0] = 1;
		return b;
	}

	private static String repeat(String s, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) sb.append(s);
		return sb.toString();
	}

	@Test
	public void aRequestRoundTripsAtTheFixedSize() throws Exception {
		byte[] otl = oneTimeLink();
		String note = repeat(EMOJI, 200);
		RequestPlaintext p = RequestPlaintext.request(requestId, linkId, otl,
				pop, repeat("n", 50), note, 3_600_000, tag);
		byte[] encoded = p.encode(writers);
		assertEquals(1536, encoded.length);
		RequestPlaintext d = RequestPlaintext.decode(readers, encoded);
		assertEquals(KIND_REQUEST, d.kind);
		assertArrayEquals(requestId, d.requestId);
		assertArrayEquals(linkId, d.linkId);
		assertArrayEquals(otl, d.oneTimeLink);
		assertArrayEquals(pop, d.pop);
		assertEquals(repeat("n", 50), d.name);
		assertEquals(note, d.note);
		assertEquals(3_600_000, d.timestamp);
		assertArrayEquals(tag, d.senderTag);
		RequestPlaintext small = RequestPlaintext.request(requestId, linkId,
				otl, pop, "a", null, 0, tag);
		assertEquals(1536, small.encode(writers).length);
		assertNull(RequestPlaintext.decode(readers, small.encode(writers))
				.note);
	}

	@Test
	public void theReconnectFlagRoundTripsAndIsRequired() throws Exception {
		byte[] otl = oneTimeLink();
		for (boolean reconnect : new boolean[] {false, true}) {
			RequestPlaintext p = RequestPlaintext.request(requestId, linkId,
					otl, pop, "a", null, 0, tag, reconnect);
			byte[] encoded = p.encode(writers);
			assertEquals(1536, encoded.length);
			assertEquals(reconnect,
					RequestPlaintext.decode(readers, encoded).reconnect);
		}
		assertFalse(RequestPlaintext.request(requestId, linkId, otl, pop,
				"a", null, 0, tag).reconnect);
		BdfList tenFields = BdfList.of(1, KIND_REQUEST, requestId, linkId,
				otl, pop, "a", null, 0, tag);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writers.createWriter(out).writeList(tenFields);
		byte[] bdf = out.toByteArray();
		byte[] padded = new byte[1536];
		ByteUtils.writeUint16(bdf.length, padded, 0);
		System.arraycopy(bdf, 0, padded, 2, bdf.length);
		try {
			RequestPlaintext.decode(readers, padded);
			fail();
		} catch (FormatException expected) {
		}
	}

	@Test
	public void aWithdrawCarriesTheFirstFourFields() throws Exception {
		byte[] encoded = RequestPlaintext.withdraw(requestId, linkId)
				.encode(writers);
		assertEquals(1536, encoded.length);
		RequestPlaintext d = RequestPlaintext.decode(readers, encoded);
		assertEquals(KIND_WITHDRAW, d.kind);
		assertArrayEquals(requestId, d.requestId);
		assertNull(d.oneTimeLink);
		assertNull(d.name);
	}

	@Test
	public void theLimitsAreEnforcedOnBothSides() {
		String[] badNames = {"", repeat("n", 51), repeat("é", 26)};
		for (String name : badNames) {
			try {
				RequestPlaintext.request(requestId, linkId, oneTimeLink(), pop,
						name, null, 0, tag);
				fail(name);
			} catch (IllegalArgumentException expected) {
			}
		}
		assertFalse(RequestPlaintext.isValidNote(""));
		assertFalse(RequestPlaintext.isValidNote(repeat("a", 201)));
		assertTrue(RequestPlaintext.isValidNote(repeat("a", 200)));
		assertTrue(RequestPlaintext.isValidNote(repeat(EMOJI, 200)));
		assertFalse(RequestPlaintext.isValidNote(repeat(EMOJI, 200) + "a"));
		assertTrue(RequestPlaintext.isValidNote(null));
		byte[] wrongType = oneTimeLink();
		wrongType[0] = 2;
		try {
			RequestPlaintext.request(requestId, linkId, wrongType, pop, "a",
					null, 0, tag);
			fail();
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void aDecoderRefusesWhatAnEncoderWouldNotWrite() throws Exception {
		byte[] good = RequestPlaintext.request(requestId, linkId,
				oneTimeLink(), pop, "a", null, 0, tag).encode(writers);
		byte[] padding = good.clone();
		padding[1535] = 1;
		assertRefused(padding);
		byte[] length = good.clone();
		ByteUtils.writeUint16(1535, length, 0);
		assertRefused(length);
		assertRefused(encodeList(BdfList.of(1, 1, requestId, linkId)));
		assertRefused(encodeList(BdfList.of(2, 2, requestId, linkId)));
		assertRefused(encodeList(BdfList.of(1, 3, requestId, linkId)));
		assertRefused(encodeList(BdfList.of(1, 2, requestId, linkId, 5)));
		assertRefused(encodeList(BdfList.of(1, 2, getRandomBytes(15),
				linkId)));
		assertRefused(encodeList(BdfList.of(1, 1, requestId, linkId,
				oneTimeLink(), pop, repeat("n", 51), null, 0, tag)));
		assertRefused(encodeList(BdfList.of(1, 1, requestId, linkId,
				oneTimeLink(), pop, "a", repeat("b", 201), 0, tag)));
		assertRefused(new byte[1535]);
	}

	private byte[] encodeList(BdfList list) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writers.createWriter(out).writeList(list);
		byte[] bdf = out.toByteArray();
		byte[] padded = new byte[1536];
		ByteUtils.writeUint16(bdf.length, padded, 0);
		System.arraycopy(bdf, 0, padded, 2, bdf.length);
		return padded;
	}

	private void assertRefused(byte[] padded) {
		try {
			RequestPlaintext.decode(readers, padded);
			fail();
		} catch (FormatException expected) {
		}
	}
}
