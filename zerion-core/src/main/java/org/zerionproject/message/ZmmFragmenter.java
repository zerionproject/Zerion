package org.zerionproject.message;

import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

@NotNullByDefault
public final class ZmmFragmenter {

	static final int FRAGMENT_HEADER_LENGTH = 10;
	static final int RECORD_TYPE_LENGTH = 2;
	private static final int MAX_FRAGMENTS = 0xFFFF;

	private ZmmFragmenter() {
	}

	public static List<byte[]> fragment(int type, byte[] payload, long messageId,
			int maxRecordBytes) {
		if (payload.length > ZmmConstants.MAX_RECORD_BYTES) {
			throw new IllegalArgumentException("record too large");
		}
		byte[] plain = ZmmRecord.encode(type, payload);
		List<byte[]> out = new ArrayList<>();
		if (plain.length <= maxRecordBytes) {
			out.add(plain);
			return out;
		}
		int chunkSize = maxRecordBytes - RECORD_TYPE_LENGTH - FRAGMENT_HEADER_LENGTH;
		if (chunkSize <= 0) {
			throw new IllegalArgumentException("maxRecordBytes too small");
		}
		int count = (payload.length + chunkSize - 1) / chunkSize;
		if (count > MAX_FRAGMENTS) {
			throw new IllegalArgumentException("payload too large to fragment");
		}
		for (int index = 0; index < count; index++) {
			int start = index * chunkSize;
			int len = Math.min(chunkSize, payload.length - start);
			byte[] body = new byte[FRAGMENT_HEADER_LENGTH + len];
			ByteUtils.writeUint16(type, body, 0);
			ByteUtils.writeUint32(messageId, body, 2);
			ByteUtils.writeUint16(index, body, 6);
			ByteUtils.writeUint16(count, body, 8);
			System.arraycopy(payload, start, body, FRAGMENT_HEADER_LENGTH, len);
			out.add(ZmmRecord.encode(ZmmConstants.TYPE_FRAGMENT, body));
		}
		return out;
	}
}
