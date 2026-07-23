package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.UnsupportedVersionException;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.crypto.PublicKey;

interface PendingContactFactory {

	PendingContact createPendingContact(String link, String alias)
			throws FormatException;

	String createHandshakeLink(PublicKey k);

	boolean verifyHybridKeyCommitment(PublicKey receivedKey, byte[] commitment);
}
