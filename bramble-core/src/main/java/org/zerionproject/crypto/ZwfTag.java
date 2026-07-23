package org.zerionproject.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;

/**
 * Derives the 16-byte stream-recognition tag that prefixes the first frame of a
 * ZWF stream.
 *
 * <p>The tag is {@code MAC(tagKey, streamId)} truncated to {@link
 * org.zerionproject.wire.ZwfConstants#TAG_LENGTH} bytes. Because it is
 * keyed by the per-contact tag key and bound to the monotonic {@code streamId},
 * every stream's tag is distinct and, without the tag key, unlinkable — a
 * network observer cannot tell two streams belong to the same contact, and there
 * is no constant per-contact prefix to fingerprint.
 */
@NotNullByDefault
public final class ZwfTag {

	static final String LABEL = "org.zerionproject/ZWF_STREAM_TAG";

	private ZwfTag() {
	}

	public static byte[] computeTag(CryptoComponent crypto, SecretKey tagKey,
			long streamId) {
		byte[] streamIdBytes = new byte[8];
		ByteUtils.writeUint64(streamId, streamIdBytes, 0);
		byte[] mac = crypto.mac(LABEL, tagKey, streamIdBytes);
		byte[] tag = new byte[TAG_LENGTH];
		System.arraycopy(mac, 0, tag, 0, TAG_LENGTH);
		return tag;
	}
}
