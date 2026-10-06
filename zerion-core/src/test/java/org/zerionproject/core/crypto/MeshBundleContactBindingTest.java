package org.zerionproject.core.crypto;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.MeshBundleStore;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class MeshBundleContactBindingTest extends BrambleMockTestCase {

	private static final int ID = 5;

	private final ContactManager contactManager =
			context.mock(ContactManager.class);
	private final InMemorySettings settings = new InMemorySettings();
	private CryptoComponent crypto;
	private KeyPair removedIdentity;
	private KeyPair nextIdentity;

	@Before
	public void setUp() {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		removedIdentity = crypto.generateHybridSignatureKeyPair();
		nextIdentity = crypto.generateHybridSignatureKeyPair();
	}

	private byte[] bundleOf(KeyPair identity) throws Exception {
		KeyPair agree = crypto.generateHybridAgreementKeyPair();
		return AsyncPrekeyBundle.create(crypto,
				identity.getPublic().getEncoded(), identity.getPrivate(),
				agree.getPublic().getEncoded(), 1L,
				crypto.generateHybridAgreementKeyPair().getPublic()
						.getEncoded(), 9999999999L,
				Collections.<AsyncPrekeyBundle.OneTimePrekey>emptyList())
				.encode();
	}

	private static Contact contactWith(KeyPair identity) {
		HybridSignaturePublicKey hybrid = new HybridSignaturePublicKey(
				identity.getPublic().getEncoded());
		Author author = new Author(new AuthorId(getRandomId()), 1, "peer",
				new SignaturePublicKey(hybrid.getEd25519PublicKey()));
		return new Contact(new ContactId(ID), author,
				new AuthorId(getRandomId()), null, null, true, true, false,
				hybrid.getMlDsaPublicKey());
	}

	private void theIdBelongsTo(Contact c) throws Exception {
		context.checking(new Expectations() {{
			allowing(contactManager).getContact(new ContactId(ID));
			will(returnValue(c));
		}});
	}

	@Test
	public void aStoredBundleIsUsedForItsOwnContact() throws Exception {
		MeshBundleStore store = new MeshBundleStore(settings, contactManager);
		theIdBelongsTo(contactWith(removedIdentity));
		store.putContactBundle(ID, bundleOf(removedIdentity));
		assertNotNull(store.getContactBundle(ID, crypto));
	}

	@Test
	public void removingTheContactForgetsItsBundle() throws Exception {
		MeshBundleStore store = new MeshBundleStore(settings, contactManager);
		Contact removed = contactWith(removedIdentity);
		store.putContactBundle(ID, bundleOf(removedIdentity));
		store.removingContact(new Transaction(null, false), removed);
		theIdBelongsTo(contactWith(removedIdentity));
		assertNull(store.getContactBundle(ID, crypto));
	}

	@Test
	public void aBundleLeftUnderAReusedIdIsNotUsedForTheNextContact()
			throws Exception {
		MeshBundleStore store = new MeshBundleStore(settings, contactManager);
		store.putContactBundle(ID, bundleOf(removedIdentity));
		theIdBelongsTo(contactWith(nextIdentity));
		assertNull(store.getContactBundle(ID, crypto));
	}

	@Test
	public void noBundleIsUsedForAnIdWithoutAContact() throws Exception {
		MeshBundleStore store = new MeshBundleStore(settings, contactManager);
		store.putContactBundle(ID, bundleOf(removedIdentity));
		context.checking(new Expectations() {{
			allowing(contactManager).getContact(new ContactId(ID));
			will(throwException(new NoSuchContactException()));
		}});
		assertNull(store.getContactBundle(ID, crypto));
	}

	private static class InMemorySettings implements SettingsManager {

		private final Map<String, Settings> byNamespace = new HashMap<>();

		@Override
		public synchronized Settings getSettings(String namespace) {
			Settings s = new Settings();
			Settings stored = byNamespace.get(namespace);
			if (stored != null) s.putAll(stored);
			return s;
		}

		@Override
		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		@Override
		public synchronized void mergeSettings(Settings s, String namespace) {
			Settings stored = byNamespace.get(namespace);
			if (stored == null) {
				stored = new Settings();
				byNamespace.put(namespace, stored);
			}
			stored.putAll(s);
		}

		@Override
		public void mergeSettings(Transaction txn, Settings s,
				String namespace) {
			mergeSettings(s, namespace);
		}
	}
}
