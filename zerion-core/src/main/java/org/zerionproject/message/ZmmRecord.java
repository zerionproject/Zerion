package org.zerionproject.message;

import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * One Zerion Message Model record: a 16-bit type followed by its payload,
 * carried as the payload of a single ZWF frame.
 *
 * <p>Wire layout: {@code [type:2 big-endian][payload]}. Because the whole record
 * sits inside the fixed-size ZWF frame's AEAD, neither the type nor the length
 * is observable on the wire.
 */
@NotNullByDefault
public final class ZmmRecord {

	private static final int TYPE_LENGTH = 2;
	private static final byte[] EMPTY = new byte[0];

	private ZmmRecord() {
	}

	/** Encodes a record: {@code [type][payload]}. */
	public static byte[] encode(int type, byte[] payload) {
		if (type < 0 || type > ZmmConstants.MAX_TYPE)
			throw new IllegalArgumentException("type out of range: " + type);
		byte[] out = new byte[TYPE_LENGTH + payload.length];
		ByteUtils.writeUint16(type, out, 0);
		System.arraycopy(payload, 0, out, TYPE_LENGTH, payload.length);
		return out;
	}

	/** A cover record: cover type, empty payload (padded to frame size by ZWF). */
	public static byte[] cover() {
		return encode(ZmmConstants.TYPE_COVER, EMPTY);
	}

	public static int getType(byte[] record) {
		requireLength(record);
		return ByteUtils.readUint16(record, 0);
	}

	public static boolean isCover(byte[] record) {
		return getType(record) == ZmmConstants.TYPE_COVER;
	}

	public static byte[] getPayload(byte[] record) {
		requireLength(record);
		int len = record.length - TYPE_LENGTH;
		byte[] payload = new byte[len];
		System.arraycopy(record, TYPE_LENGTH, payload, 0, len);
		return payload;
	}

	private static void requireLength(byte[] record) {
		if (record.length < TYPE_LENGTH)
			throw new IllegalArgumentException("record too short");
	}
}
