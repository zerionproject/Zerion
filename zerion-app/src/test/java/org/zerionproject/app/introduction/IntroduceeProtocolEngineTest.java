package org.zerionproject.app.introduction;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.zerionproject.app.api.identity.AuthorManager;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.zerionproject.core.api.crypto.CryptoConstants.MAC_BYTES;
import static org.zerionproject.core.api.crypto.CryptoConstants.MAX_SIGNATURE_BYTES;
import static org.zerionproject.core.api.system.Clock.MIN_REASONABLE_TIME_MS;
import static org.zerionproject.core.test.TestUtils.getAgreementPrivateKey;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContactId;
import static org.zerionproject.core.test.TestUtils.getLocalAuthor;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.introduction.IntroduceeState.AWAIT_ACTIVATE;
import static org.zerionproject.app.api.introduction.IntroductionManager.CLIENT_ID;
import static org.zerionproject.app.api.introduction.IntroductionManager.MAJOR_VERSION;
import static org.zerionproject.app.introduction.IntroduceeState.AWAIT_AUTH;
import static org.zerionproject.app.introduction.IntroduceeState.AWAIT_RESPONSES;
import static org.zerionproject.app.introduction.IntroduceeState.LOCAL_DECLINED;
import static org.zerionproject.app.introduction.IntroduceeState.REMOTE_ACCEPTED;
import static org.zerionproject.app.introduction.IntroduceeState.START;
import static org.zerionproject.app.introduction.MessageType.ABORT;
import static org.zerionproject.app.introduction.MessageType.ACCEPT;
import static org.zerionproject.app.introduction.MessageType.ACTIVATE;
import static org.zerionproject.app.introduction.MessageType.DECLINE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class IntroduceeProtocolEngineTest extends BrambleMockTestCase {

	private final SecretKey aliceActivateKey = getSecretKey();
	private final SecretKey bobActivateKey = getSecretKey();

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ContactManager contactManager =
			context.mock(ContactManager.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final MessageTracker messageTracker =
			context.mock(MessageTracker.class);
	private final IdentityManager identityManager =
			context.mock(IdentityManager.class);
	private final AuthorManager authorManager =
			context.mock(AuthorManager.class);
	private final MessageParser messageParser =
			context.mock(MessageParser.class);
	private final MessageEncoder messageEncoder =
			context.mock(MessageEncoder.class);
	private final IntroductionCrypto crypto =
			context.mock(IntroductionCrypto.class);
	private final KeyManager keyManager = context.mock(KeyManager.class);
	private final TransportPropertyManager transportPropertyManager =
			context.mock(TransportPropertyManager.class);
	private final ClientVersioningManager clientVersioningManager =
			context.mock(ClientVersioningManager.class);
	private final AutoDeleteManager autoDeleteManager =
			context.mock(AutoDeleteManager.class);
	private final ConversationManager conversationManager =
			context.mock(ConversationManager.class);
	private final Clock clock = context.mock(Clock.class);

	private final Author introducer = getAuthor();
	private final Author remoteIntroducee = getAuthor();
	private final LocalAuthor localIntroducee = getLocalAuthor();
	private final ContactId contactId = getContactId();
	private final GroupId contactGroupId = new GroupId(getRandomId());
	private final SessionId sessionId = new SessionId(getRandomId());
	private final boolean alice = new Random().nextBoolean();
	private final long now = System.currentTimeMillis();
	private final long requestTimestamp = now - 12345;
	private final long localAcceptTimestamp = requestTimestamp + 123;
	private final long remoteAuthTimestamp = localAcceptTimestamp + 123;
	private final MessageId lastLocalMessageId = new MessageId(getRandomId());
	private final MessageId lastRemoteMessageId = new MessageId(getRandomId());
	private final PublicKey localPublicKey = getAgreementPublicKey();
	private final PrivateKey localPrivateKey = getAgreementPrivateKey();
	private final PublicKey remotePublicKey = getAgreementPublicKey();
	private final SecretKey localMacKey = getSecretKey();
	private final SecretKey remoteMacKey = getSecretKey();
	private final SecretKey masterKey = getSecretKey();
	private final byte[] remoteMac = getRandomBytes(MAC_BYTES);
	private final byte[] localMac = getRandomBytes(MAC_BYTES);
	private final byte[] remoteSignature = getRandomBytes(MAX_SIGNATURE_BYTES);
	private final byte[] localMlKemPriv = getRandomBytes(32);
	private final byte[] ownKemSecret = getRandomBytes(32);
	private final byte[] peerKemSecret = getRandomBytes(32);
	private final byte[] kemCiphertext = getRandomBytes(
			org.zerionproject.app.api.introduction.IntroductionConstants
					.INTRODUCTION_KEM_CIPHERTEXT_BYTES);
	private final SecretKey peerPreMasterKey = getSecretKey();
	private final SecretKey peerMacKey = getSecretKey();
	private final SecretKey finalMasterKey = getSecretKey();

	private final List<MessageId> marked = new ArrayList<>();

	private final IntroduceeProtocolEngine engine =
			new IntroduceeProtocolEngine(db, clientHelper, contactManager,
					contactGroupFactory, messageTracker, identityManager,
					authorManager, messageParser, messageEncoder, crypto,
					keyManager, transportPropertyManager,
					clientVersioningManager, autoDeleteManager,
					conversationManager, clock, marked::add);

	@Test
	public void testDoesNotAbortSessionIfTimestampIsMaxAge() throws Exception {
		Transaction txn = new Transaction(null, false);

		long remoteAcceptTimestamp = MIN_REASONABLE_TIME_MS;
		IntroduceeSession session =
				createAwaitAuthSession(remoteAcceptTimestamp);

		AuthMessage authMessage = new AuthMessage(new MessageId(getRandomId()),
				contactGroupId, remoteAuthTimestamp, lastRemoteMessageId,
				sessionId, remoteMac, remoteSignature, kemCiphertext);

		Message activateMessage = getMessage(contactGroupId, 1234, now);
		BdfDictionary activateMeta = new BdfDictionary();

		context.checking(new Expectations() {{

			oneOf(identityManager).getLocalAuthor(txn);
			will(returnValue(localIntroducee));
			oneOf(crypto).decapsulateMlKem(localMlKemPriv, kemCiphertext);
			will(returnValue(peerKemSecret));
			oneOf(crypto).derivePreMasterKey(session, peerKemSecret);
			will(returnValue(peerPreMasterKey));
			oneOf(crypto).deriveMacKey(peerPreMasterKey, !alice);
			will(returnValue(peerMacKey));
			oneOf(crypto).verifyAuthMacWithKey(remoteMac, session,
					localIntroducee.getId(), peerMacKey);
			oneOf(crypto).verifySignatureWithKey(remoteSignature, session,
					peerMacKey);
			oneOf(crypto).deriveFinalMasterKey(session,
					alice ? ownKemSecret : peerKemSecret,
					alice ? peerKemSecret : ownKemSecret);
			will(returnValue(finalMasterKey));
			oneOf(crypto).deriveActivateKey(finalMasterKey, true);
			will(returnValue(aliceActivateKey));
			oneOf(crypto).deriveActivateKey(finalMasterKey, false);
			will(returnValue(bobActivateKey));

			oneOf(contactManager).addContact(with(txn), with(remoteIntroducee),
					with(localIntroducee.getId()),
					with(any(SecretKey.class)), with(false),
					with(aNull(byte[].class)));
			will(returnValue(contactId));
			oneOf(keyManager).addRotationKeys(with(txn), with(contactId),
					with(any(SecretKey.class)), with(remoteAcceptTimestamp),
					with(alice), with(false));
			will(returnValue(emptyMap()));
			oneOf(transportPropertyManager).addRemoteProperties(txn, contactId,
					emptyMap());

			oneOf(crypto).activateMac(with(any(IntroduceeSession.class)));
			will(returnValue(localMac));
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(messageEncoder).encodeActivateMessage(contactGroupId, now,
					lastLocalMessageId, sessionId, localMac);
			will(returnValue(activateMessage));
			oneOf(messageEncoder).encodeMetadata(ACTIVATE, sessionId, now,
					true, true, false, NO_AUTO_DELETE_TIMER, false);
			will(returnValue(activateMeta));
			oneOf(clientHelper).addLocalMessage(txn, activateMessage,
					activateMeta, true, false);
		}});

		IntroduceeSession after =
				engine.onAuthMessage(txn, session, authMessage);

		assertEquals(AWAIT_ACTIVATE, after.getState());
		assertNull(after.getMasterKey());
		assertEquals(Collections.<TransportId, KeySetId>emptyMap(),
				after.getTransportKeys());

		IntroduceeSession.Local afterLocal = after.getLocal();
		assertEquals(activateMessage.getId(), afterLocal.lastMessageId);
		assertEquals(now, afterLocal.lastMessageTimestamp);
		assertNull(afterLocal.ephemeralPublicKey);
		assertNull(afterLocal.ephemeralPrivateKey);
		assertArrayEquals(alice ? aliceActivateKey.getBytes()
				: bobActivateKey.getBytes(), afterLocal.macKey);

		IntroduceeSession.Remote afterRemote = after.getRemote();
		assertEquals(authMessage.getMessageId(), afterRemote.lastMessageId);
		assertNull(afterRemote.ephemeralPublicKey);
		assertArrayEquals(alice ? bobActivateKey.getBytes()
				: aliceActivateKey.getBytes(), afterRemote.macKey);
	}

	@Test
	public void testAbortsSessionIfTimestampIsTooOld() throws Exception {
		Transaction txn = new Transaction(null, false);

		long remoteAcceptTimestamp = MIN_REASONABLE_TIME_MS - 1;
		IntroduceeSession session =
				createAwaitAuthSession(remoteAcceptTimestamp);

		AuthMessage authMessage = new AuthMessage(new MessageId(getRandomId()),
				contactGroupId, remoteAuthTimestamp, lastRemoteMessageId,
				sessionId, remoteMac, remoteSignature, kemCiphertext);

		BdfDictionary query = new BdfDictionary();
		Message abortMessage = getMessage(contactGroupId, 123, now);
		BdfDictionary abortMeta = new BdfDictionary();

		context.checking(new Expectations() {{

			oneOf(identityManager).getLocalAuthor(txn);
			will(returnValue(localIntroducee));
			oneOf(crypto).decapsulateMlKem(localMlKemPriv, kemCiphertext);
			will(returnValue(peerKemSecret));
			oneOf(crypto).derivePreMasterKey(session, peerKemSecret);
			will(returnValue(peerPreMasterKey));
			oneOf(crypto).deriveMacKey(peerPreMasterKey, !alice);
			will(returnValue(peerMacKey));
			oneOf(crypto).verifyAuthMacWithKey(remoteMac, session,
					localIntroducee.getId(), peerMacKey);
			oneOf(crypto).verifySignatureWithKey(remoteSignature, session,
					peerMacKey);
			oneOf(crypto).deriveFinalMasterKey(session,
					alice ? ownKemSecret : peerKemSecret,
					alice ? peerKemSecret : ownKemSecret);
			will(returnValue(finalMasterKey));
			oneOf(crypto).deriveActivateKey(finalMasterKey, true);
			will(returnValue(aliceActivateKey));
			oneOf(crypto).deriveActivateKey(finalMasterKey, false);
			will(returnValue(bobActivateKey));

			oneOf(messageParser).getRequestsAvailableToAnswerQuery(sessionId);
			will(returnValue(query));
			oneOf(clientHelper).getMessageIds(txn, contactGroupId, query);
			will(returnValue(emptyList()));
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(messageEncoder).encodeAbortMessage(contactGroupId, now,
					lastLocalMessageId, sessionId);
			will(returnValue(abortMessage));
			oneOf(messageEncoder).encodeMetadata(ABORT, sessionId, now,
					true, true, false, NO_AUTO_DELETE_TIMER, false);
			will(returnValue(abortMeta));
			oneOf(clientHelper).addLocalMessage(txn, abortMessage, abortMeta,
					true, false);
		}});

		IntroduceeSession after =
				engine.onAuthMessage(txn, session, authMessage);

		assertEquals(START, after.getState());
		assertNull(after.getMasterKey());
		assertNull(after.getTransportKeys());

		IntroduceeSession.Local afterLocal = after.getLocal();
		assertEquals(abortMessage.getId(), afterLocal.lastMessageId);
		assertEquals(now, afterLocal.lastMessageTimestamp);
		assertNull(afterLocal.ephemeralPublicKey);
		assertNull(afterLocal.ephemeralPrivateKey);
		assertNull(afterLocal.macKey);

		IntroduceeSession.Remote afterRemote = after.getRemote();
		assertEquals(authMessage.getMessageId(), afterRemote.lastMessageId);
		assertNull(afterRemote.ephemeralPublicKey);
		assertNull(afterRemote.macKey);
	}

	@Test
	public void testAuthWithoutKemCiphertextAbortsSession() throws Exception {
		Transaction txn = new Transaction(null, false);
		IntroduceeSession session =
				createAwaitAuthSession(MIN_REASONABLE_TIME_MS);
		AuthMessage classical = new AuthMessage(new MessageId(getRandomId()),
				contactGroupId, remoteAuthTimestamp, lastRemoteMessageId,
				sessionId, remoteMac, remoteSignature);
		BdfDictionary query = new BdfDictionary();
		Message abortMessage = getMessage(contactGroupId, 123, now);
		BdfDictionary abortMeta = new BdfDictionary();
		context.checking(new Expectations() {{
			oneOf(identityManager).getLocalAuthor(txn);
			will(returnValue(localIntroducee));
			oneOf(messageParser).getRequestsAvailableToAnswerQuery(sessionId);
			will(returnValue(query));
			oneOf(clientHelper).getMessageIds(txn, contactGroupId, query);
			will(returnValue(emptyList()));
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(messageEncoder).encodeAbortMessage(contactGroupId, now,
					lastLocalMessageId, sessionId);
			will(returnValue(abortMessage));
			oneOf(messageEncoder).encodeMetadata(ABORT, sessionId, now,
					true, true, false, NO_AUTO_DELETE_TIMER, false);
			will(returnValue(abortMeta));
			oneOf(clientHelper).addLocalMessage(txn, abortMessage, abortMeta,
					true, false);
		}});
		IntroduceeSession after =
				engine.onAuthMessage(txn, session, classical);
		assertEquals(START, after.getState());
		assertNull(after.getMasterKey());
	}

	@Test
	public void aDeclineTheUserChoosesIsMarked() throws Exception {
		Transaction txn = new Transaction(null, false);
		Message decline = getMessage(contactGroupId, 123, now);
		expectAnswerSetUp(txn, 1);
		expectDecline(txn, decline, false);

		IntroduceeSession after = engine.onDeclineAction(txn,
				answerableSession(AWAIT_RESPONSES, null), false);

		assertEquals(LOCAL_DECLINED, after.getState());
		assertEquals(Collections.singletonList(decline.getId()), marked);
	}

	@Test
	public void anAutomaticDeclineIsNotMarked() throws Exception {
		Transaction txn = new Transaction(null, false);
		Message decline = getMessage(contactGroupId, 123, now);
		expectAnswerSetUp(txn, 1);
		expectDecline(txn, decline, true);
		context.checking(new Expectations() {{
			oneOf(authorManager).getAuthorInfo(txn, remoteIntroducee.getId());
			will(returnValue(new AuthorInfo(AuthorInfo.Status.UNKNOWN)));
		}});

		IntroduceeSession after = engine.onDeclineAction(txn,
				answerableSession(AWAIT_RESPONSES, null), true);

		assertEquals(LOCAL_DECLINED, after.getState());
		assertEquals(decline.getId(), after.getLocal().lastMessageId);
		assertEquals(Collections.emptyList(), marked);
	}

	@Test
	public void anAcceptTheUserChoosesIsMarkedButNotTheStepThatFollows()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Message accept = getMessage(contactGroupId, 123, now);
		Message abort = getMessage(contactGroupId, 123, now + 1);
		BdfDictionary acceptMeta = new BdfDictionary();
		BdfDictionary abortMeta = new BdfDictionary();
		byte[] localMlDsa = getRandomBytes(32);
		byte[] localMlKemPub = getRandomBytes(32);
		expectAnswerSetUp(txn, 2);
		context.checking(new Expectations() {{
			oneOf(crypto).generateAgreementKeyPair();
			will(returnValue(new KeyPair(localPublicKey, localPrivateKey)));
			oneOf(crypto).generateMlKemEphemeralKeyPair();
			will(returnValue(new byte[][] {localMlKemPriv, localMlKemPub}));
			oneOf(transportPropertyManager).getLocalProperties(txn);
			will(returnValue(emptyMap()));
			oneOf(identityManager).getLocalMlDsaSigPublicKey(txn);
			will(returnValue(localMlDsa));
			oneOf(messageEncoder).encodeAcceptMessage(contactGroupId, now,
					null, sessionId, localPublicKey, now, emptyMap(),
					NO_AUTO_DELETE_TIMER, localMlDsa, localMlKemPub);
			will(returnValue(accept));
			oneOf(messageEncoder).encodeMetadata(ACCEPT, sessionId, now,
					true, true, true, NO_AUTO_DELETE_TIMER, false);
			will(returnValue(acceptMeta));
			oneOf(clientHelper).addLocalMessage(txn, accept, acceptMeta,
					true, false);
			oneOf(conversationManager).trackOutgoingMessage(txn, accept);
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(messageEncoder).encodeAbortMessage(contactGroupId, now + 1,
					accept.getId(), sessionId);
			will(returnValue(abort));
			oneOf(messageEncoder).encodeMetadata(ABORT, sessionId, now + 1,
					true, true, false, NO_AUTO_DELETE_TIMER, false);
			will(returnValue(abortMeta));
			oneOf(clientHelper).addLocalMessage(txn, abort, abortMeta, true,
					false);
		}});

		IntroduceeSession after = engine.onAcceptAction(txn,
				answerableSession(REMOTE_ACCEPTED, remotePublicKey));

		assertEquals(START, after.getState());
		assertEquals("the step the app adds by itself is the last one sent",
				abort.getId(), after.getLocal().lastMessageId);
		assertEquals(Collections.singletonList(accept.getId()), marked);
	}

	private void expectAnswerSetUp(Transaction txn, int requestScans)
			throws Exception {
		BdfDictionary query = new BdfDictionary();
		context.checking(new Expectations() {{
			exactly(requestScans).of(messageParser)
					.getRequestsAvailableToAnswerQuery(sessionId);
			will(returnValue(query));
			exactly(requestScans).of(clientHelper).getMessageIds(txn,
					contactGroupId, query);
			will(returnValue(emptyList()));
			allowing(clientHelper).getContactId(txn, contactGroupId);
			will(returnValue(contactId));
			oneOf(conversationManager).getTimestampForOutgoingMessage(txn,
					contactId);
			will(returnValue(now));
			oneOf(clientVersioningManager).getClientMinorVersion(txn,
					contactId, CLIENT_ID, MAJOR_VERSION);
			will(returnValue(1));
			oneOf(autoDeleteManager).getAutoDeleteTimer(txn, contactId, now);
			will(returnValue(NO_AUTO_DELETE_TIMER));
		}});
	}

	private void expectDecline(Transaction txn, Message decline,
			boolean automatic) throws Exception {
		BdfDictionary declineMeta = new BdfDictionary();
		context.checking(new Expectations() {{
			oneOf(messageEncoder).encodeDeclineMessage(contactGroupId, now,
					null, sessionId, NO_AUTO_DELETE_TIMER);
			will(returnValue(decline));
			oneOf(messageEncoder).encodeMetadata(DECLINE, sessionId, now,
					true, true, true, NO_AUTO_DELETE_TIMER, automatic);
			will(returnValue(declineMeta));
			oneOf(clientHelper).addLocalMessage(txn, decline, declineMeta,
					true, false);
			oneOf(conversationManager).trackOutgoingMessage(txn, decline);
		}});
	}

	private IntroduceeSession answerableSession(IntroduceeState state,
			@Nullable PublicKey remoteEphemeralKey) {
		IntroduceeSession.Local local = new IntroduceeSession.Local(alice,
				null, -1, null, null, null, -1, null, null);
		IntroduceeSession.Remote remote = new IntroduceeSession.Remote(!alice,
				remoteIntroducee, lastRemoteMessageId, remoteEphemeralKey,
				remoteEphemeralKey == null ? null : emptyMap(),
				remoteEphemeralKey == null ? -1 : requestTimestamp + 1, null,
				null);
		return new IntroduceeSession(sessionId, state, requestTimestamp,
				contactGroupId, introducer, local, remote, null, null);
	}

	private IntroduceeSession createAwaitAuthSession(
			long remoteAcceptTimestamp) {
		IntroduceeSession.Local local = new IntroduceeSession.Local(alice,
				lastLocalMessageId, localAcceptTimestamp, localPublicKey,
				localPrivateKey, emptyMap(), localAcceptTimestamp,
				localMacKey.getBytes(), null, null, localMlKemPriv,
				ownKemSecret);
		IntroduceeSession.Remote remote = new IntroduceeSession.Remote(!alice,
				remoteIntroducee, lastRemoteMessageId, remotePublicKey,
				emptyMap(), remoteAcceptTimestamp, remoteMacKey.getBytes(),
				null);
		return new IntroduceeSession(sessionId,
				AWAIT_AUTH, requestTimestamp, contactGroupId, introducer,
				local, remote, masterKey.getBytes(), emptyMap());
	}
}
