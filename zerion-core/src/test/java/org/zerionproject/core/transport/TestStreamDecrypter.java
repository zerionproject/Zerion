package org.zerionproject.core.transport;

import org.zerionproject.core.api.crypto.StreamDecrypter;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import static org.zerionproject.core.api.transport.TransportConstants.FRAME_HEADER_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAC_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_FRAME_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.STREAM_HEADER_LENGTH;
import static org.zerionproject.core.util.ByteUtils.INT_16_BYTES;

@NotNullByDefault
class TestStreamDecrypter implements StreamDecrypter {

	private final InputStream in;
	private final byte[] frame;

	private boolean readStreamHeader = true, finalFrame = false;

	TestStreamDecrypter(InputStream in) {
		this.in = in;
		frame = new byte[MAX_FRAME_LENGTH];
	}

	@Override
	public int readFrame(byte[] payload) throws IOException {
		if (finalFrame) return -1;
		if (readStreamHeader) readStreamHeader();
		int offset = 0;
		while (offset < FRAME_HEADER_LENGTH) {
			int read = in.read(frame, offset, FRAME_HEADER_LENGTH - offset);
			if (read == -1) throw new EOFException();
			offset += read;
		}
		finalFrame = (frame[0] & 0x80) == 0x80;
		int payloadLength = ByteUtils.readUint16(frame, 0) & 0x7FFF;
		int paddingLength = ByteUtils.readUint16(frame, INT_16_BYTES);
		int frameLength = FRAME_HEADER_LENGTH + payloadLength + paddingLength
				+ MAC_LENGTH;
		while (offset < frameLength) {
			int read = in.read(frame, offset, frameLength - offset);
			if (read == -1) throw new EOFException();
			offset += read;
		}
		System.arraycopy(frame, FRAME_HEADER_LENGTH, payload, 0, payloadLength);
		return payloadLength;
	}

	private void readStreamHeader() throws IOException {
		byte[] streamHeader = new byte[STREAM_HEADER_LENGTH];
		int offset = 0;
		while (offset < STREAM_HEADER_LENGTH) {
			int read = in.read(streamHeader, offset,
					STREAM_HEADER_LENGTH - offset);
			if (read == -1) throw new EOFException();
			offset += read;
		}
		readStreamHeader = false;
	}
}
