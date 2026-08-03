package org.zerionproject.core.transport.agreement;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.zerionproject.core.api.plugin.simplex.SimplexPluginFactory;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.CaptureArgumentAction;
import org.jmock.Expectations;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Math.min;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.zerionproject.core.api.Bytes.compare;
import static org.zerionproject.core.api.sync.Group.Visibility.VISIBLE;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.DEFER;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.REJECT;
import static org.zerionproject.core.api.transport.agreement.TransportKeyAgreementManager.CLIENT_ID;
import static org.zerionproject.core.api.transport.agreement.TransportKeyAgreementManager.MAJOR_VERSION;
import static org.zerionproject.core.test.TestUtils.getAgreementPrivateKey;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getLocalAuthor;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.zerionproject.core.transport.agreement.MessageType.ACTIVATE;
import static org.zerionproject.core.transport.agreement.MessageType.KEY;
import static org.zerionproject.core.transport.agreement.State.ACTIVATED;
import static org.zerionproject.core.transport.agreement.State.AWAIT_ACTIVATE;
import static org.zerionproject.core.transport.agreement.State.AWAIT_KEY;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.MSG_KEY_MESSAGE_TYPE;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.MSG_KEY_PUBLIC_KEY;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.MSG_KEY_TRANSPORT_ID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TransportKeyAgreementManagerImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final MetadataParser metadataParser =
			context.mock(MetadataParser.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final ClientVersioningManager clientVersioningManager =
			context.mock(ClientVersioningManager.class);
	private final IdentityManager identityManager =
			context.mock(IdentityManager.class);
	private final KeyManager keyManager = context.mock(KeyManager.class);
	private final MessageEncoder messageEncoder =
			context.mock(MessageEncoder.class);
	private final SessionEncoder sessionEncoder =
			context.mock(SessionEncoder.class);
	private final SessionParser sessionParser =
			context.mock(SessionParser.class);
	private final TransportKeyAgreementCrypto crypto =
			context.mock(TransportKeyAgreementCrypto.class);
	private final PluginConfig pluginConfig = context.mock(PluginConfig.class);
	private final SimplexPluginFactory simplexFactory =
			context.mock(SimplexPluginFactory.class);
	private final DuplexPluginFactory duplexFactory =
			context.mock(DuplexPluginFactory.class);

	private final TransportId simplexTransportId = getTransportId();
	private final TransportId duplexTransportId = getTransportId();
	private final Group localGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Contact contact = getContact();
	private final LocalAuthor localAuthor = getLocalAuthor();
	private final boolean alice = compare(localAuthor.getId().getBytes(),
			contact.getAuthor().getId().getBytes()) < 0;
	private final KeyPair localKeyPair =
			new KeyPair(getAgreementPublicKey(), getAgreementPrivateKey());
	private final PublicKey remotePublicKey = getAgreementPublicKey();
	private final SecretKey rootKey = getSecretKey();
	private final KeySetId keySetId = new KeySetId(123);

	private final Message storageMessage = getMessage(contactGroup.getId());
	private final Message localKeyMessage = getMessage(contactGroup.getId());
	private final Message localActivateMessage =
			getMessage(contactGroup.getId());
	private final Message remoteKeyMessage = getMessage(contactGroup.getId());
	private final Message remoteActivateMessage =
			getMessage(contactGroup.getId());
	private final long localTimestamp = localKeyMessage.getTimestamp();
	private final long remoteTimestamp = remoteKeyMessage.getTimestamp();

	private final BdfDictionary sessionQuery = new BdfDictionary();
	private final BdfDictionary sessionMeta = new BdfDictionary();
	private final BdfDictionary localKeyMeta = new BdfDictionary();
	private final BdfDictionary localActivateMeta = new BdfDictionary();

	private final BdfList remoteMessageBody = new BdfList();

	private final BdfDictionary remoteKeyMeta = BdfDictionary.of(
			new BdfEntry(MSG_KEY_MESSAGE_TYPE, KEY.getValue()),
			new BdfEntry(MSG_KEY_TRANSPORT_ID,
					simplexTransportId.getString()),
			new BdfEntry(MSG_KEY_PUBLIC_KEY, remotePublicKey.getEncoded()));

	private final BdfDictionary remoteActivateMeta = BdfDictionary.of(
			new BdfEntry(MSG_KEY_MESSAGE_TYPE, ACTIVATE.getValue()),
			new BdfEntry(MSG_KEY_TRANSPORT_ID,
					simplexTransportId.getString()));

	private TransportKeyAgreementManagerImpl manager;

	@Before
	public void setUp() {
		context.checking(new Expectations() {{
			oneOf(pluginConfig).getSimplexFactories();
			will(returnValue(singletonList(simplexFactory)));
			oneOf(simplexFactory).getId();
			will(returnValue(simplexTransportId));
			oneOf(pluginConfig).getDuplexFactories();
			will(returnValue(singletonList(duplexFactory)));
			oneOf(duplexFactory).getId();
			will(returnValue(duplexTransportId));
			oneOf(contactGroupFactory)
					.createLocalGroup(CLIENT_ID, MAJOR_VERSION);
			will(returnValue(localGroup));
		}});

		manager = new TransportKeyAgreementManagerImpl(db, clientHelper,
				metadataParser, contactGroupFactory, clientVersioningManager,
				identityManager, keyManager, messageEncoder, sessionEncoder,
				sessionParser, crypto, pluginConfig);
	}

	@Test
	public void testCreatesContactGroupAtStartupIfLocalGroupDoesNotExist()
			throws Exception {
		Transaction txn = new Transaction(null, false);

		context.checking(new Expectations() {{
			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));

			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(false));
			oneOf(db).addGroup(txn, localGroup);

			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).addGroup(txn, contactGroup);
			oneOf(clientHelper)
					.setContactId(txn, contactGroup.getId(), contact.getId());
			oneOf(clientVersioningManager).getClientVisibility(txn,
					contact.getId(), CLIENT_ID, MAJOR_VERSION);
			will(returnValue(VISIBLE));
			oneOf(db).setGroupVisibility(txn, contact.getId(),
					contactGroup.getId(), VISIBLE);

			oneOf(db).getTransportsWithKeys(txn);
			will(returnValue(singletonMap(contact.getId(),
					asList(simplexTransportId, duplexTransportId))));
		}});

		manager.onDatabaseOpened(txn);
	}

	@Test
	public void testDoesNotCreateContactGroupAtStartupIfLocalGroupExists()
			throws Exception {
		Transaction txn = new Transaction(null, false);

		context.checking(new Expectations() {{
			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));

			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(true));

			oneOf(db).getTransportsWithKeys(txn);
			will(returnValue(singletonMap(contact.getId(),
					asList(simplexTransportId, duplexTransportId))));
		}});

		manager.onDatabaseOpened(txn);
	}

	@Test
	public void testStartsSessionAtStartup() throws Exception {
		Transaction txn = new Transaction(null, false);

		context.checking(new Expectations() {{
			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));

			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(true));

			oneOf(db).getTransportsWithKeys(txn);
			will(returnValue(singletonMap(contact.getId(),
					singletonList(duplexTransportId))));

			oneOf(contactGroupFactory)
					.createContactGroup(CLIENT_ID, MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
		}});

		expectSessionDoesNotExist(txn);

		expectGenerateLocalKeyPair();

		expectSendKeyMessage(txn);

		expectCreateStorageMessage(txn);
		AtomicReference<Session> savedSession = expectSaveSession(txn);

		manager.onDatabaseOpened(txn);

		assertEquals(AWAIT_KEY, savedSession.get().getState());
		assertEquals(localKeyMessage.getId(),
				savedSession.get().getLastLocalMessageId());
		assertEquals(localKeyPair, savedSession.get().getLocalKeyPair());
		assertEquals(Long.valueOf(localTimestamp),
				savedSession.get().getLocalTimestamp());
		assertNull(savedSession.get().getKeySetId());
	}

	@Test
	public void testDefersMessageIfTransportIsNotSupported() throws Exception {
		Transaction txn = new Transaction(null, false);
		TransportId unknownTransportId = getTransportId();
		BdfDictionary meta = new BdfDictionary(remoteKeyMeta);
		meta.put(MSG_KEY_TRANSPORT_ID, unknownTransportId.getString());

		assertEquals(DEFER, manager.incomingMessage(txn, remoteKeyMessage,
				remoteMessageBody, meta));
	}

	@Test
	public void testAcceptsKeyMessageInAwaitKeyState() throws Exception {
		Transaction txn = new Transaction(null, false);
		Session loadedSession = new Session(AWAIT_KEY,
				localKeyMessage.getId(), localKeyPair, localTimestamp, null);

		expectLoadSession(txn, loadedSession);

		expectLoadContactId(txn);

		expectKeysExist(txn, false);

		expectParseRemotePublicKey();

		expectDeriveAndStoreTransportKeys(txn);

		expectSendActivateMessage(txn);

		AtomicReference<Session> savedSession = expectSaveSession(txn);

		assertEquals(ACCEPT_DO_NOT_SHARE, manager.incomingMessage(txn,
				remoteKeyMessage, remoteMessageBody, remoteKeyMeta));

		assertEquals(AWAIT_ACTIVATE, savedSession.get().getState());
		assertEquals(localActivateMessage.getId(),
				savedSession.get().getLastLocalMessageId());
		assertNull(savedSession.get().getLocalKeyPair());
		assertNull(savedSession.get().getLocalTimestamp());
		assertEquals(keySetId, savedSession.get().getKeySetId());
	}

	@Test
	public void testAcceptsKeyMessageIfWeHaveTransportKeysButNoSession()
			throws Exception {
		Transaction txn = new Transaction(null, false);

		expectSessionDoesNotExist(txn);

		expectLoadContactId(txn);

		expectKeysExist(txn, true);

		expectGenerateLocalKeyPair();

		expectParseRemotePublicKey();

		expectSendKeyMessage(txn);

		expectDeriveAndStoreTransportKeys(txn);

		expectSendActivateMessage(txn);

		expectCreateStorageMessage(txn);
		AtomicReference<Session> savedSession = expectSaveSession(txn);

		assertEquals(ACCEPT_DO_NOT_SHARE, manager.incomingMessage(txn,
				remoteKeyMessage, remoteMessageBody, remoteKeyMeta));

		assertEquals(AWAIT_ACTIVATE, savedSession.get().getState());
		assertEquals(localActivateMessage.getId(),
				savedSession.get().getLastLocalMessageId());
		assertNull(savedSession.get().getLocalKeyPair());
		assertNull(savedSession.get().getLocalTimestamp());
		assertEquals(keySetId, savedSession.get().getKeySetId());
	}

	@Test
	public void testRejectsKeyMessageInAwaitActivateState() throws Exception {
		Session loadedSession = new Session(AWAIT_ACTIVATE,
				localActivateMessage.getId(), null, null, keySetId);
		testRejectsKeyMessageWithExistingSession(loadedSession);
	}

	@Test
	public void testRejectsKeyMessageInActivatedState() throws Exception {
		Session loadedSession = new Session(ACTIVATED,
				localActivateMessage.getId(), null, null, null);
		testRejectsKeyMessageWithExistingSession(loadedSession);
	}

	private void testRejectsKeyMessageWithExistingSession(Session loadedSession)
			throws Exception {
		Transaction txn = new Transaction(null, false);

		expectLoadSession(txn, loadedSession);

		expectLoadContactId(txn);

		expectKeysExist(txn, false);

		assertEquals(REJECT, manager.incomingMessage(txn,
				remoteKeyMessage, remoteMessageBody, remoteKeyMeta));
	}

	@Test
	public void testAcceptsActivateMessageInAwaitActivateState()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Session loadedSession = new Session(AWAIT_ACTIVATE,
				localActivateMessage.getId(), null, null, keySetId);

		expectLoadSession(txn, loadedSession);

		context.checking(new Expectations() {{
			oneOf(keyManager).activateKeys(txn,
					singletonMap(simplexTransportId, keySetId));
		}});

		AtomicReference<Session> savedSession = expectSaveSession(txn);

		assertEquals(ACCEPT_DO_NOT_SHARE, manager.incomingMessage(txn,
				remoteActivateMessage, remoteMessageBody, remoteActivateMeta));

		assertEquals(ACTIVATED, savedSession.get().getState());
		assertEquals(localActivateMessage.getId(),
				savedSession.get().getLastLocalMessageId());
		assertNull(savedSession.get().getLocalKeyPair());
		assertNull(savedSession.get().getLocalTimestamp());
		assertNull(savedSession.get().getKeySetId());
	}

	@Test
	public void testRejectsActivateMessageWithNoSession() throws Exception {
		Transaction txn = new Transaction(null, false);

		expectSessionDoesNotExist(txn);

		assertEquals(REJECT, manager.incomingMessage(txn,
				remoteActivateMessage, remoteMessageBody, remoteActivateMeta));
	}

	@Test
	public void testRejectsActivateMessageInAwaitKeyState() throws Exception {
		Session loadedSession = new Session(AWAIT_KEY,
				localKeyMessage.getId(), localKeyPair, localTimestamp, null);
		testRejectsActivateMessageWithExistingSession(loadedSession);
	}

	@Test
	public void testRejectsActivateMessageInActivatedState() throws Exception {
		Session loadedSession = new Session(ACTIVATED,
				localActivateMessage.getId(), null, null, null);
		testRejectsActivateMessageWithExistingSession(loadedSession);
	}

	private void testRejectsActivateMessageWithExistingSession(
			Session loadedSession) throws Exception {
		Transaction txn = new Transaction(null, false);

		expectLoadSession(txn, loadedSession);

		assertEquals(REJECT, manager.incomingMessage(txn,
				remoteActivateMessage, remoteMessageBody, remoteActivateMeta));
	}

	private void expectSessionDoesNotExist(Transaction txn) throws Exception {
		context.checking(new Expectations() {{
			oneOf(sessionEncoder).getSessionQuery(simplexTransportId);
			will(returnValue(sessionQuery));
			oneOf(clientHelper)
					.getMessageIds(txn, contactGroup.getId(), sessionQuery);
			will(returnValue(emptyList()));
		}});
	}

	private void expectLoadSession(Transaction txn, Session loadedSession)
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(sessionEncoder).getSessionQuery(simplexTransportId);
			will(returnValue(sessionQuery));
			oneOf(clientHelper)
					.getMessageIds(txn, contactGroup.getId(), sessionQuery);
			will(returnValue(singletonList(storageMessage.getId())));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					storageMessage.getId());
			will(returnValue(sessionMeta));
			oneOf(sessionParser).parseSession(sessionMeta);
			will(returnValue(loadedSession));
		}});
	}

	private void expectSendKeyMessage(Transaction txn) throws Exception {
		context.checking(new Expectations() {{
			oneOf(messageEncoder).encodeKeyMessage(contactGroup.getId(),
					simplexTransportId, localKeyPair.getPublic());
			will(returnValue(localKeyMessage));
			oneOf(messageEncoder)
					.encodeMessageMetadata(simplexTransportId, KEY, true);
			will(returnValue(localKeyMeta));
			oneOf(clientHelper).addLocalMessage(txn, localKeyMessage,
					localKeyMeta, true, false);
		}});
	}

	private void expectSendActivateMessage(Transaction txn) throws Exception {
		context.checking(new Expectations() {{
			oneOf(messageEncoder).encodeActivateMessage(contactGroup.getId(),
					simplexTransportId, localKeyMessage.getId());
			will(returnValue(localActivateMessage));
			oneOf(messageEncoder)
					.encodeMessageMetadata(simplexTransportId, ACTIVATE, true);
			will(returnValue(localActivateMeta));
			oneOf(clientHelper).addLocalMessage(txn, localActivateMessage,
					localActivateMeta, true, false);
		}});
	}

	private void expectCreateStorageMessage(Transaction txn) throws Exception {
		context.checking(new Expectations() {{
			oneOf(clientHelper)
					.createMessageForStoringMetadata(contactGroup.getId());
			will(returnValue(storageMessage));
			oneOf(db).addLocalMessage(txn, storageMessage, new Metadata(),
					false, false);
		}});
	}

	private AtomicReference<Session> expectSaveSession(Transaction txn)
			throws Exception {
		AtomicReference<Session> savedSession = new AtomicReference<>();

		context.checking(new Expectations() {{
			oneOf(sessionEncoder).encodeSession(with(any(Session.class)),
					with(simplexTransportId));
			will(doAll(
					new CaptureArgumentAction<>(savedSession, Session.class, 0),
					returnValue(sessionMeta)));
			oneOf(clientHelper).mergeMessageMetadata(txn,
					storageMessage.getId(), sessionMeta);
		}});

		return savedSession;
	}

	private void expectLoadContactId(Transaction txn) throws Exception {
		context.checking(new Expectations() {{
			oneOf(clientHelper).getContactId(txn, contactGroup.getId());
			will(returnValue(contact.getId()));
		}});
	}

	private void expectGenerateLocalKeyPair() {
		context.checking(new Expectations() {{
			oneOf(crypto).generateKeyPair();
			will(returnValue(localKeyPair));
		}});
	}

	private void expectParseRemotePublicKey() throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).parsePublicKey(remotePublicKey.getEncoded());
			will(returnValue(remotePublicKey));
		}});
	}

	private void expectDeriveAndStoreTransportKeys(Transaction txn)
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).deriveRootKey(localKeyPair, remotePublicKey);
			will(returnValue(rootKey));
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			oneOf(identityManager).getLocalAuthor(txn);
			will(returnValue(localAuthor));
			oneOf(keyManager).addRotationKeys(txn, contact.getId(),
					simplexTransportId, rootKey,
					min(localTimestamp, remoteTimestamp), alice, false);
			will(returnValue(keySetId));
		}});
	}

	private void expectKeysExist(Transaction txn, boolean exist)
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(db).containsTransportKeys(txn, contact.getId(),
					simplexTransportId);
			will(returnValue(exist));
		}});
	}
}
