package org.zerionproject.handshake;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Native plaintext message framing for the Zerion handshake. The handshake runs
 * before any session key exists, so its messages are in the clear; their
 * security comes from the hybrid key
 * commitment and the key agreement, not from encrypting the framing.
 *
 * <p>Wire format of one message: {@code [type:1][length:2 big-endian][payload]}.
 *
 * <p><strong>Denial-of-service note:</strong> the handshake runs over an
 * unauthenticated, attacker-controlled stream, and these read loops have no
 * timeout of their own. The caller MUST set a socket read timeout (SO_TIMEOUT)
 * for the handshake phase, so a peer that dribbles bytes or stalls cannot pin
 * the handshake thread indefinitely.
 */
@NotNullByDefault
class ZwfHandshakeIo {

	static final byte TYPE_STATIC_KEY = 1;
	static final byte TYPE_EPHEMERAL_KEY = 2;
	static final byte TYPE_MINOR_VERSION = 3;
	static final byte TYPE_KEM_CIPHERTEXT = 4;
	static final byte TYPE_PROOF = 5;
	static final byte TYPE_MODE3_CAPABILITY = 6;

	private static final int MAX_MESSAGE_LENGTH = 8192;

	private final InputStream in;
	private final OutputStream out;

	ZwfHandshakeIo(InputStream in, OutputStream out) {
		this.in = in;
		this.out = out;
	}

	void write(byte type, byte[] payload) throws IOException {
		if (payload.length > MAX_MESSAGE_LENGTH)
			throw new IllegalArgumentException("handshake message too long");
		byte[] header = new byte[3];
		header[0] = type;
		ByteUtils.writeUint16(payload.length, header, 1);
		out.write(header);
		out.write(payload);
		out.flush();
	}

	/** Reads the next message, requiring it to be of {@code expectedType}. */
	byte[] read(byte expectedType) throws IOException {
		byte[] header = new byte[3];
		readFully(header);
		byte type = header[0];
		int length = ByteUtils.readUint16(header, 1);
		if (length > MAX_MESSAGE_LENGTH) throw new FormatException();
		byte[] payload = new byte[length];
		readFully(payload);
		if (type != expectedType) throw new FormatException();
		return payload;
	}

	private void readFully(byte[] buf) throws IOException {
		int off = 0;
		while (off < buf.length) {
			int r = in.read(buf, off, buf.length - off);
			if (r == -1) throw new EOFException();
			off += r;
		}
	}
}
