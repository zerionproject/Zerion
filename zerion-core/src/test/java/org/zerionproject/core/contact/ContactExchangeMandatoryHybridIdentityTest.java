package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.contact.ContactExchangeConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.contact.ContactExchangeRecordTypes.CONTACT_INFO;
import static org.zerionproject.core.test.TestDuplexTransportConnection.createPair;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A real contact exchange manager (Alice) faces a scripted peer built from
 * the same production primitives (Bob's identity, header keys, stream and
 * record codecs) that sends a crafted contact info record. The exchange
 * must reject every record that lacks or breaks the ML-DSA half of the
 * identity proof, and accept the fully valid one, so that no peer can fall
 * back to Ed25519-only authentication.
 */
public class ContactExchangeMandatoryHybridIdentityTest
		extends BrambleTestCase {

	private static final int TIMEOUT = 30_000;

	private enum Tamper {
		NONE, NO_ML_DSA_KEY, NO_HYBRID_SIGNATURE, INVALID_ML_DSA_HALF,
		INVALID_ED25519_HALF, INVALID_EXCHANGE_SIGNATURE, WRONG_ROLE_NONCE,
		FOREIGN_ML_DSA_KEY
	}

	private final File testDir = getTestDirectory();
	private final File aliceDir = new File(testDir, "alice");
	private final File bobDir = new File(testDir, "bob");
	private final SecretKey masterKey = getSecretKey();

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

	/**
	 * Runs Alice's real exchange against the scripted Bob and returns what
	 * Alice's exchange threw, or null if it completed.
	 */
	@Nullable
	private Throwable exchangeAgainst(Tamper tamper) throws Exception {
		TestDuplexTransportConnection[] pair = createPair();
		TestDuplexTransportConnection aliceConnection = pair[0];
		TestDuplexTransportConnection bobConnection = pair[1];
		CountDownLatch aliceFinished = new CountDownLatch(1);
		AtomicReference<Throwable> aliceError = new AtomicReference<>();
		alice.getIoExecutor().execute(() -> {
			try {
				alice.getContactExchangeManager().exchangeContacts(
						aliceConnection, masterKey, true, true);
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
						exchangeCrypto.deriveHeaderKey(masterKey, true));
		RecordReader recordReader = bob.getRecordReaderFactory()
				.createRecordReader(streamReader, false);
		StreamWriter streamWriter = bob.getStreamWriterFactory()
				.createContactExchangeStreamWriter(out,
						exchangeCrypto.deriveHeaderKey(masterKey, false));
		RecordWriter recordWriter = bob.getRecordWriterFactory()
				.createRecordWriter(streamWriter.getOutputStream(), false);

		assertNotNull("Alice sends her info first", recordReader.readRecord());

		boolean signingRole = tamper == Tamper.WRONG_ROLE_NONCE;
		byte[] signature = exchangeCrypto.sign(author.getPrivateKey(),
				masterKey, signingRole);
		byte[] hybrid = exchangeCrypto.hybridSign(author.getPrivateKey(),
				mlDsaPriv, masterKey, signingRole);
		byte[] presentedMlDsaPub = mlDsaPub;
		switch (tamper) {
			case INVALID_ML_DSA_HALF:
				hybrid[hybrid.length - 1] ^= 0x01;
				break;
			case INVALID_ED25519_HALF:
				hybrid[3] ^= 0x01;
				break;
			case INVALID_EXCHANGE_SIGNATURE:
				signature[3] ^= 0x01;
				break;
			case FOREIGN_ML_DSA_KEY:
				presentedMlDsaPub = new byte[ML_DSA_65_PUBLIC_KEY_BYTES];
				System.arraycopy(mlDsaPub, 0, presentedMlDsaPub, 0,
						mlDsaPub.length);
				presentedMlDsaPub[7] ^= 0x01;
				break;
			default:
				break;
		}
		BdfList authorList = clientHelper.toList(author);
		long timestamp = System.currentTimeMillis();
		BdfList payload;
		if (tamper == Tamper.NO_ML_DSA_KEY) {
			payload = BdfList.of(authorList, new BdfDictionary(), signature,
					timestamp, new byte[0]);
		} else if (tamper == Tamper.NO_HYBRID_SIGNATURE) {
			payload = BdfList.of(authorList, new BdfDictionary(), signature,
					timestamp, new byte[0], presentedMlDsaPub);
		} else {
			payload = BdfList.of(authorList, new BdfDictionary(), signature,
					timestamp, new byte[0], presentedMlDsaPub, hybrid);
		}
		recordWriter.writeRecord(new Record(PROTOCOL_VERSION, CONTACT_INFO,
				clientHelper.toByteArray(payload)));
		recordWriter.flush();
		streamWriter.sendEndOfStream();

		assertTrue(aliceFinished.await(TIMEOUT, MILLISECONDS));
		return aliceError.get();
	}

	private void assertRejected(Tamper tamper) throws Exception {
		Throwable t = exchangeAgainst(tamper);
		assertTrue(tamper + ": expected a format error, got " + t,
				t instanceof FormatException);
		assertEquals(tamper + ": no contact may be stored", 0,
				alice.getContactManager().getContacts().size());
	}

	@Test
	public void testFullyValidCraftedRecordIsAccepted() throws Exception {
		assertNull(exchangeAgainst(Tamper.NONE));
		ContactManager contacts = alice.getContactManager();
		assertEquals(1, contacts.getContacts().size());
		assertTrue(contacts.getContacts().iterator().next().isPostQuantum());
	}

	@Test
	public void testRecordWithoutMlDsaKeyIsRejected() throws Exception {
		assertRejected(Tamper.NO_ML_DSA_KEY);
	}

	@Test
	public void testRecordWithoutHybridSignatureIsRejected() throws Exception {
		assertRejected(Tamper.NO_HYBRID_SIGNATURE);
	}

	@Test
	public void testValidEd25519WithInvalidMlDsaIsRejected() throws Exception {
		assertRejected(Tamper.INVALID_ML_DSA_HALF);
	}

	@Test
	public void testValidMlDsaWithInvalidEd25519IsRejected() throws Exception {
		assertRejected(Tamper.INVALID_ED25519_HALF);
	}

	@Test
	public void testInvalidExchangeSignatureIsRejected() throws Exception {
		assertRejected(Tamper.INVALID_EXCHANGE_SIGNATURE);
	}

	@Test
	public void testSignatureUnderTheOtherRoleIsRejected() throws Exception {
		assertRejected(Tamper.WRONG_ROLE_NONCE);
	}

	@Test
	public void testForeignMlDsaKeyIsRejected() throws Exception {
		assertRejected(Tamper.FOREIGN_ML_DSA_KEY);
	}
}
