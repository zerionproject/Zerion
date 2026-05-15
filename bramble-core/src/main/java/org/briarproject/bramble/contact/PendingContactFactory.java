package org.briarproject.bramble.contact;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UnsupportedVersionException;
import org.briarproject.bramble.api.contact.PendingContact;
import org.briarproject.bramble.api.crypto.PublicKey;

interface PendingContactFactory {

	PendingContact createPendingContact(String link, String alias)
			throws FormatException;

	String createHandshakeLink(PublicKey k);

	boolean verifyHybridKeyCommitment(PublicKey receivedKey, byte[] commitment);
}
