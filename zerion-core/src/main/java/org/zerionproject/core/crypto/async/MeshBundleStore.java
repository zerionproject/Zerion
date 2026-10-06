package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.ContactManager.ContactHook;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class MeshBundleStore implements ContactHook {

	private static final String NS = "org.zerionproject.async/contactBundles";

	private final SettingsManager settingsManager;
	@Nullable
	private final ContactManager contactManager;

	public MeshBundleStore(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
		this.contactManager = null;
	}

	public MeshBundleStore(SettingsManager settingsManager,
			ContactManager contactManager) {
		this.settingsManager = settingsManager;
		this.contactManager = contactManager;
	}

	@Override
	public void addingContact(Transaction txn, Contact c) {
	}

	@Override
	public void removingContact(Transaction txn, Contact c)
			throws DbException {
		Settings s = new Settings();
		s.put(key(c.getId().getInt()), "");
		settingsManager.mergeSettings(txn, s, NS);
	}

	public void putContactBundle(int contactId, byte[] encodedBundle)
			throws DbException {
		Settings s = new Settings();
		s.put(key(contactId), StringUtils.toHexString(encodedBundle));
		settingsManager.mergeSettings(s, NS);
	}

	@Nullable
	public AsyncPrekeyBundle getContactBundle(int contactId,
			CryptoComponent crypto) throws DbException {
		String hex = settingsManager.getSettings(NS).get(key(contactId));
		if (hex == null || hex.isEmpty()) return null;
		AsyncPrekeyBundle bundle;
		try {
			bundle = AsyncPrekeyBundle.decode(StringUtils.fromHexString(hex));
			if (!bundle.verify(crypto)) return null;
		} catch (FormatException | RuntimeException e) {
			return null;
		}
		if (contactManager == null) return bundle;
		byte[] identity;
		try {
			identity = identityOf(contactManager.getContact(
					new ContactId(contactId)));
		} catch (NoSuchContactException e) {
			return null;
		}
		if (identity == null || !matchesIdentity(bundle, identity)) {
			return null;
		}
		return bundle;
	}

	@Nullable
	public static byte[] identityOf(Contact c) {
		byte[] mlDsa = c.getMlDsaSigPublicKey();
		if (mlDsa == null) return null;
		return new HybridSignaturePublicKey(
				c.getAuthor().getPublicKey().getEncoded(), mlDsa).getEncoded();
	}

	public static boolean matchesIdentity(AsyncPrekeyBundle bundle,
			byte[] expectedIdentitySigPub) {
		return Arrays.equals(bundle.getIdentitySigPub(),
				expectedIdentitySigPub);
	}

	private static String key(int contactId) {
		return "b." + contactId;
	}
}
