package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Stores each contact's most recent async prekey bundle in the encrypted
 * settings store (SQLCipher-backed), so an offline mesh message can be sealed to
 * a contact without a live connection. A bundle is stored only after the caller
 * has verified it belongs to that contact (its identity key matches the
 * contact's), and it is re-verified on read as defence in depth.
 */
@ThreadSafe
@NotNullByDefault
public class MeshBundleStore {

	private static final String NS = "org.zerionproject.async/contactBundles";

	private final SettingsManager settingsManager;

	public MeshBundleStore(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	/**
	 * Stores a contact's bundle. The caller must first check the bundle's
	 * signatures and that {@link AsyncPrekeyBundle#getIdentitySigPub()} matches
	 * the contact's known identity, so a contact cannot store a bundle for
	 * another identity.
	 */
	public void putContactBundle(int contactId, byte[] encodedBundle)
			throws DbException {
		Settings s = new Settings();
		s.put(key(contactId), StringUtils.toHexString(encodedBundle));
		settingsManager.mergeSettings(s, NS);
	}

	/** Returns a contact's stored bundle, or null if none is stored or it fails
	 * verification. */
	@Nullable
	public AsyncPrekeyBundle getContactBundle(int contactId,
			CryptoComponent crypto) throws DbException {
		String hex = settingsManager.getSettings(NS).get(key(contactId));
		if (hex == null || hex.isEmpty()) return null;
		try {
			AsyncPrekeyBundle bundle =
					AsyncPrekeyBundle.decode(StringUtils.fromHexString(hex));
			return bundle.verify(crypto) ? bundle : null;
		} catch (FormatException | RuntimeException e) {
			return null;
		}
	}

	/** True if the bundle's identity matches {@code expectedIdentitySigPub}, so
	 * the caller can reject a bundle claiming another contact's identity. */
	public static boolean matchesIdentity(AsyncPrekeyBundle bundle,
			byte[] expectedIdentitySigPub) {
		return Arrays.equals(bundle.getIdentitySigPub(),
				expectedIdentitySigPub);
	}

	private static String key(int contactId) {
		return "b." + contactId;
	}
}
