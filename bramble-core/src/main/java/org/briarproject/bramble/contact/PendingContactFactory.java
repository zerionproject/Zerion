package org.briarproject.bramble.contact;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UnsupportedVersionException;
import org.briarproject.bramble.api.contact.PendingContact;
import org.briarproject.bramble.api.crypto.PublicKey;

interface PendingContactFactory {

	/**
	 * Creates a {@link PendingContact} from the given handshake link and alias.
	 *
	 * @throws UnsupportedVersionException If the link uses a format version
	 * that is not supported
	 * @throws FormatException If the link is invalid
	 */
	PendingContact createPendingContact(String link, String alias)
			throws FormatException;

	/**
	 * Creates a handshake link from the given public key.
	 */
	String createHandshakeLink(PublicKey k);

	/**
	 * Verifies that a received hybrid public key matches the commitment
	 * from the handshake link.
	 *
	 * @param receivedKey The full hybrid public key received over Tor
	 * @param commitment The commitment (hash) from the link
	 * @return true if the key matches the commitment
	 */
	boolean verifyHybridKeyCommitment(PublicKey receivedKey, byte[] commitment);
}
