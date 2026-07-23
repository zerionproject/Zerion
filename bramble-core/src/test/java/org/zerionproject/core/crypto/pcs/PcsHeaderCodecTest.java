package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.zerionproject.core.crypto.pcs.PcsHeaderCodec.PcsHeader;
import org.junit.Before;
import org.junit.Test;

import java.security.SecureRandom;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.DH_PUBLIC_KEY_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.FLAG_DH_RATCHET;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.FLAG_PCS_ENABLED;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_HEADER_MAX_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_HEADER_MIN_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_PROTOCOL_VERSION;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PcsHeaderCodecTest {

	private PcsHeaderCodec codec;
	private SecureRandom random;

	@Before
	public void setUp() {
		codec = new PcsHeaderCodec();
		random = new SecureRandom();
	}

	@Test
	public void testEncodeMode2HeaderSize() {
		byte[] dhKey = new byte[DH_PUBLIC_KEY_SIZE];
		random.nextBytes(dhKey);

		byte[] header = codec.encodeMode2Header(0, 0, dhKey);
		assertEquals(PCS_HEADER_MAX_SIZE, header.length);
	}

	@Test
	public void testDecodeMode2Header() throws PcsException {
		int messageNumber = 67890;
		int prevChainLength = 100;
		byte[] dhKey = new byte[DH_PUBLIC_KEY_SIZE];
		random.nextBytes(dhKey);

		byte[] encoded = codec.encodeMode2Header(messageNumber, prevChainLength, dhKey);
		PcsHeader decoded = codec.decode(encoded);

		assertEquals(PCS_PROTOCOL_VERSION, decoded.getVersion());
		assertEquals((byte) (FLAG_PCS_ENABLED | FLAG_DH_RATCHET), decoded.getFlags());
		assertTrue(decoded.isPcsEnabled());
		assertTrue(decoded.hasDhRatchet());
		assertEquals(messageNumber, decoded.getMessageNumber());
		assertEquals(prevChainLength, decoded.getPreviousChainLength());
		assertNotNull(decoded.getDhPublicKey());
		assertArrayEquals(dhKey, decoded.getDhPublicKey());
	}

	@Test
	public void testEncodeDecodeRoundTrip() throws PcsException {
		int[] testNumbers = {0, 1, 127, 128, 255, 256, 65535, 65536,
				Integer.MAX_VALUE, Integer.MAX_VALUE - 1};
		byte[] dhKey = new byte[DH_PUBLIC_KEY_SIZE];
		random.nextBytes(dhKey);

		for (int msgNum : testNumbers) {
			byte[] encoded = codec.encodeMode2Header(msgNum, 0, dhKey);
			PcsHeader decoded = codec.decode(encoded);
			assertEquals("Failed for message number " + msgNum,
					msgNum, decoded.getMessageNumber());
		}
	}

	@Test
	public void testDecodeMode1HeaderThrows() {
		byte[] header = new byte[PCS_HEADER_MIN_SIZE];
		header[0] = PCS_PROTOCOL_VERSION;
		header[1] = FLAG_PCS_ENABLED;

		try {
			codec.decode(header);
			fail("Expected PcsException for Mode 1 header without DH ratchet");
		} catch (PcsException e) {
			assertTrue(e.getMessage().contains("DH ratchet required"));
		}
	}

	@Test
	public void testDecodeTooShortHeaderThrows() {
		byte[] tooShort = new byte[PCS_HEADER_MIN_SIZE - 1];
		try {
			codec.decode(tooShort);
			fail("Expected PcsException for short header");
		} catch (PcsException e) {
			assertTrue(e.getMessage().contains("too short"));
		}
	}

	@Test
	public void testDecodeInvalidVersionThrows() {
		byte[] dhKey = new byte[DH_PUBLIC_KEY_SIZE];
		random.nextBytes(dhKey);
		byte[] header = codec.encodeMode2Header(0, 0, dhKey);
		header[0] = (byte) (PCS_PROTOCOL_VERSION + 1);

		try {
			codec.decode(header);
			fail("Expected PcsException for invalid version");
		} catch (PcsException e) {
			assertTrue(e.getMessage().contains("Unsupported version"));
		}
	}

	@Test
	public void testDecodeMode2WithTruncatedDhKeyThrows() {
		byte[] header = new byte[PCS_HEADER_MIN_SIZE];
		header[0] = PCS_PROTOCOL_VERSION;
		header[1] = (byte) (FLAG_PCS_ENABLED | FLAG_DH_RATCHET);

		try {
			codec.decode(header);
			fail("Expected PcsException for missing DH key");
		} catch (PcsException e) {
			assertTrue(e.getMessage().contains("too short"));
		}
	}

	@Test
	public void testGetHeaderSize() {
		assertEquals(PCS_HEADER_MAX_SIZE, codec.getHeaderSize());
	}

	@Test
	public void testMode2HeaderInvalidDhKeyLengthThrows() {
		byte[] shortKey = new byte[DH_PUBLIC_KEY_SIZE - 1];
		try {
			codec.encodeMode2Header(0, 0, shortKey);
			fail("Expected IllegalArgumentException for short DH key");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("32 bytes"));
		}

		byte[] longKey = new byte[DH_PUBLIC_KEY_SIZE + 1];
		try {
			codec.encodeMode2Header(0, 0, longKey);
			fail("Expected IllegalArgumentException for long DH key");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("32 bytes"));
		}
	}

	@Test
	public void testBigEndianEncoding() throws PcsException {
		int messageNumber = 0x12345678;
		int prevChainLength = 0xAABBCCDD;
		byte[] dhKey = new byte[DH_PUBLIC_KEY_SIZE];
		random.nextBytes(dhKey);

		byte[] encoded = codec.encodeMode2Header(messageNumber, prevChainLength, dhKey);

		assertEquals((byte) 0x12, encoded[2]);
		assertEquals((byte) 0x34, encoded[3]);
		assertEquals((byte) 0x56, encoded[4]);
		assertEquals((byte) 0x78, encoded[5]);

		assertEquals((byte) 0xAA, encoded[6]);
		assertEquals((byte) 0xBB, encoded[7]);
		assertEquals((byte) 0xCC, encoded[8]);
		assertEquals((byte) 0xDD, encoded[9]);

		PcsHeader decoded = codec.decode(encoded);
		assertEquals(messageNumber, decoded.getMessageNumber());
		assertEquals(prevChainLength, decoded.getPreviousChainLength());
	}
}
