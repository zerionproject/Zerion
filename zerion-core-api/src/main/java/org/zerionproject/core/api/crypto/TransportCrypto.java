package org.zerionproject.core.api.crypto;

import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.transport.TransportKeys;

import java.security.GeneralSecurityException;

public interface TransportCrypto {

	boolean isAlice(PublicKey theirHandshakePublicKey,
			KeyPair ourHandshakeKeyPair);

	SecretKey deriveStaticMasterKey(PublicKey theirHandshakePublicKey,
			KeyPair ourHandshakeKeyPair) throws GeneralSecurityException;

	SecretKey deriveHandshakeRootKey(SecretKey staticMasterKey,
			boolean pendingContact);

	TransportKeys deriveRotationKeys(TransportId t, SecretKey rootKey,
			long timePeriod, boolean alice, boolean active);

	TransportKeys deriveHandshakeKeys(TransportId t, SecretKey rootKey,
			long timePeriod, boolean alice);

	TransportKeys updateTransportKeys(TransportKeys k, long timePeriod);

	void encodeTag(byte[] tag, SecretKey tagKey, int protocolVersion,
			long streamNumber);
}
