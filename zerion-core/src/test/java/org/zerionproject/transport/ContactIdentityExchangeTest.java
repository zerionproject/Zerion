package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.db.HyperSqlDatabaseForTests;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestMessageFactory;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ContactIdentityExchangeTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();
	private final SecretKey dbKey = getSecretKey();
	private final MessageFactory messageFactory = new TestMessageFactory();
	private DatabaseComponent db;
	private CryptoComponent crypto;
	private ContactId contact;
	private KeyPair peerSigning;
	private byte[] localMlDsa;
	private Identity identity;

	@Before
	public void setUp() throws Exception {
		Class<?> impl = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> cc = impl.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName("org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		crypto = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
		db = HyperSqlDatabaseForTests.open(testDir, dbKey, new NoBus(),
				messageFactory);
		KeyPair localSigning = crypto.generateSignatureKeyPair();
		LocalAuthor localAuthor = new LocalAuthor(new AuthorId(getRandomId()),
				Author.FORMAT_VERSION, "Me", localSigning.getPublic(),
				localSigning.getPrivate());
		Identity template = getIdentity();
		identity = new Identity(localAuthor,
				template.getHandshakePublicKey(),
				template.getHandshakePrivateKey(), template.getTimeCreated());
		peerSigning = crypto.generateSignatureKeyPair();
		localMlDsa = new byte[ML_DSA_65_PUBLIC_KEY_BYTES];
		crypto.getSecureRandom().nextBytes(localMlDsa);
		Author peer = new Author(new AuthorId(getRandomId()),
				Author.FORMAT_VERSION, "Peer", peerSigning.getPublic());
		contact = db.transactionWithResult(false, txn -> {
			db.addIdentity(txn, identity);
			return db.addContact(txn, peer,
					identity.getLocalAuthor().getId(), null, true);
		});
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	private ContactIdentityExchange exchange() {
		IdentityManager identityManager = (IdentityManager)
				Proxy.newProxyInstance(IdentityManager.class.getClassLoader(),
						new Class<?>[] {IdentityManager.class},
						(proxy, method, args) -> {
							switch (method.getName()) {
								case "getLocalMlDsaSigPublicKey":
									return localMlDsa;
								case "getLocalAuthor":
									return identity.getLocalAuthor();
								default:
									return null;
							}
						});
		return new ContactIdentityExchange(db, identityManager, crypto);
	}

	private byte[] peerMlDsa() {
		byte[] k = new byte[ML_DSA_65_PUBLIC_KEY_BYTES];
		crypto.getSecureRandom().nextBytes(k);
		return k;
	}

	@Nullable
	private byte[] storedKey() throws Exception {
		return db.transactionWithNullableResult(true, txn ->
				db.getContact(txn, contact).getMlDsaSigPublicKey());
	}

	@Test
	public void aPeerKeyIsLearnedOnlyUnderAValidSignatureAndNeverReplaced()
			throws Exception {
		ContactIdentityExchange exchange = exchange();
		assertFalse(exchange.knowsPeerKey(contact));
		byte[] key = peerMlDsa();
		byte[] forged = crypto.sign(ContactIdentityExchange.SIGNATURE_LABEL,
				key, crypto.generateSignatureKeyPair().getPrivate());
		exchange.learnPeerKey(contact, key, forged);
		assertNull("a signature by another key records nothing",
				storedKey());
		byte[] wrongLength = new byte[ML_DSA_65_PUBLIC_KEY_BYTES - 1];
		exchange.learnPeerKey(contact, wrongLength, forged);
		assertNull(storedKey());

		byte[] valid = crypto.sign(ContactIdentityExchange.SIGNATURE_LABEL,
				key, peerSigning.getPrivate());
		exchange.learnPeerKey(contact, key, valid);
		assertArrayEquals(key, storedKey());
		assertTrue(exchange.knowsPeerKey(contact));

		byte[] other = peerMlDsa();
		byte[] validOther = crypto.sign(
				ContactIdentityExchange.SIGNATURE_LABEL, other,
				peerSigning.getPrivate());
		exchange.learnPeerKey(contact, other, validOther);
		assertArrayEquals("a known key is never replaced", key, storedKey());
	}

	@Test
	public void ourOwnKeyIsSignedWithOurAuthorKey() throws Exception {
		byte[][] own = exchange().ownKeyAndSignature();
		assertNotNull(own);
		assertArrayEquals(localMlDsa, own[0]);
		LocalAuthor local = identity.getLocalAuthor();
		assertTrue(crypto.verifySignature(own[1],
				ContactIdentityExchange.SIGNATURE_LABEL, own[0],
				local.getPublicKey()));
	}

	private static final class NoBus implements EventBus {
		@Override
		public void addListener(EventListener l) {
		}

		@Override
		public void removeListener(EventListener l) {
		}

		@Override
		public void broadcast(Event e) {
		}
	}

	@java.lang.annotation.Retention(
			java.lang.annotation.RetentionPolicy.SOURCE)
	private @interface Nullable {
	}
}
