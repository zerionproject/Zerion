package com.professor.zerion.android.mesh;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class MeshPrivateLink {

	static final int KEY_BYTES = 32;
	static final int NONCE_BYTES = 16;
	static final int MAC_BYTES = 16;
	static final byte CONTROL = 0x7F;
	static final byte TYPE_HELLO = 0x02;
	static final byte TYPE_REPLY = 0x03;
	static final byte TYPE_CONFIRM = 0x04;
	static final long EPOCH_MS = 10L * 60L * 1000L;
	static final int EPOCHS_ACCEPTED = 3;
	static final int HELLO_BYTES = 2 + NONCE_BYTES + MAC_BYTES;
	static final int REPLY_BYTES = 2 + NONCE_BYTES + MAC_BYTES;
	static final int CONFIRM_BYTES = 2 + MAC_BYTES;

	static final UUID RANDOM_UUID_BITS =
			new UUID(0x0000000000004000L, 0x8000000000000000L);
	static final UUID RANDOM_UUID_MASK =
			new UUID(0x000000000000F000L, 0xC000000000000000L);

	private static final byte[] ADVERT_LABEL =
			"org.zerionproject/MESH_ADVERT".getBytes(StandardCharsets.UTF_8);
	private static final byte[] LINK_LABEL =
			"org.zerionproject/MESH_LINK".getBytes(StandardCharsets.UTF_8);
	private static final byte[] CHANNEL_LABEL =
			"org.zerionproject/MESH_CHANNEL".getBytes(StandardCharsets.UTF_8);
	private static final byte[] PAIR_LABEL =
			"org.zerionproject/MESH_PAIR".getBytes(StandardCharsets.UTF_8);

	private MeshPrivateLink() {
	}

	static long currentEpoch() {
		return System.currentTimeMillis() / EPOCH_MS;
	}

	static UUID advert(byte[] advertKey, SecureRandom random, long epoch,
			int channel) {
		if (channel < 0 || channel > 0xFF) {
			throw new IllegalArgumentException();
		}
		byte[] r = new byte[8];
		random.nextBytes(r);
		long head = shaped(ByteBuffer.wrap(r).getLong(),
				RANDOM_UUID_MASK.getMostSignificantBits(),
				RANDOM_UUID_BITS.getMostSignificantBits()) & ~0xFFL;
		long msb = head | ((channel ^ channelMask(advertKey, head)) & 0xFFL);
		return new UUID(msb, tagFor(advertKey, msb, epoch));
	}

	static int channelOf(byte[] advertKey, UUID advert) {
		long msb = advert.getMostSignificantBits();
		return (int) ((msb ^ channelMask(advertKey, msb & ~0xFFL)) & 0xFFL);
	}

	private static long channelMask(byte[] advertKey, long head) {
		return mac(advertKey, CHANNEL_LABEL, longBytes(head))[0] & 0xFFL;
	}

	static boolean recognises(byte[] advertKey, UUID seen, long epoch) {
		if (!looksRandom(seen)) return false;
		long msb = seen.getMostSignificantBits();
		byte[] tag = longBytes(seen.getLeastSignificantBits());
		boolean found = false;
		for (long e = epoch - EPOCHS_ACCEPTED; e <= epoch + EPOCHS_ACCEPTED;
				e++) {
			found |= MessageDigest.isEqual(
					longBytes(tagFor(advertKey, msb, e)), tag);
		}
		return found;
	}

	static boolean looksRandom(UUID u) {
		return (u.getMostSignificantBits()
				& RANDOM_UUID_MASK.getMostSignificantBits())
				== RANDOM_UUID_BITS.getMostSignificantBits()
				&& (u.getLeastSignificantBits()
				& RANDOM_UUID_MASK.getLeastSignificantBits())
				== RANDOM_UUID_BITS.getLeastSignificantBits();
	}

	private static long tagFor(byte[] advertKey, long msb, long epoch) {
		byte[] mac = mac(advertKey, ADVERT_LABEL, longBytes(msb),
				longBytes(epoch));
		return shaped(ByteBuffer.wrap(mac).getLong(),
				RANDOM_UUID_MASK.getLeastSignificantBits(),
				RANDOM_UUID_BITS.getLeastSignificantBits());
	}

	private static long shaped(long value, long mask, long bits) {
		return (value & ~mask) | bits;
	}

	static byte[] linkKey(byte[] pairSecretA, byte[] pairSecretB) {
		boolean aFirst = compare(pairSecretA, pairSecretB) <= 0;
		byte[] first = aFirst ? pairSecretA : pairSecretB;
		byte[] second = aFirst ? pairSecretB : pairSecretA;
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(PAIR_LABEL);
			md.update(first);
			md.update(second);
			return md.digest();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	static int compare(byte[] a, byte[] b) {
		int n = Math.min(a.length, b.length);
		for (int i = 0; i < n; i++) {
			int d = (a[i] & 0xFF) - (b[i] & 0xFF);
			if (d != 0) return d;
		}
		return a.length - b.length;
	}

	static byte[] uuidBytes(UUID u) {
		return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits())
				.putLong(u.getLeastSignificantBits()).array();
	}

	static byte[] hello(byte[] linkKey, UUID answererAdvert,
			byte[] dialerNonce) {
		byte[] out = new byte[HELLO_BYTES];
		out[0] = CONTROL;
		out[1] = TYPE_HELLO;
		System.arraycopy(dialerNonce, 0, out, 2, NONCE_BYTES);
		System.arraycopy(helloMac(linkKey, answererAdvert, dialerNonce), 0,
				out, 2 + NONCE_BYTES, MAC_BYTES);
		return out;
	}

	@Nullable
	static byte[] helloNonce(byte[] frame) {
		if (!isType(frame, TYPE_HELLO, HELLO_BYTES)) return null;
		return part(frame, 2, NONCE_BYTES);
	}

	static boolean helloMatches(byte[] frame, byte[] linkKey,
			UUID answererAdvert) {
		byte[] nonce = helloNonce(frame);
		if (nonce == null) return false;
		return MessageDigest.isEqual(
				helloMac(linkKey, answererAdvert, nonce),
				part(frame, 2 + NONCE_BYTES, MAC_BYTES));
	}

	static byte[] reply(byte[] linkKey, byte[] dialerNonce,
			byte[] answererNonce) {
		byte[] out = new byte[REPLY_BYTES];
		out[0] = CONTROL;
		out[1] = TYPE_REPLY;
		System.arraycopy(answererNonce, 0, out, 2, NONCE_BYTES);
		System.arraycopy(sessionMac(linkKey, TYPE_REPLY, dialerNonce,
				answererNonce), 0, out, 2 + NONCE_BYTES, MAC_BYTES);
		return out;
	}

	@Nullable
	static byte[] replyNonce(byte[] frame, byte[] linkKey,
			byte[] dialerNonce) {
		if (!isType(frame, TYPE_REPLY, REPLY_BYTES)) return null;
		byte[] answererNonce = part(frame, 2, NONCE_BYTES);
		if (!MessageDigest.isEqual(sessionMac(linkKey, TYPE_REPLY,
				dialerNonce, answererNonce),
				part(frame, 2 + NONCE_BYTES, MAC_BYTES))) {
			return null;
		}
		return answererNonce;
	}

	static byte[] confirm(byte[] linkKey, byte[] dialerNonce,
			byte[] answererNonce) {
		byte[] out = new byte[CONFIRM_BYTES];
		out[0] = CONTROL;
		out[1] = TYPE_CONFIRM;
		System.arraycopy(sessionMac(linkKey, TYPE_CONFIRM, dialerNonce,
				answererNonce), 0, out, 2, MAC_BYTES);
		return out;
	}

	static boolean confirmMatches(byte[] frame, byte[] linkKey,
			byte[] dialerNonce, byte[] answererNonce) {
		if (!isType(frame, TYPE_CONFIRM, CONFIRM_BYTES)) return false;
		return MessageDigest.isEqual(sessionMac(linkKey, TYPE_CONFIRM,
				dialerNonce, answererNonce), part(frame, 2, MAC_BYTES));
	}

	static boolean isHandshake(byte[] frame) {
		return frame.length >= 2 && frame[0] == CONTROL
				&& (frame[1] == TYPE_HELLO || frame[1] == TYPE_REPLY
				|| frame[1] == TYPE_CONFIRM);
	}

	private static boolean isType(byte[] frame, byte type, int length) {
		return frame.length == length && frame[0] == CONTROL
				&& frame[1] == type;
	}

	private static byte[] helloMac(byte[] linkKey, UUID answererAdvert,
			byte[] dialerNonce) {
		return part(mac(linkKey, LINK_LABEL, new byte[] {TYPE_HELLO},
				uuidBytes(answererAdvert), dialerNonce), 0, MAC_BYTES);
	}

	private static byte[] sessionMac(byte[] linkKey, byte type,
			byte[] dialerNonce, byte[] answererNonce) {
		return part(mac(linkKey, LINK_LABEL, new byte[] {type}, dialerNonce,
				answererNonce), 0, MAC_BYTES);
	}

	private static byte[] mac(byte[] key, byte[]... parts) {
		try {
			Mac m = Mac.getInstance("HmacSHA256");
			m.init(new SecretKeySpec(key, "HmacSHA256"));
			for (byte[] p : parts) m.update(p);
			return m.doFinal();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte[] longBytes(long v) {
		return ByteBuffer.allocate(8).putLong(v).array();
	}

	private static byte[] part(byte[] from, int offset, int length) {
		byte[] out = new byte[length];
		System.arraycopy(from, offset, out, 0, length);
		return out;
	}
}
