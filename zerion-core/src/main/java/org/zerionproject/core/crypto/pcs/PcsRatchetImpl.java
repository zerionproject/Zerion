package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.CHAIN_KEY_INPUT;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MESSAGE_KEY_INPUT;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_CHAIN_KEY_LABEL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_MESSAGE_KEY_LABEL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_STREAM_CHAIN_LABEL;

@Immutable
@NotNullByDefault
public class PcsRatchetImpl implements PcsRatchet {

	private final CryptoComponent crypto;

	@Inject
	public PcsRatchetImpl(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	@Override
	public SecretKey deriveStreamInitialChainKey(SecretKey rootKey,
			long streamNumber, byte[] salt) {
		byte[] streamBytes = new byte[ByteUtils.INT_64_BYTES];
		ByteUtils.writeUint64(streamNumber, streamBytes, 0);
		return crypto.deriveKey(PCS_STREAM_CHAIN_LABEL, rootKey, streamBytes,
				salt);
	}

	@Override
	public KdfCkResult kdfCk(SecretKey chainKey) {
		SecretKey newChainKey = crypto.deriveKey(PCS_CHAIN_KEY_LABEL, chainKey,
				new byte[] {CHAIN_KEY_INPUT});
		SecretKey messageKey = crypto.deriveKey(PCS_MESSAGE_KEY_LABEL,
				chainKey, new byte[] {MESSAGE_KEY_INPUT});
		return new KdfCkResult(newChainKey, messageKey);
	}
}
