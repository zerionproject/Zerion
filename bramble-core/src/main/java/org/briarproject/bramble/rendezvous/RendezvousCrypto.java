package org.briarproject.bramble.rendezvous;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.rendezvous.KeyMaterialSource;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
interface RendezvousCrypto {

	SecretKey deriveRendezvousKey(SecretKey staticMasterKey);

	/**
	 * Derives a rendezvous key for hybrid (PQ) pending contacts using the
	 * commitment hashes from both parties' links.
	 * <p>
	 * Since hybrid links only contain commitment hashes (not actual public
	 * keys), we cannot perform key agreement before the Tor connection.
	 * Instead, both parties derive the same rendezvous key from their
	 * commitments, which allows them to find each other on the Tor network.
	 * <p>
	 * The actual PQ key exchange happens over the Tor connection after
	 * the rendezvous.
	 *
	 * @param theirCommitment The commitment hash from the remote party's link
	 * @param ourCommitment The commitment hash from our own link
	 * @return A shared rendezvous key derived from both commitments
	 */
	SecretKey deriveHybridRendezvousKey(byte[] theirCommitment,
			byte[] ourCommitment);

	KeyMaterialSource createKeyMaterialSource(SecretKey rendezvousKey,
			TransportId t);
}
