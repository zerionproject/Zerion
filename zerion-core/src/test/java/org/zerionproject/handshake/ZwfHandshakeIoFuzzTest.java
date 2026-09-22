package org.zerionproject.handshake;

import org.zerionproject.core.api.FormatException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The handshake record reader is strict about order, count and size: a
 * record of the wrong type, a duplicate, an oversized length field, a
 * truncated body and random input are refused with an {@link IOException}
 * and nothing else, and an oversized length is refused before any body byte
 * is read.
 */
public class ZwfHandshakeIoFuzzTest {

	private static final int RANDOM_INPUTS = 3000;
	private static final int MAX_MESSAGE_LENGTH = 8192;

	private final Random random = new Random(29);

	@Test
	public void outOfOrderRecordsAreRefused() throws Exception {
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		ZwfHandshakeIo writer = new ZwfHandshakeIo(
				new ByteArrayInputStream(new byte[0]), wire);
		writer.write(ZwfHandshakeIo.TYPE_EPHEMERAL_KEY, new byte[32]);
		writer.write(ZwfHandshakeIo.TYPE_STATIC_KEY, new byte[32]);
		ZwfHandshakeIo reader = reader(wire.toByteArray());
		try {
			reader.read(ZwfHandshakeIo.TYPE_STATIC_KEY);
			fail();
		} catch (FormatException expected) {
		}
	}

	@Test
	public void duplicateRecordIsRefusedWhereTheNextTypeIsExpected()
			throws Exception {
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		ZwfHandshakeIo writer = new ZwfHandshakeIo(
				new ByteArrayInputStream(new byte[0]), wire);
		writer.write(ZwfHandshakeIo.TYPE_STATIC_KEY, new byte[32]);
		writer.write(ZwfHandshakeIo.TYPE_STATIC_KEY, new byte[32]);
		ZwfHandshakeIo reader = reader(wire.toByteArray());
		assertEquals(32, reader.read(ZwfHandshakeIo.TYPE_STATIC_KEY).length);
		try {
			reader.read(ZwfHandshakeIo.TYPE_EPHEMERAL_KEY);
			fail();
		} catch (FormatException expected) {
		}
	}

	@Test
	public void oversizedLengthIsRefusedBeforeTheBodyIsRead() {
		byte[] header = {ZwfHandshakeIo.TYPE_PROOF, (byte) 0xFF, (byte) 0xFF};
		try {
			reader(header).read(ZwfHandshakeIo.TYPE_PROOF);
			fail();
		} catch (FormatException expected) {
		} catch (IOException e) {
			throw new AssertionError("the body was read: " + e, e);
		}
		byte[] justOver = {ZwfHandshakeIo.TYPE_PROOF, (byte) 0x20, (byte) 0x01};
		try {
			reader(justOver).read(ZwfHandshakeIo.TYPE_PROOF);
			fail();
		} catch (FormatException expected) {
		} catch (IOException e) {
			throw new AssertionError("the body was read: " + e, e);
		}
	}

	@Test
	public void truncatedBodyIsAnEndOfStream() throws Exception {
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		new ZwfHandshakeIo(new ByteArrayInputStream(new byte[0]), wire)
				.write(ZwfHandshakeIo.TYPE_PROOF, new byte[100]);
		byte[] bytes = wire.toByteArray();
		for (int len = 0; len < bytes.length; len += 9) {
			byte[] truncated = new byte[len];
			System.arraycopy(bytes, 0, truncated, 0, len);
			try {
				reader(truncated).read(ZwfHandshakeIo.TYPE_PROOF);
				fail("truncated to " + len);
			} catch (EOFException expected) {
			}
		}
	}

	@Test
	public void maximumLengthRoundTripsAndOneMoreIsRefusedBySender()
			throws Exception {
		byte[] payload = new byte[MAX_MESSAGE_LENGTH];
		random.nextBytes(payload);
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		ZwfHandshakeIo writer = new ZwfHandshakeIo(
				new ByteArrayInputStream(new byte[0]), wire);
		writer.write(ZwfHandshakeIo.TYPE_KEM_CIPHERTEXT, payload);
		assertArrayEquals(payload, reader(wire.toByteArray())
				.read(ZwfHandshakeIo.TYPE_KEM_CIPHERTEXT));
		try {
			writer.write(ZwfHandshakeIo.TYPE_KEM_CIPHERTEXT,
					new byte[MAX_MESSAGE_LENGTH + 1]);
			fail();
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void randomInputYieldsOnlyIoExceptions() {
		byte[] types = {ZwfHandshakeIo.TYPE_STATIC_KEY,
				ZwfHandshakeIo.TYPE_EPHEMERAL_KEY,
				ZwfHandshakeIo.TYPE_MINOR_VERSION,
				ZwfHandshakeIo.TYPE_KEM_CIPHERTEXT, ZwfHandshakeIo.TYPE_PROOF,
				ZwfHandshakeIo.TYPE_MODE3_CAPABILITY};
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			byte[] in = new byte[random.nextInt(40)];
			random.nextBytes(in);
			byte expected = types[random.nextInt(types.length)];
			try {
				byte[] payload = reader(in).read(expected);
				assertEquals(in[0], expected);
				assertEquals(in.length - 3, payload.length);
			} catch (IOException ignored) {
			} catch (RuntimeException e) {
				throw new AssertionError("random input " + i + ": " + e, e);
			}
		}
	}

	private static ZwfHandshakeIo reader(byte[] wire) {
		return new ZwfHandshakeIo(new ByteArrayInputStream(wire),
				new ByteArrayOutputStream());
	}
}
