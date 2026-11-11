package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.HandshakeManager.HandshakeResult;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.event.PendingContactStateChangedEvent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordWriter;
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
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.api.contact.B3Constants.B3_KEY_PROOF_LABEL;
import static org.zerionproject.core.api.contact.B3Constants.B3_KEY_PROOF_LABEL_V2;
import static org.zerionproject.core.api.contact.B3Constants.B3_PQ_PUB_LEN;
import static org.zerionproject.core.api.contact.B3Constants.B3_SIG_LEN;
import static org.zerionproject.core.api.contact.ContactType.BRIAR;
import static org.zerionproject.core.api.contact.PendingContactState.OFFLINE;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.contact.ContactExchangeConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.contact.ContactExchangeRecordTypes.CONTACT_INFO;
import static org.zerionproject.core.test.TestDuplexTransportConnection.createPair;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ContactExchangeB3ProofTest extends BrambleTestCase {

	private static final int TIMEOUT = 30_000;

	private enum BobProof {
		V1, V2, V2_OTHER_KEY
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
	private Throwable exchangeAgainst(boolean keySeparated, BobProof proof)
			throws Exception {
		PendingContact bobFromAlice = addPendingContact(alice, bob);
		HandshakeResult aliceResult = new HandshakeResult(
				new SecretKey(exchangeKey.getBytes().clone()),
				new SecretKey(rootKey.getBytes().clone()), keySeparated,
				true, true, aliceStaticHybridPub, bobStaticHybridPub,
				aliceEph, bobEph);
		TestDuplexTransportConnection[] pair = createPair();
		TestDuplexTransportConnection aliceConnection = pair[0];
		TestDuplexTransportConnection bobConnection = pair[1];
		CountDownLatch aliceFinished = new CountDownLatch(1);
		AtomicReference<Throwable> aliceError = new AtomicReference<>();
		alice.getIoExecutor().execute(() -> {
			try {
				alice.getContactExchangeManager().exchangeContacts(
						bobFromAlice.getId(), aliceConnection, aliceResult,
						false, false);
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
		checkAliceProof(keySeparated, clientHelper.toList(
				aliceRecord.getPayload()));

		byte[] signature = exchangeCrypto.sign(author.getPrivateKey(),
				exchangeKey, false);
		byte[] hybrid = exchangeCrypto.hybridSign(author.getPrivateKey(),
				mlDsaPriv, exchangeKey, false);
		byte[] bobProof = bobProof(proof, author, exchangeCrypto);
		BdfList authorList = clientHelper.toList(author);
		BdfList payload = BdfList.of(authorList, new BdfDictionary(),
				signature, System.currentTimeMillis(), bobProof, mlDsaPub,
				hybrid);
		recordWriter.writeRecord(new Record(PROTOCOL_VERSION, CONTACT_INFO,
				clientHelper.toByteArray(payload)));
		recordWriter.flush();
		streamWriter.sendEndOfStream();

		assertTrue(aliceFinished.await(TIMEOUT, MILLISECONDS));
		return aliceError.get();
	}

	private byte[] bobProof(BobProof proof, LocalAuthor author,
			ContactExchangeCrypto exchangeCrypto) {
		byte[] bobPq = java.util.Arrays.copyOfRange(bobStaticHybridPub, 32,
				32 + B3_PQ_PUB_LEN);
		byte[] signingPriv = author.getPrivateKey().getEncoded();
		if (proof == BobProof.V1) {
			return B3PqProof.sign(signingPriv, bobEph, aliceEph, bobPq);
		}
		byte role = B3PqProof.roleFor(bobEph, aliceEph);
		byte[] sessionId = B3PqProof.computeSessionId(bobEph, aliceEph);
		SecretKey macKey = proof == BobProof.V2 ? exchangeKey : otherKey;
		byte[] tag = exchangeCrypto.b3SessionTag(macKey, sessionId);
		return B3PqProof.sign(signingPriv, role, tag, bobPq,
				B3_KEY_PROOF_LABEL_V2);
	}

	private void checkAliceProof(boolean keySeparated, BdfList payload)
			throws Exception {
		assertEquals(7, payload.size());
		byte[] aliceProof = payload.getRaw(4);
		assertEquals(B3_SIG_LEN, aliceProof.length);
		byte[] aliceSigningPub =
				alice.getIdentityManager().getLocalAuthor().getPublicKey()
						.getEncoded();
		byte[] alicePq = java.util.Arrays.copyOfRange(aliceStaticHybridPub,
				32, 32 + B3_PQ_PUB_LEN);
		byte role = B3PqProof.roleFor(aliceEph, bobEph);
		byte[] sessionId = B3PqProof.computeSessionId(aliceEph, bobEph);
		byte[] tag = bob.getContactExchangeCrypto()
				.b3SessionTag(exchangeKey, sessionId);
		boolean v1 = B3PqProof.verify(aliceSigningPub, aliceEph, bobEph,
				alicePq, aliceProof);
		boolean v2 = B3PqProof.verify(aliceSigningPub, role, tag, alicePq,
				aliceProof, B3_KEY_PROOF_LABEL_V2);
		boolean v2PublicSession = B3PqProof.verify(aliceSigningPub, role,
				sessionId, alicePq, aliceProof, B3_KEY_PROOF_LABEL_V2);
		boolean v1OverTag = B3PqProof.verify(aliceSigningPub, role, tag,
				alicePq, aliceProof, B3_KEY_PROOF_LABEL);
		assertEquals("with separated keys the proof signs the session tag",
				keySeparated, v2);
		assertEquals("without separated keys the proof signs the session id",
				!keySeparated, v1);
		assertFalse(v2PublicSession);
		assertFalse(v1OverTag);
	}

	private PendingContact addPendingContact(
			ContactExchangeIntegrationTestComponent local,
			ContactExchangeIntegrationTestComponent remote) throws Exception {
		EventWaiter waiter = new EventWaiter();
		local.getEventBus().addListener(waiter);
		String link = remote.getContactManager().getHandshakeLink(BRIAR);
		String alias = remote.getIdentityManager().getLocalAuthor().getName();
		PendingContact pendingContact =
				local.getContactManager().addPendingContact(link, alias);
		assertTrue(waiter.latch.await(TIMEOUT, MILLISECONDS));
		return pendingContact;
	}

	private void assertAccepted(boolean keySeparated, BobProof proof)
			throws Exception {
		assertNull(exchangeAgainst(keySeparated, proof));
		ContactManager contacts = alice.getContactManager();
		assertEquals(1, contacts.getContacts().size());
		assertEquals(bob.getIdentityManager().getLocalAuthor().getId(),
				contacts.getContacts().iterator().next().getAuthor().getId());
	}

	private void assertRejected(boolean keySeparated, BobProof proof)
			throws Exception {
		Throwable t = exchangeAgainst(keySeparated, proof);
		assertTrue(proof + ": expected a format error, got " + t,
				t instanceof FormatException);
		assertEquals(proof + ": no contact may be stored", 0,
				alice.getContactManager().getContacts().size());
	}

	@Test
	public void separatedKeysAcceptAProofOverTheSessionTag()
			throws Exception {
		assertAccepted(true, BobProof.V2);
	}

	@Test
	public void separatedKeysRejectAProofOverThePublicSessionId()
			throws Exception {
		assertRejected(true, BobProof.V1);
	}

	@Test
	public void separatedKeysRejectATagUnderAnotherKey() throws Exception {
		assertRejected(true, BobProof.V2_OTHER_KEY);
	}

	@Test
	public void earlierPeersKeepTheProofOverThePublicSessionId()
			throws Exception {
		assertAccepted(false, BobProof.V1);
	}

	@Test
	public void earlierPeersRejectAProofOverTheSessionTag()
			throws Exception {
		assertRejected(false, BobProof.V2);
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
