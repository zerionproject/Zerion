package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.util.ByteUtils;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ACK_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FULL_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.HELLO_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.HELLO_PROTOCOL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.HYBRID_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KEY_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAC_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.RECORD_HEADER_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.RECORD_VERSION;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SEALED_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SLOTS_BUSY_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SOLUTION_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SOLUTION_X_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_ACK;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_FULL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_HELLO;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_KEY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SEALED;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SLOTS_BUSY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SOLUTION;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.X25519_BYTES;

@NotNullByDefault
final class ContactRequestRecords {

	private ContactRequestRecords() {
	}

	static int sizeOf(int type) {
		switch (type) {
			case TYPE_HELLO:
				return HELLO_BYTES;
			case TYPE_SOLUTION:
				return SOLUTION_BYTES;
			case TYPE_KEY:
				return KEY_BYTES;
			case TYPE_FULL:
				return FULL_BYTES;
			case TYPE_SEALED:
				return SEALED_BYTES;
			case TYPE_ACK:
				return ACK_BYTES;
			case TYPE_SLOTS_BUSY:
				return SLOTS_BUSY_BYTES;
			default:
				return -1;
		}
	}

	@Immutable
	static final class Record {

		final int type;
		final byte[] payload;

		Record(int type, byte[] payload) {
			this.type = type;
			this.payload = payload;
		}
	}

	static Record read(InputStream in, int... allowed) throws IOException {
		byte[] header = new byte[RECORD_HEADER_BYTES];
		readFully(in, header);
		if ((header[0] & 0xFF) != RECORD_VERSION) throw new FormatException();
		int type = header[1] & 0xFF;
		boolean ok = false;
		for (int t : allowed) if (t == type) ok = true;
		if (!ok) throw new FormatException();
		long length = ByteUtils.readUint32(header, 2);
		if (length != sizeOf(type)) throw new FormatException();
		byte[] payload = new byte[(int) length];
		readFully(in, payload);
		return new Record(type, payload);
	}

	static void write(OutputStream out, int type, byte[] payload)
			throws IOException {
		if (payload.length != sizeOf(type)) {
			throw new IllegalArgumentException();
		}
		byte[] record = new byte[RECORD_HEADER_BYTES + payload.length];
		record[0] = (byte) RECORD_VERSION;
		record[1] = (byte) type;
		ByteUtils.writeUint32(payload.length, record, 2);
		System.arraycopy(payload, 0, record, RECORD_HEADER_BYTES,
				payload.length);
		out.write(record);
		out.flush();
	}

	private static void readFully(InputStream in, byte[] b)
			throws IOException {
		int off = 0;
		while (off < b.length) {
			int n = in.read(b, off, b.length - off);
			if (n < 0) throw new EOFException();
			off += n;
		}
	}

	@Immutable
	static final class Hello {

		final int protocol, level, difficulty;
		final long epoch;
		final byte[] nonce;

		Hello(int protocol, int level, int difficulty, long epoch,
				byte[] nonce) {
			this.protocol = protocol;
			this.level = level;
			this.difficulty = difficulty;
			this.epoch = epoch;
			this.nonce = nonce;
		}

		byte[] encode() {
			byte[] b = new byte[HELLO_BYTES];
			b[0] = (byte) protocol;
			b[1] = (byte) level;
			b[2] = (byte) difficulty;
			ByteUtils.writeUint64(epoch, b, 3);
			System.arraycopy(nonce, 0, b, 11, MAC_BYTES);
			return b;
		}

		static Hello decode(byte[] b) throws FormatException {
			if (b.length != HELLO_BYTES) throw new FormatException();
			int protocol = b[0] & 0xFF;
			if (protocol != HELLO_PROTOCOL) throw new FormatException();
			long epoch = ByteUtils.readUint64(b, 3);
			if (epoch < 0) throw new FormatException();
			return new Hello(protocol, b[1] & 0xFF, b[2] & 0xFF, epoch,
					Arrays.copyOfRange(b, 11, 11 + MAC_BYTES));
		}
	}

	@Immutable
	static final class Solution {

		final long epoch;
		final int level;
		final byte[] x;

		Solution(long epoch, int level, byte[] x) {
			if (x.length != SOLUTION_X_BYTES) {
				throw new IllegalArgumentException();
			}
			this.epoch = epoch;
			this.level = level;
			this.x = x;
		}

		byte[] encode() {
			byte[] b = new byte[SOLUTION_BYTES];
			ByteUtils.writeUint64(epoch, b, 0);
			b[8] = (byte) level;
			System.arraycopy(x, 0, b, 9, SOLUTION_X_BYTES);
			return b;
		}

		static Solution decode(byte[] b) throws FormatException {
			if (b.length != SOLUTION_BYTES) throw new FormatException();
			long epoch = ByteUtils.readUint64(b, 0);
			if (epoch < 0) throw new FormatException();
			return new Solution(epoch, b[8] & 0xFF,
					Arrays.copyOfRange(b, 9, SOLUTION_BYTES));
		}
	}

	static byte[] encodeKey(byte[] linkPublicKey, byte[] ephemeral) {
		if (linkPublicKey.length != HYBRID_PUBLIC_KEY_BYTES
				|| ephemeral.length != X25519_BYTES) {
			throw new IllegalArgumentException();
		}
		byte[] b = new byte[KEY_BYTES];
		System.arraycopy(linkPublicKey, 0, b, 0, HYBRID_PUBLIC_KEY_BYTES);
		System.arraycopy(ephemeral, 0, b, HYBRID_PUBLIC_KEY_BYTES,
				X25519_BYTES);
		return b;
	}

	static byte[] keyLinkPublicKey(byte[] key) {
		return Arrays.copyOfRange(key, 0, HYBRID_PUBLIC_KEY_BYTES);
	}

	static byte[] keyEphemeral(byte[] key) {
		return Arrays.copyOfRange(key, HYBRID_PUBLIC_KEY_BYTES, KEY_BYTES);
	}
}
