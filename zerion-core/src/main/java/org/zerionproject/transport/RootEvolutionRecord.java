package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_CIPHERTEXT_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_ENCAPSULATION_KEY_SIZE;

@Immutable
@NotNullByDefault
final class RootEvolutionRecord {

	static final byte VERSION = 2;

	static final byte KIND_HELLO = 1;
	static final byte KIND_INIT = 2;
	static final byte KIND_RESP = 3;
	static final byte KIND_CONFIRM = 4;
	static final byte KIND_DONE = 5;
	static final byte KIND_IDENTITY_FIRST = 6;
	static final byte KIND_IDENTITY_SECOND = 7;

	static final byte FLAG_WANT_IDENTITY = 0x01;

	static final int X25519_LENGTH = 32;
	static final int FLAGS_LENGTH = 1;
	static final int MAC_LENGTH = 32;
	static final int ML_DSA_PUBLIC_KEY_LENGTH = ML_DSA_65_PUBLIC_KEY_BYTES;
	static final int ED25519_SIGNATURE_LENGTH = 64;
	static final int IDENTITY_LENGTH =
			ML_DSA_PUBLIC_KEY_LENGTH + ED25519_SIGNATURE_LENGTH;
	static final int IDENTITY_PART_LENGTH = IDENTITY_LENGTH / 2;
	private static final int HEADER_LENGTH = 2 + ByteUtils.INT_64_BYTES;

	final byte kind;
	final long epoch;
	final byte[] a;
	final byte[] b;
	final byte[] c;
	final byte flags;

	private RootEvolutionRecord(byte kind, long epoch, byte[] a, byte[] b,
			byte[] c, byte flags) {
		this.kind = kind;
		this.epoch = epoch;
		this.a = a;
		this.b = b;
		this.c = c;
		this.flags = flags;
	}

	static byte[] hello(long epoch, byte[] pendingId, byte flags) {
		return encode(KIND_HELLO, epoch, pendingId, new byte[] {flags});
	}

	static byte[] init(long epoch, byte[] replaces, byte[] x25519,
			byte[] ek) {
		return encode(KIND_INIT, epoch, replaces, x25519, ek);
	}

	static byte[] resp(long epoch, byte[] x25519, byte[] ciphertext,
			byte[] confirm) {
		return encode(KIND_RESP, epoch, x25519, ciphertext, confirm);
	}

	static byte[] confirm(long epoch, byte[] mac) {
		return encode(KIND_CONFIRM, epoch, mac);
	}

	static byte[] done(long epoch, byte[] mac) {
		return encode(KIND_DONE, epoch, mac);
	}

	static byte[][] identity(byte[] mlDsaPublicKey, byte[] signature) {
		if (mlDsaPublicKey.length != ML_DSA_PUBLIC_KEY_LENGTH
				|| signature.length != ED25519_SIGNATURE_LENGTH) {
			throw new IllegalArgumentException();
		}
		byte[] all = new byte[IDENTITY_LENGTH];
		System.arraycopy(mlDsaPublicKey, 0, all, 0, mlDsaPublicKey.length);
		System.arraycopy(signature, 0, all, mlDsaPublicKey.length,
				signature.length);
		return new byte[][] {
				encode(KIND_IDENTITY_FIRST, 0,
						slice(all, 0, IDENTITY_PART_LENGTH)),
				encode(KIND_IDENTITY_SECOND, 0,
						slice(all, IDENTITY_PART_LENGTH, IDENTITY_PART_LENGTH))
		};
	}

	@javax.annotation.Nullable
	static RootEvolutionRecord decode(byte[] payload) throws FormatException {
		if (payload.length < HEADER_LENGTH) throw new FormatException();
		if (payload[0] != VERSION) return null;
		byte kind = payload[1];
		long epoch = ByteUtils.readUint64(payload, 2);
		if (epoch < 0) throw new FormatException();
		int off = HEADER_LENGTH;
		byte[] empty = new byte[0];
		switch (kind) {
			case KIND_HELLO:
				requireLength(payload, off + MAC_LENGTH + FLAGS_LENGTH);
				return new RootEvolutionRecord(kind, epoch,
						slice(payload, off, MAC_LENGTH), empty, empty,
						payload[off + MAC_LENGTH]);
			case KIND_CONFIRM:
			case KIND_DONE:
				requireLength(payload, off + MAC_LENGTH);
				return new RootEvolutionRecord(kind, epoch,
						slice(payload, off, MAC_LENGTH), empty, empty,
						(byte) 0);
			case KIND_INIT:
				requireLength(payload, off + MAC_LENGTH + X25519_LENGTH
						+ MLKEM_ENCAPSULATION_KEY_SIZE);
				return new RootEvolutionRecord(kind, epoch,
						slice(payload, off, MAC_LENGTH),
						slice(payload, off + MAC_LENGTH, X25519_LENGTH),
						slice(payload, off + MAC_LENGTH + X25519_LENGTH,
								MLKEM_ENCAPSULATION_KEY_SIZE), (byte) 0);
			case KIND_RESP:
				requireLength(payload, off + X25519_LENGTH
						+ MLKEM_CIPHERTEXT_SIZE + MAC_LENGTH);
				return new RootEvolutionRecord(kind, epoch,
						slice(payload, off, X25519_LENGTH),
						slice(payload, off + X25519_LENGTH,
								MLKEM_CIPHERTEXT_SIZE),
						slice(payload, off + X25519_LENGTH
								+ MLKEM_CIPHERTEXT_SIZE, MAC_LENGTH), (byte) 0);
			case KIND_IDENTITY_FIRST:
			case KIND_IDENTITY_SECOND:
				requireLength(payload, off + IDENTITY_PART_LENGTH);
				if (epoch != 0) throw new FormatException();
				return new RootEvolutionRecord(kind, epoch,
						slice(payload, off, IDENTITY_PART_LENGTH), empty,
						empty, (byte) 0);
			default:
				throw new FormatException();
		}
	}

	private static byte[] encode(byte kind, long epoch, byte[]... parts) {
		int length = HEADER_LENGTH;
		for (byte[] p : parts) length += p.length;
		byte[] out = new byte[length];
		out[0] = VERSION;
		out[1] = kind;
		ByteUtils.writeUint64(epoch, out, 2);
		int off = HEADER_LENGTH;
		for (byte[] p : parts) {
			System.arraycopy(p, 0, out, off, p.length);
			off += p.length;
		}
		return out;
	}

	private static void requireLength(byte[] payload, int length)
			throws FormatException {
		if (payload.length != length) throw new FormatException();
	}

	private static byte[] slice(byte[] b, int off, int len) {
		return Arrays.copyOfRange(b, off, off + len);
	}
}
