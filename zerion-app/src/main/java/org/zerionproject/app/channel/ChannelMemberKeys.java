package org.zerionproject.app.channel;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

@NotNullByDefault
final class ChannelMemberKeys {

	private static final String LABEL_SEED =
			"org.zerionproject/CHANNEL_MEMBER_KEY";
	private static final String LABEL_ED25519 =
			"org.zerionproject/CHANNEL_MEMBER_KEY_ED25519";
	private static final String LABEL_ML_DSA =
			"org.zerionproject/CHANNEL_MEMBER_KEY_ML_DSA";

	private ChannelMemberKeys() {
	}

	static KeyPair derive(CryptoComponent crypto, byte[] identityPrivateKey,
			byte[] channelId) {
		SecretKey root = crypto.deriveKey(LABEL_SEED,
				new SecretKey(identityPrivateKey), channelId);
		byte[] rootBytes = root.getBytes();
		byte[] edSeed = Arrays.copyOf(crypto.hash(LABEL_ED25519, rootBytes),
				32);
		byte[] mlSeed = Arrays.copyOf(crypto.hash(LABEL_ML_DSA, rootBytes),
				32);
		Arrays.fill(rootBytes, (byte) 0);
		try {
			Ed25519PrivateKeyParameters ed =
					new Ed25519PrivateKeyParameters(edSeed, 0);
			MLDSAPrivateKeyParameters ml = new MLDSAPrivateKeyParameters(
					MLDSAParameters.ml_dsa_65, mlSeed);
			byte[] mlPrivate = ml.getParametersWithFormat(
					MLDSAPrivateKeyParameters.EXPANDED_KEY).getEncoded();
			HybridSignaturePublicKey pub = new HybridSignaturePublicKey(
					ed.generatePublicKey().getEncoded(), ml.getPublicKey());
			HybridSignaturePrivateKey priv = new HybridSignaturePrivateKey(
					ed.getEncoded(), mlPrivate);
			return new KeyPair(pub, priv);
		} finally {
			Arrays.fill(edSeed, (byte) 0);
			Arrays.fill(mlSeed, (byte) 0);
		}
	}
}
