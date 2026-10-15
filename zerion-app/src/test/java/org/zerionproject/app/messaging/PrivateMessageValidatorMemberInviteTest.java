package org.zerionproject.app.messaging;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_GRANTED_INVITE_OFFER;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_INVITE_ACCEPT_INCOGNITO;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_JOIN_REQUEST;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_MEMBER_INVITE_DECLINE;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_MEMBER_INVITE_OFFER;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_INVITER_GRANT;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_PERMISSIONS;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.util.StringUtils.getRandomString;

public class PrivateMessageValidatorMemberInviteTest
		extends BrambleMockTestCase {

	private final BdfReaderFactory bdfReaderFactory =
			context.mock(BdfReaderFactory.class);
	private final MetadataEncoder metadataEncoder =
			context.mock(MetadataEncoder.class);
	private final Clock clock = context.mock(Clock.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final BdfReader reader = context.mock(BdfReader.class);

	private final Group group = getGroup(getClientId(), 123);
	private final Message message = getMessage(group.getId());
	private final long now = message.getTimestamp() + 1000;
	private final byte[] id = getRandomBytes(32);
	private final byte[] key = getRandomBytes(32);
	private final byte[] creator = getRandomBytes(32);
	private final byte[] mlDsa = getRandomBytes(
			PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES);
	private final byte[] sig = getRandomBytes(
			PostQuantumConstants.HYBRID_SIGNATURE_BYTES);

	private final PrivateMessageValidator validator =
			new PrivateMessageValidator(bdfReaderFactory, metadataEncoder,
					clock, crypto);

	@Test
	public void groupPermissions() throws Exception {
		accept(BdfList.of(GROUP_PERMISSIONS, id, 1L, now, sig));
		accept(BdfList.of(GROUP_PERMISSIONS, id, 0L, now, sig));
		accept(BdfList.of(GROUP_PERMISSIONS, id, 6L, now, sig));
		reject(BdfList.of(GROUP_PERMISSIONS, id, -1L, now, sig));
		reject(BdfList.of(GROUP_PERMISSIONS, id, 1L, now));
		reject(BdfList.of(GROUP_PERMISSIONS, id, 1L, now, sig, 1L));
		reject(BdfList.of(GROUP_PERMISSIONS, getRandomBytes(31), 1L, now,
				sig));
		reject(BdfList.of(GROUP_PERMISSIONS, id, 1L, now, new byte[0]));
		reject(BdfList.of(GROUP_PERMISSIONS, id, 1L, now,
				getRandomBytes(4097)));
	}

	@Test
	public void memberInviteOffer() throws Exception {
		accept(offer(2, "Club"));
		accept(offer(2, ""));
		reject(offer(2, getRandomString(101)));
		reject(offer(3, getRandomBytes(31)));
		reject(offer(4, ""));
		reject(offer(4, getRandomString(51)));
		reject(offer(5, getRandomBytes(31)));
		reject(offer(6, getRandomBytes(mlDsa.length - 1)));
		reject(offer(7, getRandomBytes(33)));
		reject(offer(8, -1L));
		reject(offer(10, new byte[0]));
		reject(offer(12, getRandomBytes(4097)));
		BdfList shorter = offer(2, "Club");
		shorter.remove(12);
		reject(shorter);
	}

	@Test
	public void theOfferIsKeptAsAnUnreadInvitationCard() throws Exception {
		expectValidation(offer(2, "Club"));
		BdfDictionary meta = validator.validateToBdf(message, group)
				.getDictionary();
		assertEquals(GROUPTR_MEMBER_INVITE_OFFER,
				meta.getLong(MessagingConstants.MSG_KEY_MSG_TYPE).intValue());
		assertFalse(meta.getBoolean(MessagingConstants.MSG_KEY_LOCAL));
		assertFalse(meta.getBoolean(
				org.zerionproject.app.client.MessageTrackerConstants
						.MSG_KEY_READ));
		assertArrayEquals(key, meta.getRaw(
				MessagingConstants.MSG_KEY_GTR_INVITE_INVITER_PUB));
		assertArrayEquals(creator, meta.getRaw(
				MessagingConstants.MSG_KEY_GTR_INVITE_CREATOR_PUB));
		assertEquals("Carol", meta.getString(
				MessagingConstants.MSG_KEY_GTR_INVITE_CREATOR_NAME));
		assertEquals("Club", meta.getString(
				MessagingConstants.MSG_KEY_GTR_INVITE_NAME));
	}

	@Test
	public void memberInviteResponses() throws Exception {
		accept(BdfList.of(GROUPTR_MEMBER_INVITE_ACCEPT, id, key, now, sig));
		accept(BdfList.of(GROUPTR_MEMBER_INVITE_DECLINE, id, key, now, sig));
		reject(BdfList.of(GROUPTR_MEMBER_INVITE_ACCEPT, id, now, sig));
		reject(BdfList.of(GROUPTR_MEMBER_INVITE_ACCEPT, id,
				getRandomBytes(31), now, sig));
		reject(BdfList.of(GROUPTR_MEMBER_INVITE_DECLINE, id, key, now,
				new byte[0]));
	}

	@Test
	public void joinRequest() throws Exception {
		accept(request(3, "Xavi"));
		reject(request(2, getRandomBytes(31)));
		reject(request(3, ""));
		reject(request(3, getRandomString(51)));
		reject(request(4, getRandomBytes(mlDsa.length + 1)));
		reject(request(6, new byte[0]));
		reject(request(8, getRandomBytes(4097)));
		BdfList shorter = request(3, "Xavi");
		shorter.remove(8);
		reject(shorter);
	}

	@Test
	public void memberInviteRecordsTravelOverTheMeshLikeOtherGroupRecords() {
		for (int t = GROUP_PERMISSIONS;
				t <= GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO; t++) {
			assertTrue(MessagingManagerImpl.isMeshGroupRecordType(t));
			assertEquals(t != GROUPTR_INVITE_ACCEPT_INCOGNITO,
					MessagingManagerImpl.isMemberInviteRecordType(t));
		}
		assertFalse(MessagingManagerImpl.isMeshGroupRecordType(
				GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO + 1));
		assertFalse(MessagingManagerImpl.isMemberInviteRecordType(
				GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO + 1));
		assertFalse(MessagingManagerImpl.isMemberInviteRecordType(
				GROUP_PERMISSIONS - 1));
		assertTrue(MessagingManagerImpl.isMemberInviteOfferType(
				GROUPTR_MEMBER_INVITE_OFFER));
		assertTrue(MessagingManagerImpl.isMemberInviteOfferType(
				GROUPTR_GRANTED_INVITE_OFFER));
		assertFalse(MessagingManagerImpl.isMemberInviteOfferType(
				GROUP_INVITER_GRANT));
	}

	@Test
	public void inviterGrant() throws Exception {
		accept(BdfList.of(GROUP_INVITER_GRANT, id, key, 1L, now, sig));
		accept(BdfList.of(GROUP_INVITER_GRANT, id, key, 0L, now, sig));
		reject(BdfList.of(GROUP_INVITER_GRANT, id, key, -1L, now, sig));
		reject(BdfList.of(GROUP_INVITER_GRANT, getRandomBytes(31), key, 1L,
				now, sig));
		reject(BdfList.of(GROUP_INVITER_GRANT, id, getRandomBytes(31), 1L,
				now, sig));
		reject(BdfList.of(GROUP_INVITER_GRANT, id, key, 1L, now));
		reject(BdfList.of(GROUP_INVITER_GRANT, id, key, 1L, now, sig, 1L));
		reject(BdfList.of(GROUP_INVITER_GRANT, id, key, 1L, now,
				new byte[0]));
		reject(BdfList.of(GROUP_INVITER_GRANT, id, key, 1L, now,
				getRandomBytes(4097)));
	}

	@Test
	public void aGrantedOfferIsKeptAsAnInvitationCardLikeAMemberOffer()
			throws Exception {
		accept(grantedOffer(2, "Club"));
		reject(grantedOffer(4, ""));
		reject(grantedOffer(6, getRandomBytes(mlDsa.length - 1)));
		reject(grantedOffer(8, -1L));
		reject(grantedOffer(12, new byte[0]));
		BdfList shorter = grantedOffer(2, "Club");
		shorter.remove(12);
		reject(shorter);
		expectValidation(grantedOffer(2, "Club"));
		BdfDictionary meta = validator.validateToBdf(message, group)
				.getDictionary();
		assertEquals(GROUPTR_GRANTED_INVITE_OFFER,
				meta.getLong(MessagingConstants.MSG_KEY_MSG_TYPE).intValue());
		assertFalse(meta.getBoolean(
				org.zerionproject.app.client.MessageTrackerConstants
						.MSG_KEY_READ));
		assertArrayEquals(key, meta.getRaw(
				MessagingConstants.MSG_KEY_GTR_INVITE_INVITER_PUB));
	}

	@Test
	public void incognitoAcceptances() throws Exception {
		accept(BdfList.of(GROUPTR_INVITE_ACCEPT_INCOGNITO, id, now,
				"Amber Falcon 27", sig));
		reject(BdfList.of(GROUPTR_INVITE_ACCEPT_INCOGNITO, id, now, "",
				sig));
		reject(BdfList.of(GROUPTR_INVITE_ACCEPT_INCOGNITO, id, now,
				getRandomString(51), sig));
		reject(BdfList.of(GROUPTR_INVITE_ACCEPT_INCOGNITO, id, now, sig));
		reject(BdfList.of(GROUPTR_INVITE_ACCEPT_INCOGNITO, id, now,
				"Amber Falcon 27", new byte[0]));
		expectValidation(BdfList.of(GROUPTR_INVITE_ACCEPT_INCOGNITO, id, now,
				"Amber Falcon 27", sig));
		BdfDictionary meta = validator.validateToBdf(message, group)
				.getDictionary();
		assertEquals("Amber Falcon 27", meta.getString(
				MessagingConstants.MSG_KEY_GTR_JOINER_NAME));
		assertArrayEquals(sig, meta.getRaw(
				MessagingConstants.MSG_KEY_GROUP_RECORD_SIG));

		accept(BdfList.of(GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO, id, key,
				now, "Amber Falcon 27", sig));
		reject(BdfList.of(GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO, id,
				getRandomBytes(31), now, "Amber Falcon 27", sig));
		reject(BdfList.of(GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO, id, key,
				now, "", sig));
		reject(BdfList.of(GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO, id, key,
				now, sig));
		reject(BdfList.of(GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO, id, key,
				now, "Amber Falcon 27", getRandomBytes(4097)));
	}

	private BdfList offer(int index, Object value) {
		BdfList b = BdfList.of(GROUPTR_MEMBER_INVITE_OFFER, id, "Club",
				getRandomBytes(32), "Carol", creator, mlDsa, key, 1L, now,
				sig, now, sig);
		b.set(index, value);
		return b;
	}

	private BdfList grantedOffer(int index, Object value) {
		BdfList b = offer(index, value);
		b.set(0, GROUPTR_GRANTED_INVITE_OFFER);
		return b;
	}

	private BdfList request(int index, Object value) {
		BdfList b = BdfList.of(GROUPTR_JOIN_REQUEST, id, key, "Xavi", mlDsa,
				now, sig, now, sig);
		b.set(index, value);
		return b;
	}

	private void accept(BdfList body) throws Exception {
		expectValidation(body);
		context.checking(new Expectations() {{
			oneOf(metadataEncoder).encode(with(any(BdfDictionary.class)));
			will(returnValue(new Metadata()));
		}});
		assertEquals(0, validator.validateMessage(message, group)
				.getDependencies().size());
	}

	private void reject(BdfList body) throws Exception {
		expectValidation(body);
		try {
			validator.validateMessage(message, group);
			fail("accepted " + body);
		} catch (InvalidMessageException expected) {
		}
	}

	private void expectValidation(BdfList body) throws Exception {
		context.checking(new Expectations() {{
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(bdfReaderFactory).createReader(with(any(InputStream.class)),
					with(any(int.class)), with(any(int.class)),
					with(any(boolean.class)));
			will(returnValue(reader));
			oneOf(reader).readList();
			will(returnValue(body));
			allowing(reader).eof();
			will(returnValue(true));
		}});
	}
}
