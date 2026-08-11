package org.zerionproject.message;

import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a record that is too large for one ZWF frame into a sequence of
 * {@link ZmmConstants#TYPE_FRAGMENT} records that each fit, and that
 * {@link ZmmReassembler} joins back together on the far side.
 *
 * <p>A fragment record's payload is
 * {@code [originalType:2][messageId:4][index:2][count:2][chunk]}: the message id
 * ties a message's fragments together, and index/count let the receiver order
 * them and know when the last has arrived. A record that already fits is emitted
 * unchanged, so the common small-message case adds no overhead.
 */
@NotNullByDefault
public final class ZmmFragmenter {

	static final int FRAGMENT_HEADER_LENGTH = 10; // type2 + msgId4 + index2 + count2
	private static final int RECORD_TYPE_LENGTH = 2;
	private static final int MAX_FRAGMENTS = 0xFFFF;

	private ZmmFragmenter() {
	}

	/**
	 * Encodes {@code (type, payload)} as one or more ZMM records that each fit in
	 * {@code maxRecordBytes}. Returns the single plain record if it already fits.
	 */
	public static List<byte[]> fragment(int type, byte[] payload, long messageId,
			int maxRecordBytes) {
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
