package org.zerionproject.handshake;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ZwfHandshakeIoTest {

	@Test
	public void testRoundTripPreservesPayload() throws Exception {
		byte[] payload = new byte[100];
		for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
		byte[] wire = writeOne(ZwfHandshakeIo.TYPE_PROOF, payload);
		byte[] back = new ZwfHandshakeIo(new ByteArrayInputStream(wire),
				new ByteArrayOutputStream()).read(ZwfHandshakeIo.TYPE_PROOF);
		assertArrayEquals(payload, back);
	}

	@Test
	public void testEmptyPayloadRoundTrips() throws Exception {
		byte[] wire = writeOne(ZwfHandshakeIo.TYPE_MINOR_VERSION, new byte[0]);
		byte[] back = new ZwfHandshakeIo(new ByteArrayInputStream(wire),
				new ByteArrayOutputStream())
				.read(ZwfHandshakeIo.TYPE_MINOR_VERSION);
		assertEquals(0, back.length);
	}

	@Test
	public void testWrongTypeRejected() throws Exception {
		byte[] wire = writeOne(ZwfHandshakeIo.TYPE_STATIC_KEY, new byte[10]);
		try {
			new ZwfHandshakeIo(new ByteArrayInputStream(wire),
					new ByteArrayOutputStream()).read(ZwfHandshakeIo.TYPE_PROOF);
			fail("expected a format failure on type mismatch");
		} catch (Exception expected) {
			// FormatException
		}
	}

	private static byte[] writeOne(byte type, byte[] payload) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		new ZwfHandshakeIo(new ByteArrayInputStream(new byte[0]), bos)
				.write(type, payload);
		return bos.toByteArray();
	}
}
