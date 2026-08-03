package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestUtils;
import org.zerionproject.core.util.ByteUtils;
import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.zerionproject.core.api.transport.TransportConstants.FRAME_HEADER_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAC_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_FRAME_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_PAYLOAD_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.api.transport.TransportConstants.STREAM_HEADER_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.STREAM_HEADER_NONCE_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.TAG_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class StreamEncrypterImplTest extends BrambleTestCase {

	private final AuthenticatedCipher cipher;
	private final SecretKey streamHeaderKey, frameKey;
	private final byte[] tag, streamHeaderNonce, protocolVersionBytes;
	private final byte[] streamNumberBytes, payload;
	private final long streamNumber = 1234;
	private final int payloadLength = 123, paddingLength = 234;

	public StreamEncrypterImplTest() {
		cipher = new TestAuthenticatedCipher();
		streamHeaderKey = TestUtils.getSecretKey();
		frameKey = TestUtils.getSecretKey();
		tag = TestUtils.getRandomBytes(TAG_LENGTH);
		streamHeaderNonce =
				TestUtils.getRandomBytes(STREAM_HEADER_NONCE_LENGTH);
		protocolVersionBytes = new byte[2];
		ByteUtils.writeUint16(PROTOCOL_VERSION, protocolVersionBytes, 0);
		streamNumberBytes = new byte[8];
		ByteUtils.writeUint64(streamNumber, streamNumberBytes, 0);
		payload = TestUtils.getRandomBytes(payloadLength);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNegativePayloadLength() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.writeFrame(payload, -1, 0, false);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsNegativePaddingLength() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.writeFrame(payload, 0, -1, false);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testRejectsMaxPayloadPlusPadding() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);

		byte[] bigPayload = new byte[MAX_PAYLOAD_LENGTH + 1];
		s.writeFrame(bigPayload, MAX_PAYLOAD_LENGTH, 1, false);
	}

	@Test
	public void testAcceptsMaxPayloadIncludingPadding() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);

		byte[] bigPayload = new byte[MAX_PAYLOAD_LENGTH];
		s.writeFrame(bigPayload, MAX_PAYLOAD_LENGTH - 1, 1, false);
		assertEquals(TAG_LENGTH + STREAM_HEADER_LENGTH + MAX_FRAME_LENGTH,
				out.size());
	}

	@Test
	public void testAcceptsMaxPayloadWithoutPadding() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);

		byte[] bigPayload = new byte[MAX_PAYLOAD_LENGTH];
		s.writeFrame(bigPayload, MAX_PAYLOAD_LENGTH, 0, false);
		assertEquals(TAG_LENGTH + STREAM_HEADER_LENGTH + MAX_FRAME_LENGTH,
				out.size());
	}

	@Test
	public void testWriteUnpaddedNonFinalFrameWithTag() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.writeFrame(payload, payloadLength, 0, false);

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(tag);
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);
		byte[] expectedFrameHeader = new byte[FRAME_HEADER_LENGTH];
		FrameEncoder.encodeHeader(expectedFrameHeader, false, payloadLength, 0);
		expected.write(expectedFrameHeader);
		expected.write(payload);
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}

	@Test
	public void testWriteUnpaddedFinalFrameWithTag() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.writeFrame(payload, payloadLength, 0, true);

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(tag);
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);
		byte[] expectedFrameHeader = new byte[FRAME_HEADER_LENGTH];
		FrameEncoder.encodeHeader(expectedFrameHeader, true, payloadLength, 0);
		expected.write(expectedFrameHeader);
		expected.write(payload);
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}

	@Test
	public void testWriteUnpaddedNonFinalFrameWithoutTag() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, null, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.writeFrame(payload, payloadLength, 0, false);

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);
		byte[] expectedFrameHeader = new byte[FRAME_HEADER_LENGTH];
		FrameEncoder.encodeHeader(expectedFrameHeader, false, payloadLength, 0);
		expected.write(expectedFrameHeader);
		expected.write(payload);
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}

	@Test
	public void testWriteUnpaddedFinalFrameWithoutTag() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, null, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.writeFrame(payload, payloadLength, 0, true);

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);
		byte[] expectedFrameHeader = new byte[FRAME_HEADER_LENGTH];
		FrameEncoder.encodeHeader(expectedFrameHeader, true, payloadLength, 0);
		expected.write(expectedFrameHeader);
		expected.write(payload);
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}

	@Test
	public void testWritePaddedNonFinalFrameWithTag() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.writeFrame(payload, payloadLength, paddingLength, false);

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(tag);
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);
		byte[] expectedFrameHeader = new byte[FRAME_HEADER_LENGTH];
		FrameEncoder.encodeHeader(expectedFrameHeader, false, payloadLength,
				paddingLength);
		expected.write(expectedFrameHeader);
		expected.write(payload);
		expected.write(new byte[paddingLength]);
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}

	@Test
	public void testWritePaddedFinalFrameWithTag() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.writeFrame(payload, payloadLength, paddingLength, true);

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(tag);
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);
		byte[] expectedFrameHeader = new byte[FRAME_HEADER_LENGTH];
		FrameEncoder.encodeHeader(expectedFrameHeader, true, payloadLength,
				paddingLength);
		expected.write(expectedFrameHeader);
		expected.write(payload);
		expected.write(new byte[paddingLength]);
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}

	@Test
	public void testWritePaddedNonFinalFrameWithoutTag() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, null, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.writeFrame(payload, payloadLength, paddingLength, false);

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);
		byte[] expectedFrameHeader = new byte[FRAME_HEADER_LENGTH];
		FrameEncoder.encodeHeader(expectedFrameHeader, false, payloadLength,
				paddingLength);
		expected.write(expectedFrameHeader);
		expected.write(payload);
		expected.write(new byte[paddingLength]);
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}

	@Test
	public void testWritePaddedFinalFrameWithoutTag() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, null, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.writeFrame(payload, payloadLength, paddingLength, true);

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);
		byte[] expectedFrameHeader = new byte[FRAME_HEADER_LENGTH];
		FrameEncoder.encodeHeader(expectedFrameHeader, true, payloadLength,
				paddingLength);
		expected.write(expectedFrameHeader);
		expected.write(payload);
		expected.write(new byte[paddingLength]);
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}

	@Test
	public void testWriteTwoFramesWithTag() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);
		int payloadLength1 = 345, paddingLength1 = 456;
		byte[] payload1 = TestUtils.getRandomBytes(payloadLength1);

		s.writeFrame(payload, payloadLength, paddingLength, false);
		s.writeFrame(payload1, payloadLength1, paddingLength1, true);

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(tag);
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);
		byte[] expectedFrameHeader = new byte[FRAME_HEADER_LENGTH];
		FrameEncoder.encodeHeader(expectedFrameHeader, false, payloadLength,
				paddingLength);
		expected.write(expectedFrameHeader);
		expected.write(payload);
		expected.write(new byte[paddingLength]);
		expected.write(new byte[MAC_LENGTH]);
		byte[] expectedFrameHeader1 = new byte[FRAME_HEADER_LENGTH];
		FrameEncoder.encodeHeader(expectedFrameHeader1, true, payloadLength1,
				paddingLength1);
		expected.write(expectedFrameHeader1);
		expected.write(payload1);
		expected.write(new byte[paddingLength1]);
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}

	@Test
	public void testFlushWritesTagAndStreamHeaderIfNotAlreadyWritten()
			throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.flush();

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(tag);
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}

	@Test
	public void testFlushDoesNotWriteTagOrStreamHeaderIfAlreadyWritten()
			throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, tag, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.flush();
		s.flush();

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(tag);
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}

	@Test
	public void testFlushDoesNotWriteTagIfNull() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamEncrypterImpl s = new StreamEncrypterImpl(out, cipher,
				streamNumber, null, streamHeaderNonce, streamHeaderKey,
				frameKey);

		s.flush();

		ByteArrayOutputStream expected = new ByteArrayOutputStream();
		expected.write(streamHeaderNonce);
		expected.write(protocolVersionBytes);
		expected.write(streamNumberBytes);
		expected.write(frameKey.getBytes());
		expected.write(new byte[MAC_LENGTH]);

		assertArrayEquals(expected.toByteArray(), out.toByteArray());
	}
}
