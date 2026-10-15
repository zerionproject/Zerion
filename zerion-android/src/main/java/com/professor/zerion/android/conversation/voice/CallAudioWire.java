package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
final class CallAudioWire {

	static final int SYNC_MARKER = 0x5A455249;
	static final int READY_MARKER = -1;
	static final int HEARTBEAT_MARKER = -2;
	static final int BYE_MARKER = -3;
	static final int FRAME_HEADER_BYTES = 28;
	static final int MAX_SKIPPED_PAYLOAD_BYTES = 1 << 20;

	static final class FrameHeader {

		final long sequence;
		final long checksum;

		private FrameHeader(long sequence, long checksum) {
			this.sequence = sequence;
			this.checksum = checksum;
		}
	}

	private CallAudioWire() {
	}

	static void writePreamble(DataOutputStream out) throws IOException {
		out.writeInt(SYNC_MARKER);
		out.flush();
		out.writeInt(READY_MARKER);
		out.flush();
	}

	static long checksum(byte[] payload) {
		CRC32 crc32 = new CRC32();
		crc32.update(payload, 0, payload.length);
		return crc32.getValue();
	}

	static void writeFrame(DataOutputStream out, long sequence,
			long checksum, byte[] payload) throws IOException {
		out.writeInt(payload.length);
		out.writeLong(sequence);
		out.writeLong(0L);
		out.writeLong(checksum);
		out.write(payload);
		out.flush();
	}

	static void writeMarker(OutputStream stream, int marker)
			throws IOException {
		DataOutputStream out = new DataOutputStream(stream);
		out.writeInt(marker);
		out.flush();
	}

	static boolean skipPayload(DataInputStream in, int length)
			throws IOException {
		if (length > MAX_SKIPPED_PAYLOAD_BYTES) return false;
		int left = length;
		while (left > 0) {
			int skipped = in.skipBytes(left);
			if (skipped > 0) {
				left -= skipped;
				continue;
			}
			if (in.read() < 0) throw new EOFException();
			left--;
		}
		return true;
	}

	static FrameHeader readFrameHeader(DataInputStream in)
			throws IOException {
		long sequence = in.readLong();
		in.readLong();
		long checksum = in.readLong();
		return new FrameHeader(sequence, checksum);
	}
}
