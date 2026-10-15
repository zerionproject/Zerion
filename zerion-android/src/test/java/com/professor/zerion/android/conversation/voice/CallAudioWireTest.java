package com.professor.zerion.android.conversation.voice;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class CallAudioWireTest {

	@Test
	public void markersAreFixedOnTheWire() {
		assertEquals("ZERI", new String(new byte[] {0x5A, 0x45, 0x52, 0x49},
				StandardCharsets.US_ASCII));
		assertEquals(0x5A455249, CallAudioWire.SYNC_MARKER);
		assertEquals(-1, CallAudioWire.READY_MARKER);
		assertEquals(-2, CallAudioWire.HEARTBEAT_MARKER);
		assertEquals(-3, CallAudioWire.BYE_MARKER);
		assertEquals(28, CallAudioWire.FRAME_HEADER_BYTES);
	}

	@Test
	public void thePreambleIsSyncThenReady() throws Exception {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		CallAudioWire.writePreamble(new DataOutputStream(b));
		assertArrayEquals(new byte[] {0x5A, 0x45, 0x52, 0x49, -1, -1, -1, -1},
				b.toByteArray());
	}

	@Test
	public void aFrameIsLengthSequenceZeroChecksumPayload() throws Exception {
		byte[] payload = {9, 8, 7};
		long crc = CallAudioWire.checksum(payload);
		CRC32 expected = new CRC32();
		expected.update(payload);
		assertEquals(expected.getValue(), crc);
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		CallAudioWire.writeFrame(new DataOutputStream(b), 0x0102030405060708L,
				crc, payload);
		byte[] wire = b.toByteArray();
		assertEquals(CallAudioWire.FRAME_HEADER_BYTES + 3, wire.length);
		assertArrayEquals(new byte[] {0, 0, 0, 3},
				Arrays.copyOfRange(wire, 0, 4));
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
				Arrays.copyOfRange(wire, 4, 12));
		assertArrayEquals(new byte[8], Arrays.copyOfRange(wire, 12, 20));
		assertArrayEquals(payload, Arrays.copyOfRange(wire, 28, 31));

		DataInputStream in = new DataInputStream(new ByteArrayInputStream(
				wire));
		assertEquals(3, in.readInt());
		CallAudioWire.FrameHeader h = CallAudioWire.readFrameHeader(in);
		assertEquals(0x0102030405060708L, h.sequence);
		assertEquals(crc, h.checksum);
		byte[] body = new byte[3];
		in.readFully(body);
		assertArrayEquals(payload, body);
	}

	@Test
	public void aSkippedPayloadIsConsumedExactly() throws Exception {
		byte[] wire = new byte[5000 + 4];
		wire[5000] = 0x0A;
		wire[5003] = 0x0B;
		DataInputStream in = new DataInputStream(new java.io
				.BufferedInputStream(new ByteArrayInputStream(wire), 64));
		assertEquals(true, CallAudioWire.skipPayload(in, 5000));
		assertEquals(0x0A00000B, in.readInt());
	}

	@Test
	public void aPayloadBeyondTheCapIsRefusedUnread() throws Exception {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(
				new byte[] {1, 2, 3, 4}));
		assertEquals(false, CallAudioWire.skipPayload(in,
				CallAudioWire.MAX_SKIPPED_PAYLOAD_BYTES + 1));
		assertEquals(0x01020304, in.readInt());
	}

	@Test(expected = java.io.EOFException.class)
	public void aTruncatedPayloadEndsTheStream() throws Exception {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(
				new byte[10]));
		CallAudioWire.skipPayload(in, 11);
	}

	@Test
	public void markersAreFourBigEndianBytes() throws Exception {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		CallAudioWire.writeMarker(b, CallAudioWire.BYE_MARKER);
		CallAudioWire.writeMarker(b, CallAudioWire.HEARTBEAT_MARKER);
		assertArrayEquals(new byte[] {-1, -1, -1, -3, -1, -1, -1, -2},
				b.toByteArray());
	}
}
