package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.HandshakeManager.HandshakeResult;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.event.PendingContactStateChangedEvent;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.ContactExistsException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordWriter;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.transport.StreamWriter;
import org.zerionproject.core.BrambleCoreIntegrationTestEagerSingletons;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.core.test.TestDuplexTransportConnection;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.api.contact.B3Constants.B3_PQ_PUB_LEN;
import static org.zerionproject.core.api.contact.B3Constants.B3_SETTINGS_NAMESPACE;
import static org.zerionproject.core.api.contact.B3Constants.B3_SIG_LEN;
import static org.zerionproject.core.api.contact.B3Constants.B3_SLOT_PRESENT_KEY_PREFIX;
import static org.zerionproject.core.api.contact.ContactType.BRIAR;
import static org.zerionproject.core.api.contact.ContactType.ZERION;
import static org.zerionproject.core.api.contact.PendingContactState.OFFLINE;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.zerionproject.core.contact.ContactExchangeConstants.EXCHANGE_TAG_LENGTH;
import static org.zerionproject.core.contact.ContactExchangeConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.contact.ContactExchangeRecordTypes.CONTACT_INFO;
import static org.zerionproject.core.test.TestDuplexTransportConnection.createPair;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ContactExchangeB3ProofTest extends BrambleTestCase {

	private static final int TIMEOUT = 30_000;

	private enum BobRecord {
		SIGNATURES, TAG, TAG_OTHER_KEY, TAG_OTHER_ROLE, TAG_OTHER_TIMESTAMP,
		TAG_WITHOUT_STATIC_KEY, TAG_WITH_SIGNATURES
	}

	private enum Pairing {
		LINK, LINK_AGAIN, LINK_RE_ADD, IN_PERSON
	}

	private final File testDir = getTestDirectory();
	private final File aliceDir = new File(testDir, "alice");
	private final File bobDir = new File(testDir, "bob");
	private final SecretKey exchangeKey = getSecretKey();
	private final SecretKey rootKey = getSecretKey();
	private final SecretKey otherKey = getSecretKey();
	private final Random random = new Random();
	private final byte[] aliceStaticHybridPub =
			randomBytes(HYBRID_AGREEMENT_PUBLIC_KEY_BYTES);
	private final byte[] bobStaticHybridPub =
			randomBytes(HYBRID_AGREEMENT_PUBLIC_KEY_BYTES);
	private final byte[] aliceEph = randomBytes(32);
	private final byte[] bobEph = randomBytes(32);

	private ContactExchangeIntegrationTestComponent alice, bob;

	@Before
	public void setUp() throws Exception {
		assertTrue(testDir.mkdirs());
		alice = DaggerContactExchangeIntegrationTestComponent.builder()
				.testDatabaseConfigModule(
						new TestDatabaseConfigModule(aliceDir)).build();
		BrambleCoreIntegrationTestEagerSingletons.Helper
				.injectEagerSingletons(alice);
		bob = DaggerContactExchangeIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(bobDir))
				.build();
		BrambleCoreIntegrationTestEagerSingletons.Helper
				.injectEagerSingletons(bob);
		setUp(alice, "Alice");
		setUp(bob, "Bob");
	}

	private void setUp(ContactExchangeIntegrationTestComponent device,
			String name) throws Exception {
		IdentityManager identityManager = device.getIdentityManager();
		Identity identity = identityManager.createIdentity(name);
		identityManager.registerIdentity(identity);
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.startServices(getSecretKey());
		lifecycleManager.waitForStartup();
	}

	@After
	public void tearDown() throws Exception {
		tearDown(alice);
		tearDown(bob);
		deleteTestDirectory(testDir);
	}

	private void tearDown(ContactExchangeIntegrationTestComponent device)
			throws Exception {
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
	}

	@Nullable
	private Throwable exchangeAgainst(boolean keySeparated, BobRecord form)
			throws Exception {
		return exchangeAgainst(keySeparated, Pairing.LINK, form, rootKey);
	}

	@Nullable
	private Throwable exchangeAgainst(boolean keySeparated, Pairing pairing,
			BobRecord form, SecretKey root) throws Exception {
		boolean statics = pairing != Pairing.IN_PERSON;
		boolean aliceSigns = !keySeparated || pairing == Pairing.LINK_RE_ADD
				|| pairing == Pairing.IN_PERSON;
		PendingContact bobFromAlice = pairing == Pairing.IN_PERSON ? null
				: addPendingContact(alice, bob, pairing == Pairing.LINK_RE_ADD,
				pairing == Pairing.LINK_AGAIN ? ZERION : BRIAR);
		HandshakeResult aliceResult = new HandshakeResult(
				new SecretKey(exchangeKey.getBytes().clone()),
				new SecretKey(root.getBytes().clone()), keySeparated,
				true, true, aliceStaticHybridPub, bobStaticHybridPub,
				aliceEph, bobEph);
		TestDuplexTransportConnection[] pair = createPair();
		TestDuplexTransportConnection aliceConnection = pair[0];
		TestDuplexTransportConnection bobConnection = pair[1];
		CountDownLatch aliceFinished = new CountDownLatch(1);
		AtomicReference<Throwable> aliceError = new AtomicReference<>();
		alice.getIoExecutor().execute(() -> {
			try {
				if (bobFromAlice == null) {
					alice.getContactExchangeManager().exchangeContacts(
							aliceConnection,
							new SecretKey(exchangeKey.getBytes().clone()),
							new SecretKey(root.getBytes().clone()), true,
							true);
				} else {
					alice.getContactExchangeManager().exchangeContacts(
							bobFromAlice.getId(), aliceConnection, aliceResult,
							false, false);
				}
			} catch (Throwable t) {
				aliceError.set(t);
			} finally {
				aliceFinished.countDown();
			}
		});

		ContactExchangeCrypto exchangeCrypto = bob.getContactExchangeCrypto();
		ClientHelper clientHelper = bob.getClientHelper();
		IdentityManager identityManager = bob.getIdentityManager();
		LocalAuthor author = identityManager.getLocalAuthor();
		byte[] mlDsaPub = identityManager.getLocalMlDsaSigPublicKey();
		byte[] mlDsaPriv = identityManager.getLocalMlDsaSigPrivateKey();
		assertNotNull(mlDsaPub);
		assertNotNull(mlDsaPriv);

		InputStream in = bobConnection.getReader().getInputStream();
		OutputStream out = bobConnection.getWriter().getOutputStream();
		InputStream streamReader = bob.getStreamReaderFactory()
				.createContactExchangeStreamReader(in,
						exchangeCrypto.deriveHeaderKey(exchangeKey, true));
		RecordReader recordReader = bob.getRecordReaderFactory()
				.createRecordReader(streamReader, false);
		StreamWriter streamWriter = bob.getStreamWriterFactory()
				.createContactExchangeStreamWriter(out,
						exchangeCrypto.deriveHeaderKey(exchangeKey, false));
		RecordWriter recordWriter = bob.getRecordWriterFactory()
				.createRecordWriter(streamWriter.getOutputStream(), false);

		Record aliceRecord = recordReader.readRecord();
		assertNotNull("Alice sends her info first", aliceRecord);
		assertEquals(CONTACT_INFO, aliceRecord.getRecordType());
		checkAliceRecord(aliceSigns, statics, clientHelper.toList(
				aliceRecord.getPayload()));

		BdfList authorList = clientHelper.toList(author);
		BdfDictionary props = new BdfDictionary();
		long timestamp = System.currentTimeMillis();
		byte[] bobPq = statics ? Arrays.copyOfRange(bobStaticHybridPub, 32,
				32 + B3_PQ_PUB_LEN) : null;
		byte[] sessionId = statics
				? B3PqProof.computeSessionId(bobEph, aliceEph) : null;
		byte[] signingPriv = author.getPrivateKey().getEncoded();
		byte[] slot2;
		byte[] slot4 = new byte[0];
		byte[] slot6 = new byte[0];
		switch (form) {
			case SIGNATURES:
				slot2 = exchangeCrypto.sign(author.getPrivateKey(),
						exchangeKey, false);
				if (statics) {
					slot4 = B3PqProof.sign(signingPriv, bobEph, aliceEph,
							bobPq);
				}
				slot6 = exchangeCrypto.hybridSign(author.getPrivateKey(),
						mlDsaPriv, exchangeKey, false);
				break;
			case TAG_WITH_SIGNATURES:
				slot2 = tag(exchangeCrypto, clientHelper, exchangeKey, false,
						authorList, mlDsaPub, props, timestamp, bobPq,
						sessionId);
				slot4 = B3PqProof.sign(signingPriv, bobEph, aliceEph, bobPq);
				slot6 = exchangeCrypto.hybridSign(author.getPrivateKey(),
						mlDsaPriv, exchangeKey, false);
				break;
			case TAG_OTHER_KEY:
				slot2 = tag(exchangeCrypto, clientHelper, otherKey, false,
						authorList, mlDsaPub, props, timestamp, bobPq,
						sessionId);
				break;
			case TAG_OTHER_ROLE:
				slot2 = tag(exchangeCrypto, clientHelper, exchangeKey, true,
						authorList, mlDsaPub, props, timestamp, bobPq,
						sessionId);
				break;
			case TAG_OTHER_TIMESTAMP:
				slot2 = tag(exchangeCrypto, clientHelper, exchangeKey, false,
						authorList, mlDsaPub, props, timestamp + 1, bobPq,
						sessionId);
				break;
			case TAG_WITHOUT_STATIC_KEY:
				slot2 = tag(exchangeCrypto, clientHelper, exchangeKey, false,
						authorList, mlDsaPub, props, timestamp, null, null);
				break;
			default:
				slot2 = tag(exchangeCrypto, clientHelper, exchangeKey, false,
						authorList, mlDsaPub, props, timestamp, bobPq,
						sessionId);
				break;
		}
		BdfList payload = BdfList.of(authorList, props, slot2, timestamp,
				slot4, mlDsaPub, slot6);
		recordWriter.writeRecord(new Record(PROTOCOL_VERSION, CONTACT_INFO,
				clientHelper.toByteArray(payload)));
		recordWriter.flush();
		streamWriter.sendEndOfStream();

		assertTrue(aliceFinished.await(TIMEOUT, MILLISECONDS));
		return aliceError.get();
	}

	private static byte[] tag(ContactExchangeCrypto exchangeCrypto,
			ClientHelper clientHelper, SecretKey key, boolean alice,
			BdfList authorList, byte[] mlDsaPub, BdfDictionary props,
			long timestamp, @Nullable byte[] staticPqPub,
			@Nullable byte[] sessionId) throws FormatException {
		return exchangeCrypto.exchangeTag(key, alice, new ExchangeTranscript(
				clientHelper.toByteArray(authorList), mlDsaPub,
				clientHelper.toByteArray(props), timestamp, staticPqPub,
				sessionId));
	}

	private void checkAliceRecord(boolean signed, boolean statics,
			BdfList payload) throws Exception {
		assertEquals(7, payload.size());
		ClientHelper clientHelper = bob.getClientHelper();
		ContactExchangeCrypto exchangeCrypto = bob.getContactExchangeCrypto();
		BdfList authorList = payload.getList(0);
		BdfDictionary props = payload.getDictionary(1);
		byte[] slot2 = payload.getRaw(2);
		long timestamp = payload.getLong(3);
		byte[] slot4 = payload.getRaw(4);
		byte[] mlDsaPub = payload.getRaw(5);
		byte[] slot6 = payload.getRaw(6);
		PublicKey aliceSigningPub =
				alice.getIdentityManager().getLocalAuthor().getPublicKey();
		assertArrayEquals(alice.getIdentityManager()
				.getLocalMlDsaSigPublicKey(), mlDsaPub);
		byte[] alicePq = Arrays.copyOfRange(aliceStaticHybridPub, 32,
				32 + B3_PQ_PUB_LEN);
		byte[] sessionId = B3PqProof.computeSessionId(aliceEph, bobEph);
		if (!signed) {
			assertEquals(EXCHANGE_TAG_LENGTH, slot2.length);
			assertEquals("no B.3 proof with a tag", 0, slot4.length);
			assertEquals("no hybrid signature with a tag", 0, slot6.length);
			byte[] recomputed = tag(exchangeCrypto, clientHelper, exchangeKey,
					true, authorList, mlDsaPub, props, timestamp, alicePq,
					sessionId);
			assertArrayEquals("the receiving side computes the same tag",
					recomputed, slot2);
			assertFalse("another key gives another tag", Arrays.equals(
					tag(exchangeCrypto, clientHelper, otherKey, true,
							authorList, mlDsaPub, props, timestamp, alicePq,
							sessionId), slot2));
			assertFalse("the tag is no signature by the identity key",
					exchangeCrypto.verify(aliceSigningPub, exchangeKey, true,
							slot2));
		} else {
			assertEquals(B3_SIG_LEN, slot2.length);
			assertTrue(exchangeCrypto.verify(aliceSigningPub, exchangeKey,
					true, slot2));
			if (statics) {
				assertEquals(B3_SIG_LEN, slot4.length);
				assertTrue(B3PqProof.verify(aliceSigningPub.getEncoded(),
						aliceEph, bobEph, alicePq, slot4));
			} else {
				assertEquals(0, slot4.length);
			}
			assertEquals(HYBRID_SIGNATURE_BYTES, slot6.length);
			assertTrue(exchangeCrypto.verifyHybrid(aliceSigningPub, mlDsaPub,
					exchangeKey, true, slot6));
		}
	}

	private PendingContact addPendingContact(
			ContactExchangeIntegrationTestComponent local,
			ContactExchangeIntegrationTestComponent remote, boolean reAdd)
			throws Exception {
		return addPendingContact(local, remote, reAdd, BRIAR);
	}

	private PendingContact addPendingContact(
			ContactExchangeIntegrationTestComponent local,
			ContactExchangeIntegrationTestComponent remote, boolean reAdd,
			org.zerionproject.core.api.contact.ContactType type)
			throws Exception {
		EventWaiter waiter = new EventWaiter();
		local.getEventBus().addListener(waiter);
		String link = remote.getContactManager().getHandshakeLink(type);
		String alias = remote.getIdentityManager().getLocalAuthor().getName();
		PendingContact pendingContact = reAdd
				? local.getContactManager().addPendingContact(link, alias,
				null, true, false)
				: local.getContactManager().addPendingContact(link, alias);
		assertTrue(waiter.latch.await(TIMEOUT, MILLISECONDS));
		return pendingContact;
	}

	private Contact assertAccepted(boolean keySeparated, BobRecord form)
			throws Exception {
		return assertAccepted(keySeparated, Pairing.LINK, form, rootKey);
	}

	private Contact assertAccepted(boolean keySeparated, Pairing pairing,
			BobRecord form, SecretKey root) throws Exception {
		assertNull(exchangeAgainst(keySeparated, pairing, form, root));
		ContactManager contacts = alice.getContactManager();
		assertEquals(1, contacts.getContacts().size());
		Contact contact = contacts.getContacts().iterator().next();
		assertEquals(bob.getIdentityManager().getLocalAuthor().getId(),
				contact.getAuthor().getId());
		assertArrayEquals(bob.getIdentityManager()
				.getLocalMlDsaSigPublicKey(), contact.getMlDsaSigPublicKey());
		Settings b3 = alice.getSettingsManager()
				.getSettings(B3_SETTINGS_NAMESPACE);
		assertEquals("the static ML-KEM key was bound in this exchange",
				pairing == Pairing.IN_PERSON ? "0" : "1",
				b3.get(B3_SLOT_PRESENT_KEY_PREFIX + contact.getId().getInt()));
		assertStoredRoot(root);
		return contact;
	}

	private void assertStoredRoot(SecretKey root) throws Exception {
		String label = "org.zerionproject.test/STORED_ROOT_PROBE";
		Contact c = alice.getContactManager().getContacts().iterator().next();
		SecretKey probe = alice.getContactManager().deriveContactKey(
				c.getId(), label);
		assertArrayEquals(alice.getCryptoComponent().deriveKey(label, root)
				.getBytes(), probe.getBytes());
	}

	private void assertRejected(boolean keySeparated, BobRecord form)
			throws Exception {
		Throwable t = exchangeAgainst(keySeparated, form);
		assertTrue(form + ": expected a format error, got " + t,
				t instanceof FormatException);
		assertEquals(form + ": no contact may be stored", 0,
				alice.getContactManager().getContacts().size());
	}

	@Test
	public void separatedKeysAcceptATagUnderTheExchangeKey()
			throws Exception {
		assertAccepted(true, BobRecord.TAG);
	}

	@Test
	public void separatedKeysAcceptTheSignedFormForANewContact()
			throws Exception {
		assertAccepted(true, BobRecord.SIGNATURES);
	}

	@Test
	public void separatedKeysRejectATagUnderAnotherKey() throws Exception {
		assertRejected(true, BobRecord.TAG_OTHER_KEY);
	}

	@Test
	public void separatedKeysRejectATagUnderTheOtherRole() throws Exception {
		assertRejected(true, BobRecord.TAG_OTHER_ROLE);
	}

	@Test
	public void separatedKeysRejectATagOverAnotherTimestamp()
			throws Exception {
		assertRejected(true, BobRecord.TAG_OTHER_TIMESTAMP);
	}

	@Test
	public void separatedKeysRejectATagThatLeavesOutTheStaticKey()
			throws Exception {
		assertRejected(true, BobRecord.TAG_WITHOUT_STATIC_KEY);
	}

	@Test
	public void separatedKeysRejectATagNextToSignatures() throws Exception {
		assertRejected(true, BobRecord.TAG_WITH_SIGNATURES);
	}

	@Test
	public void earlierPeersKeepTheThreeSignatures() throws Exception {
		assertAccepted(false, BobRecord.SIGNATURES);
	}

	@Test
	public void earlierPeersRejectATag() throws Exception {
		assertRejected(false, BobRecord.TAG);
	}

	@Test
	public void reAddingSendsTheSignedFormAndReplacesTheKeys()
			throws Exception {
		assertAccepted(true, BobRecord.TAG);
		SecretKey newRoot = getSecretKey();
		assertAccepted(true, Pairing.LINK_RE_ADD, BobRecord.SIGNATURES,
				newRoot);
	}

	@Test
	public void reAddingRefusesATagForTheStoredAuthor() throws Exception {
		assertAccepted(true, BobRecord.TAG);
		Throwable t = exchangeAgainst(true, Pairing.LINK_RE_ADD,
				BobRecord.TAG, getSecretKey());
		assertTrue("expected a format error, got " + t,
				t instanceof FormatException);
		assertEquals(1, alice.getContactManager().getContacts().size());
		assertEquals("the pending contact is removed", 0,
				alice.getContactManager().getPendingContacts().size());
		assertStoredRoot(rootKey);
	}

	@Test
	public void addingAStoredAuthorAgainWithATagChangesNoKeys()
			throws Exception {
		assertAccepted(true, BobRecord.TAG);
		Throwable t = exchangeAgainst(true, Pairing.LINK_AGAIN, BobRecord.TAG,
				getSecretKey());
		assertTrue("expected already a contact, got " + t,
				t instanceof ContactExistsException);
		assertEquals(1, alice.getContactManager().getContacts().size());
		assertEquals(0,
				alice.getContactManager().getPendingContacts().size());
		assertStoredRoot(rootKey);
	}

	@Test
	public void inPersonPairingSendsTheSignedForm() throws Exception {
		assertAccepted(true, Pairing.IN_PERSON, BobRecord.SIGNATURES,
				rootKey);
		assertTrue(alice.getContactManager().getContacts().iterator().next()
				.isVerified());
	}

	private byte[] randomBytes(int len) {
		byte[] b = new byte[len];
		random.nextBytes(b);
		return b;
	}

	@NotNullByDefault
	private static class EventWaiter implements EventListener {

		private final CountDownLatch latch = new CountDownLatch(1);

		@Override
		public void eventOccurred(Event e) {
			if (e instanceof PendingContactStateChangedEvent) {
				PendingContactStateChangedEvent p =
						(PendingContactStateChangedEvent) e;
				if (p.getPendingContactState() == OFFLINE) latch.countDown();
			}
		}
	}
}
