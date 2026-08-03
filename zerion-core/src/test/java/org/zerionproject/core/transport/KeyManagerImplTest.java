package org.zerionproject.core.transport;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.TransportCrypto;
import org.zerionproject.core.crypto.pcs.PcsStateManager;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.simplex.SimplexPluginFactory;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.jmock.lib.concurrent.DeterministicExecutor;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Random;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.zerionproject.core.api.transport.TransportConstants.TAG_LENGTH;
import static org.zerionproject.core.test.TestUtils.getAgreementPrivateKey;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getContactId;
import static org.zerionproject.core.test.TestUtils.getSignaturePublicKey;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class KeyManagerImplTest extends BrambleMockTestCase {

	private DatabaseComponent db;
	private PluginConfig pluginConfig;
	private TransportKeyManagerFactory transportKeyManagerFactory;
	private TransportKeyManager transportKeyManager;
	private TransportCrypto transportCrypto;
	private PcsStateManager pcsStateManager;

	private DeterministicExecutor executor;
	private Transaction txn;
	private ContactId contactId;
	private PendingContactId pendingContactId;
	private KeySetId keySetId;
	private TransportId transportId;
	private TransportId unknownTransportId;
	private StreamContext contactStreamContext;
	private StreamContext pendingContactStreamContext;
	private byte[] tag;
	private PublicKey theirPublicKey;
	private KeyPair ourKeyPair;
	private SecretKey staticMasterKey;
	private SecretKey rootKey;
	private Random random;

	private AuthorId authorId;
	private Author author;
	private Contact contact;

	private PendingContact pendingContact;

	private KeyManagerImpl keyManager;

	@Before
	public void setUp() throws Exception {

		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);

		db = context.mock(DatabaseComponent.class);
		pluginConfig = context.mock(PluginConfig.class);
		transportKeyManagerFactory = context.mock(TransportKeyManagerFactory.class);
		transportKeyManager = context.mock(TransportKeyManager.class);
		transportCrypto = context.mock(TransportCrypto.class);
		pcsStateManager = context.mock(PcsStateManager.class);

		executor = new DeterministicExecutor();
		txn = new Transaction(null, false);
		contactId = getContactId();
		pendingContactId = new PendingContactId(getRandomId());
		keySetId = new KeySetId(345);
		transportId = getTransportId();
		unknownTransportId = getTransportId();
		contactStreamContext = new StreamContext(contactId, null, transportId,
				getSecretKey(), getSecretKey(), 1, false);
		pendingContactStreamContext = new StreamContext(null, pendingContactId,
				transportId, getSecretKey(), getSecretKey(), 1, true);
		tag = getRandomBytes(TAG_LENGTH);
		theirPublicKey = getAgreementPublicKey();
		ourKeyPair = new KeyPair(getAgreementPublicKey(), getAgreementPrivateKey());
		staticMasterKey = getSecretKey();
		rootKey = getSecretKey();
		random = new Random();

		authorId = new AuthorId(getRandomId());
		author = new Author(authorId, 1, "Test Author", getSignaturePublicKey());
		contact = new Contact(contactId, author, authorId,
				null, null, false, false);

		pendingContact = new PendingContact(pendingContactId, theirPublicKey,
				"Pending", System.currentTimeMillis(), 0);

		startService();
	}

	private void startService() throws Exception {
		Transaction txn = new Transaction(null, false);
		SimplexPluginFactory pluginFactory =
				context.mock(SimplexPluginFactory.class);
		Collection<SimplexPluginFactory> factories =
				singletonList(pluginFactory);
		long maxLatency = 1337;

		context.checking(new Expectations() {{
			allowing(pluginConfig).getSimplexFactories();
			will(returnValue(factories));
			allowing(pluginFactory).getId();
			will(returnValue(transportId));
			allowing(pluginFactory).getMaxLatency();
			will(returnValue(maxLatency));
			allowing(pluginConfig).getDuplexFactories();
			will(returnValue(emptyList()));
			oneOf(transportKeyManagerFactory)
					.createTransportKeyManager(transportId, maxLatency);
			will(returnValue(transportKeyManager));
		}});

		keyManager = new KeyManagerImpl(db, executor,
				pluginConfig, transportCrypto, transportKeyManagerFactory,
				pcsStateManager);

		context.checking(new DbExpectations() {{
			oneOf(db).addTransport(txn, transportId, maxLatency);
			oneOf(db).transaction(with(false), withDbRunnable(txn));
			oneOf(transportKeyManager).start(txn);
		}});

		keyManager.startService();
	}

	@Test
	public void testAddContactWithRotationModeKeys() throws Exception {
		SecretKey secretKey = getSecretKey();
		long timestamp = System.currentTimeMillis();
		boolean alice = random.nextBoolean();
		boolean active = random.nextBoolean();

		context.checking(new Expectations() {{
			oneOf(transportKeyManager).addRotationKeys(txn,
					contactId, secretKey, timestamp, alice, active);
			will(returnValue(keySetId));
		}});

		Map<TransportId, KeySetId> ids = keyManager.addRotationKeys(
				txn, contactId, secretKey, timestamp, alice, active);
		assertEquals(singletonMap(transportId, keySetId), ids);
	}

	@Test
	public void testAddContactWithHandshakePublicKey() throws Exception {
		boolean alice = random.nextBoolean();

		context.checking(new Expectations() {{
			oneOf(transportCrypto)
					.deriveStaticMasterKey(theirPublicKey, ourKeyPair);
			will(returnValue(staticMasterKey));
			oneOf(transportCrypto)
					.deriveHandshakeRootKey(staticMasterKey, false);
			will(returnValue(rootKey));
			oneOf(transportCrypto).isAlice(theirPublicKey, ourKeyPair);
			will(returnValue(alice));
			oneOf(transportKeyManager).addHandshakeKeys(txn, contactId,
					rootKey, alice);
			will(returnValue(keySetId));
		}});

		Map<TransportId, KeySetId> ids = keyManager.addContact(txn, contactId,
				theirPublicKey, ourKeyPair);
		assertEquals(singletonMap(transportId, keySetId), ids);
	}

	@Test
	public void testAddPendingContact() throws Exception {
		boolean alice = random.nextBoolean();

		context.checking(new Expectations() {{
			oneOf(transportCrypto)
					.deriveStaticMasterKey(theirPublicKey, ourKeyPair);
			will(returnValue(staticMasterKey));
			oneOf(transportCrypto)
					.deriveHandshakeRootKey(staticMasterKey, true);
			will(returnValue(rootKey));
			oneOf(transportCrypto).isAlice(theirPublicKey, ourKeyPair);
			will(returnValue(alice));
			oneOf(transportKeyManager).addHandshakeKeys(txn, pendingContactId,
					rootKey, alice);
			will(returnValue(keySetId));
		}});

		Map<TransportId, KeySetId> ids = keyManager.addPendingContact(txn,
				pendingContactId, theirPublicKey, ourKeyPair);
		assertEquals(singletonMap(transportId, keySetId), ids);
	}

	@Test
	public void testGetStreamContextForContactWithUnknownTransport()
			throws Exception {
		assertNull(keyManager.getStreamContext(contactId, unknownTransportId));
	}

	@Test
	public void testGetStreamContextForPendingContactWithUnknownTransport()
			throws Exception {
		assertNull(keyManager.getStreamContext(pendingContactId,
				unknownTransportId));
	}

	@Test
	public void testGetStreamContextForContact() throws Exception {
		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(txn));
			oneOf(db).getContact(txn, contactId);
			will(returnValue(contact));
			oneOf(transportKeyManager).getStreamContext(txn, contactId, true);
			will(returnValue(contactStreamContext));
			oneOf(pcsStateManager).hasState(txn, contactId);
			will(returnValue(false));
		}});

		assertEquals(contactStreamContext,
				keyManager.getStreamContext(contactId, transportId));
	}

	@Test
	public void testGetStreamContextForPendingContact() throws Exception {

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(txn));
			oneOf(db).getPendingContact(txn, pendingContactId);
			will(returnValue(pendingContact));
			oneOf(transportKeyManager).getStreamContext(txn, pendingContactId, true);
			will(returnValue(pendingContactStreamContext));
		}});

		assertEquals(pendingContactStreamContext,
				keyManager.getStreamContext(pendingContactId, transportId));
	}

	@Test
	public void testGetStreamContextForTagAndUnknownTransport()
			throws Exception {
		assertNull(keyManager.getStreamContext(unknownTransportId, tag));
	}

	@Test
	public void testGetStreamContextForTag() throws Exception {

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(txn));

			oneOf(transportKeyManager).getStreamContextOnly(txn, tag, false);
			will(returnValue(contactStreamContext));

			oneOf(db).getContact(txn, contactId);
			will(returnValue(contact));

			oneOf(transportKeyManager).getStreamContext(txn, tag, true);
			will(returnValue(contactStreamContext));

			oneOf(pcsStateManager).hasState(txn, contactId);
			will(returnValue(false));
		}});

		assertEquals(contactStreamContext,
				keyManager.getStreamContext(transportId, tag));
	}

	@Test
	public void testContactRemovedEvent() {
		ContactRemovedEvent event = new ContactRemovedEvent(contactId);

		context.checking(new Expectations() {{
			oneOf(transportKeyManager).removeContact(contactId);
		}});

		keyManager.eventOccurred(event);
		executor.runUntilIdle();
	}

	@Test
	public void testAddMultipleRotationKeySets() throws Exception {
		long timestamp = System.currentTimeMillis();
		boolean alice = random.nextBoolean();
		boolean active = random.nextBoolean();

		context.checking(new Expectations() {{
			oneOf(transportKeyManager).addRotationKeys(txn, contactId,
					rootKey, timestamp, alice, active);
			will(returnValue(keySetId));
		}});

		assertEquals(singletonMap(transportId, keySetId),
				keyManager.addRotationKeys(txn, contactId, rootKey, timestamp,
						alice, active));
	}

	@Test
	public void testAddSingleRotationKeySet() throws Exception {
		long timestamp = System.currentTimeMillis();
		boolean alice = random.nextBoolean();
		boolean active = random.nextBoolean();

		context.checking(new Expectations() {{
			oneOf(transportKeyManager).addRotationKeys(txn, contactId,
					rootKey, timestamp, alice, active);
			will(returnValue(keySetId));
		}});

		assertEquals(keySetId, keyManager.addRotationKeys(txn, contactId,
				transportId, rootKey, timestamp, alice, active));
	}
}
