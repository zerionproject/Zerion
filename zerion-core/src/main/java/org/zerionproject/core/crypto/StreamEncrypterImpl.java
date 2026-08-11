package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.StreamEncrypter;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static org.zerionproject.core.api.transport.TransportConstants.FRAME_HEADER_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.FRAME_HEADER_PLAINTEXT_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.FRAME_NONCE_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAC_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_FRAME_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_PAYLOAD_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.api.transport.TransportConstants.STREAM_HEADER_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.STREAM_HEADER_NONCE_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.STREAM_HEADER_PLAINTEXT_LENGTH;
import static org.zerionproject.core.util.ByteUtils.INT_16_BYTES;
import static org.zerionproject.core.util.ByteUtils.INT_64_BYTES;

@NotThreadSafe
@NotNullByDefault
class StreamEncrypterImpl implements StreamEncrypter {

	private final OutputStream out;
	private final AuthenticatedCipher cipher;
	private final SecretKey streamHeaderKey, frameKey;
	private final long streamNumber;
	@Nullable
	private final byte[] tag;
	private final byte[] streamHeaderNonce;
	private final byte[] frameNonce, frameHeader;
	private final byte[] framePlaintext, frameCiphertext;

	private long frameNumber;
	private boolean writeTag, writeStreamHeader;

	StreamEncrypterImpl(OutputStream out, AuthenticatedCipher cipher,
			long streamNumber, @Nullable byte[] tag, byte[] streamHeaderNonce,
			SecretKey streamHeaderKey, SecretKey frameKey) {
		this.out = out;
		this.cipher = cipher;
		this.streamNumber = streamNumber;
		this.tag = tag;
		this.streamHeaderNonce = streamHeaderNonce;
		this.streamHeaderKey = streamHeaderKey;
		this.frameKey = frameKey;
		frameNonce = new byte[FRAME_NONCE_LENGTH];
		frameHeader = new byte[FRAME_HEADER_PLAINTEXT_LENGTH];
		framePlaintext = new byte[MAX_PAYLOAD_LENGTH];
		frameCiphertext = new byte[MAX_FRAME_LENGTH];
		frameNumber = 0;
		writeTag = (tag != null);
		writeStreamHeader = true;
	}

	@Override
	public void writeFrame(byte[] payload, int payloadLength,
			int paddingLength, boolean finalFrame) throws IOException {
		if (payloadLength < 0 || paddingLength < 0)
			throw new IllegalArgumentException();
		if (payloadLength + paddingLength > MAX_PAYLOAD_LENGTH)
			throw new IllegalArgumentException();
		if (frameNumber < 0) throw new IOException();
		if (writeTag) writeTag();
		if (writeStreamHeader) writeStreamHeader();
		FrameEncoder.encodeHeader(frameHeader, finalFrame, payloadLength,
				paddingLength);
		FrameEncoder.encodeNonce(frameNonce, frameNumber, true);
		try {
			cipher.init(true, frameKey, frameNonce);
			int encrypted = cipher.process(frameHeader, 0,
					FRAME_HEADER_PLAINTEXT_LENGTH, frameCiphertext, 0);
			if (encrypted != FRAME_HEADER_LENGTH) throw new RuntimeException();
		} catch (GeneralSecurityException badCipher) {
			throw new RuntimeException(badCipher);
		}
		System.arraycopy(payload, 0, framePlaintext, 0, payloadLength);
		for (int i = 0; i < paddingLength; i++)
			framePlaintext[payloadLength + i] = 0;
		FrameEncoder.encodeNonce(frameNonce, frameNumber, false);
		try {
			cipher.init(true, frameKey, frameNonce);
			int encrypted = cipher.process(framePlaintext, 0,
					payloadLength + paddingLength, frameCiphertext,
					FRAME_HEADER_LENGTH);
			if (encrypted != payloadLength + paddingLength + MAC_LENGTH)
				throw new RuntimeException();
		} catch (GeneralSecurityException badCipher) {
			throw new RuntimeException(badCipher);
		}
		out.write(frameCiphertext, 0, FRAME_HEADER_LENGTH + payloadLength
				+ paddingLength + MAC_LENGTH);
		frameNumber++;
	}

	private void writeTag() throws IOException {
		if (tag == null) throw new IllegalStateException();
		out.write(tag, 0, tag.length);
		writeTag = false;
	}

	private void writeStreamHeader() throws IOException {
		byte[] streamHeaderPlaintext = new byte[STREAM_HEADER_PLAINTEXT_LENGTH];
		ByteUtils.writeUint16(PROTOCOL_VERSION, streamHeaderPlaintext, 0);
		ByteUtils.writeUint64(streamNumber, streamHeaderPlaintext,
				INT_16_BYTES);
		System.arraycopy(frameKey.getBytes(), 0, streamHeaderPlaintext,
				INT_16_BYTES + INT_64_BYTES, SecretKey.LENGTH);
		byte[] streamHeaderCiphertext = new byte[STREAM_HEADER_LENGTH];
		System.arraycopy(streamHeaderNonce, 0, streamHeaderCiphertext, 0,
				STREAM_HEADER_NONCE_LENGTH);
		try {
			cipher.init(true, streamHeaderKey, streamHeaderNonce);
			int encrypted = cipher.process(streamHeaderPlaintext, 0,
					STREAM_HEADER_PLAINTEXT_LENGTH, streamHeaderCiphertext,
					STREAM_HEADER_NONCE_LENGTH);
			if (encrypted != STREAM_HEADER_PLAINTEXT_LENGTH + MAC_LENGTH)
				throw new RuntimeException();
		} catch (GeneralSecurityException badCipher) {
			throw new RuntimeException(badCipher);
		}
		out.write(streamHeaderCiphertext);
		writeStreamHeader = false;
	}

	@Override
	public int getMaxPayloadLength() {
		return MAX_PAYLOAD_LENGTH;
	}

	@Override
	public void flush() throws IOException {
		if (writeTag) writeTag();
		if (writeStreamHeader) writeStreamHeader();
		out.flush();
	}
}