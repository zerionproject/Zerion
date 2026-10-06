package org.zerionproject.message;

import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class ZmmRecord {

	private static final int TYPE_LENGTH = 2;
	private static final byte[] EMPTY = new byte[0];

	private ZmmRecord() {
	}

	public static byte[] encode(int type, byte[] payload) {
		if (type < 0 || type > ZmmConstants.MAX_TYPE)
			throw new IllegalArgumentException("type out of range: " + type);
		byte[] out = new byte[TYPE_LENGTH + payload.length];
		ByteUtils.writeUint16(type, out, 0);
		System.arraycopy(payload, 0, out, TYPE_LENGTH, payload.length);
		return out;
	}

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
