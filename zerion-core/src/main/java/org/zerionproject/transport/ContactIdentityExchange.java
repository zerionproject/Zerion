package org.zerionproject.transport;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;

@ThreadSafe
@Singleton
@NotNullByDefault
public class ContactIdentityExchange
		implements RootEvolutionManager.PeerIdentity {

	static final String SIGNATURE_LABEL =
			"org.zerionproject.transport/CONTACT_ML_DSA_IDENTITY_V1";

	private final DatabaseComponent db;
	private final IdentityManager identityManager;
	private final CryptoComponent crypto;

	@Inject
	public ContactIdentityExchange(DatabaseComponent db,
			IdentityManager identityManager, CryptoComponent crypto) {
		this.db = db;
		this.identityManager = identityManager;
		this.crypto = crypto;
	}

	@Override
	public boolean knowsPeerKey(ContactId c) {
		try {
			return db.transactionWithResult(true, txn ->
					db.getContact(txn, c).getMlDsaSigPublicKey() != null);
		} catch (DbException e) {
			return true;
		}
	}

	@Override
	@Nullable
	public byte[][] ownKeyAndSignature() {
		try {
			return db.transactionWithNullableResult(true, txn -> {
				byte[] mlDsa = identityManager.getLocalMlDsaSigPublicKey(txn);
				if (mlDsa == null || mlDsa.length != ML_DSA_65_PUBLIC_KEY_BYTES) {
					return null;
				}
				LocalAuthor author = identityManager.getLocalAuthor(txn);
				byte[] signature = crypto.sign(SIGNATURE_LABEL, mlDsa,
						author.getPrivateKey());
				return new byte[][] {mlDsa, signature};
			});
		} catch (DbException | GeneralSecurityException e) {
			return null;
		}
	}

	@Override
	public void learnPeerKey(ContactId c, byte[] mlDsaKey, byte[] signature) {
		if (mlDsaKey.length != ML_DSA_65_PUBLIC_KEY_BYTES) return;
		try {
			db.transaction(false, txn -> {
				Contact contact = db.getContact(txn, c);
				if (contact.getMlDsaSigPublicKey() != null) return;
				boolean valid;
				try {
					valid = crypto.verifySignature(signature, SIGNATURE_LABEL,
							mlDsaKey, contact.getAuthor().getPublicKey());
				} catch (GeneralSecurityException e) {
					valid = false;
				}
				if (valid) db.setContactMlDsaSigPublicKey(txn, c, mlDsaKey);
			});
		} catch (DbException | RuntimeException e) {
		}
	}
}
