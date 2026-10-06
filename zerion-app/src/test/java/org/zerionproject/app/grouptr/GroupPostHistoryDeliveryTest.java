package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getLocalAuthor;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GroupPostHistoryDeliveryTest extends BrambleMockTestCase {

	private static final long EPOCH = 5L;

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final SettingsManager settingsManager =
			context.mock(SettingsManager.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final IdentityManager identityManager =
			context.mock(IdentityManager.class);
	private final ContactManager contactManager =
			context.mock(ContactManager.class);
	private final MessagingManager messagingManager =
			context.mock(MessagingManager.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final Clock clock = context.mock(Clock.class);

	private final Transaction txn = new Transaction(null, true);
	private final byte[] groupId = getRandomId();
	private final byte[] creator = key((byte) 1);
	private final byte[] member = key((byte) 2);
	private final byte[] stranger = key((byte) 4);
	private final byte[] memberMlDsa =
			new byte[PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES];
	private final LocalAuthor local = getLocalAuthor();

	private final GroupTrManagerImpl manager = new GroupTrManagerImpl(db,
			Runnable::run, settingsManager, clientHelper, crypto,
			identityManager, contactManager, messagingManager, eventBus,
			clock);

	@Test
	public void aMembersPostDeliveredByAStrangerStaysHidden()
			throws Exception {
		Contact strangerContact = contact(stranger);
		Map<Contact, Map<MessageId, BdfDictionary>> stored = new HashMap<>();
		stored.put(strangerContact, posts(post(member, "replayed")));
		expectHistory(stored);

		List<GroupTrPost> shown = manager.getRecentPosts(groupId);

		assertTrue("a post delivered by a non-member is not shown",
				shown.isEmpty());
	}

	@Test
	public void aMembersPostDeliveredByTheMemberIsShown() throws Exception {
		Contact memberContact = contact(member);
		Map<Contact, Map<MessageId, BdfDictionary>> stored = new HashMap<>();
		stored.put(memberContact, posts(post(member, "hello")));
		expectHistory(stored);

		List<GroupTrPost> shown = manager.getRecentPosts(groupId);

		assertEquals(1, shown.size());
		assertArrayEquals(member, shown.get(0).getSenderPubKey());
	}

	@Test
	public void onlyTheMembersOwnDeliveryIsShown() throws Exception {
		Map<Contact, Map<MessageId, BdfDictionary>> stored = new HashMap<>();
		stored.put(contact(member), posts(post(member, "hello")));
		stored.put(contact(stranger), posts(post(member, "injected")));
		expectHistory(stored);

		List<GroupTrPost> shown = manager.getRecentPosts(groupId);

		assertEquals(1, shown.size());
		assertArrayEquals("hello".getBytes("UTF-8"), shown.get(0).getBody());
	}

	private void expectHistory(
			Map<Contact, Map<MessageId, BdfDictionary>> stored)
			throws Exception {
		Collection<Contact> contacts = new ArrayList<>(stored.keySet());
		Settings state = groupState();
		Settings index = new Settings();
		index.put(GroupTrConstants.S_GROUP_IDS, toHexString(groupId));
		BdfList members = BdfList.of(BdfList.of(member, "Member", 0L, 1L,
				0L, memberMlDsa));
		context.checking(new DbExpectations() {{
			allowing(db).transaction(with(true), withDbRunnable(txn));
			allowing(db).transactionWithNullableResult(with(true),
					withNullableDbCallable(txn));
			allowing(identityManager).getLocalAuthor(txn);
			will(returnValue(local));
			allowing(contactManager).getContacts(txn);
			will(returnValue(contacts));
			for (Map.Entry<Contact, Map<MessageId, BdfDictionary>> e
					: stored.entrySet()) {
				Group g = getGroup(new ClientId("test"), 0);
				allowing(messagingManager).getContactGroup(e.getKey());
				will(returnValue(g));
				allowing(clientHelper).getMessageMetadataAsDictionary(txn,
						g.getId());
				will(returnValue(e.getValue()));
				allowing(clientHelper).getMessageIds(with(txn),
						with(g.getId()), with(any(BdfDictionary.class)));
				will(returnValue(e.getValue().keySet()));
				for (Map.Entry<MessageId, BdfDictionary> m
						: e.getValue().entrySet()) {
					allowing(clientHelper).getMessageMetadataAsDictionary(txn,
							m.getKey());
					will(returnValue(m.getValue()));
				}
			}
		}});
		context.checking(new Expectations() {{
			allowing(settingsManager).getSettings(
					GroupTrConstants.SETTINGS_NS_INDEX);
			will(returnValue(index));
			allowing(settingsManager).getSettings(
					GroupTrConstants.SETTINGS_NS_PREFIX
							+ toHexString(groupId));
			will(returnValue(state));
			allowing(clientHelper).toList(with(any(byte[].class)));
			will(returnValue(members));
			allowing(crypto).hash(with(any(String.class)),
					with(any(byte[][].class)));
			will(returnValue(new byte[32]));
			allowing(crypto).verifyHybridSignature(with(any(byte[].class)),
					with(any(String.class)), with(any(byte[].class)),
					with(any(PublicKey.class)));
			will(returnValue(true));
			allowing(clock).currentTimeMillis();
			will(returnValue(10_000L));
		}});
	}

	private Settings groupState() {
		Settings s = new Settings();
		s.put(GroupTrConstants.S_NAME, "group");
		s.put(GroupTrConstants.S_SALT, toHexString(new byte[32]));
		s.put(GroupTrConstants.S_CREATOR_PUBKEY, toHexString(creator));
		s.put(GroupTrConstants.S_CREATOR_NAME, "Creator");
		s.putLong(GroupTrConstants.S_CREATED, 0L);
		s.putLong(GroupTrConstants.S_EPOCH, EPOCH);
		s.putBoolean(GroupTrConstants.S_DISSOLVED, false);
		s.put(GroupTrConstants.S_MEMBERS, "00");
		return s;
	}

	private BdfDictionary post(byte[] signer, String body) throws Exception {
		BdfDictionary d = new BdfDictionary();
		d.put("messageType", 32);
		d.put("groupId", groupId);
		d.put("groupEpoch", EPOCH);
		d.put("groupSenderPubKey", signer);
		d.put("groupSenderName", "Member");
		d.put("groupCiphertext", body.getBytes("UTF-8"));
		d.put("timestamp", 1_000L);
		d.put("autoDeleteTimer", 0L);
		d.put("groupRecordSig",
				new byte[PostQuantumConstants.HYBRID_SIGNATURE_BYTES]);
		return d;
	}

	private static Map<MessageId, BdfDictionary> posts(BdfDictionary... ds) {
		Map<MessageId, BdfDictionary> out = new HashMap<>();
		for (BdfDictionary d : ds) out.put(new MessageId(getRandomId()), d);
		return out;
	}

	private static Contact contact(byte[] pub) {
		Author a = new Author(new AuthorId(getRandomId()), 1, "c",
				new SignaturePublicKey(pub));
		return org.zerionproject.core.test.TestUtils.getContact(a,
				new AuthorId(getRandomId()), true);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
