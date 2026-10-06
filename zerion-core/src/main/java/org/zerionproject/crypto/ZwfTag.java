package org.zerionproject.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;

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
