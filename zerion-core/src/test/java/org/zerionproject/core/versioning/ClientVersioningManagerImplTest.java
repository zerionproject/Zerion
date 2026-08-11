package org.zerionproject.core.versioning;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Group.Visibility;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.versioning.ClientVersioningManager.ClientVersioningHook;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.zerionproject.core.api.sync.Group.Visibility.INVISIBLE;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.api.sync.Group.Visibility.VISIBLE;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.zerionproject.core.api.versioning.ClientVersioningManager.CLIENT_ID;
import static org.zerionproject.core.api.versioning.ClientVersioningManager.MAJOR_VERSION;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.versioning.ClientVersioningConstants.MSG_KEY_LOCAL;
import static org.zerionproject.core.versioning.ClientVersioningConstants.MSG_KEY_UPDATE_VERSION;
import static org.junit.Assert.assertEquals;

public class ClientVersioningManagerImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final Clock clock = context.mock(Clock.class);
	private final SettingsManager settingsManager =
			context.mock(SettingsManager.class);
	private final ClientVersioningHook hook =
			context.mock(ClientVersioningHook.class);

	private final Group localGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Contact contact = getContact();
	private final ClientId clientId = getClientId();
	private final long now = System.currentTimeMillis();
	private final Transaction txn = new Transaction(null, false);

	private ClientVersioningManagerImpl createInstance() {
		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createLocalGroup(CLIENT_ID,
					MAJOR_VERSION);
			will(returnValue(localGroup));
		}});
		return new ClientVersioningManagerImpl(db, clientHelper,
				contactGroupFactory, clock, settingsManager);
	}

	@Test
	public void testCreatesGroupsAtStartup() throws Exception {
		context.checking(new Expectations() {{
			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(false));
			oneOf(db).addGroup(txn, localGroup);
			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));
		}});
		expectAddingContact();

		ClientVersioningManagerImpl c = createInstance();
		c.onDatabaseOpened(txn);
	}

	@Test
	public void testDoesNotCreateGroupsAtStartupIfAlreadyCreated()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(true));
		}});

		ClientVersioningManagerImpl c = createInstance();
		c.onDatabaseOpened(txn);
	}

	@Test
	public void testCreatesContactGroupWhenAddingContact() throws Exception {
		expectAddingContact();

		ClientVersioningManagerImpl c = createInstance();
		c.addingContact(txn, contact);
	}

	private void expectAddingContact() throws Exception {
		long now = System.currentTimeMillis();
		BdfList localUpdateBody = BdfList.of(new BdfList(), 1L);
		Message localUpdate = getMessage(contactGroup.getId());
		BdfDictionary localUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));

		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).addGroup(txn, contactGroup);
			oneOf(db).setGroupVisibility(txn, contact.getId(),
					contactGroup.getId(), SHARED);
			oneOf(clientHelper).setContactId(txn, contactGroup.getId(),
					contact.getId());
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(clientHelper).createMessage(contactGroup.getId(), now,
					localUpdateBody);
			will(returnValue(localUpdate));
			oneOf(clientHelper).addLocalMessage(txn, localUpdate,
					localUpdateMeta, true, false);
		}});
	}

	@Test
	public void testRemovesGroupWhenRemovingContact() throws Exception {
		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).removeGroup(txn, contactGroup);
		}});

		ClientVersioningManagerImpl c = createInstance();
		c.removingContact(txn, contact);
	}

	@Test
	public void testStoresClientVersionsAtFirstStartup() throws Exception {
		BdfList localVersionsBody =
				BdfList.of(BdfList.of(clientId.getString(), 123, 234));
		Message localVersions = getMessage(localGroup.getId());
		MessageId localUpdateId = new MessageId(getRandomId());
		BdfDictionary localUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		BdfList localUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, false)), 1L);

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(txn));

			oneOf(db).getMessageIds(txn, localGroup.getId());
			will(returnValue(emptyList()));

			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(clientHelper).createMessage(localGroup.getId(), now,
					localVersionsBody);
			will(returnValue(localVersions));
			oneOf(db).addLocalMessage(txn, localVersions, new Metadata(),
					false, false);

			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(singletonMap(localUpdateId, localUpdateMeta)));

			oneOf(clientHelper).getMessageAsList(txn, localUpdateId);
			will(returnValue(localUpdateBody));

		}});

		ClientVersioningManagerImpl c = createInstance();
		c.registerClient(clientId, 123, 234, hook);
		c.startService();
	}

	@Test
	public void testComparesClientVersionsAtSubsequentStartup()
			throws Exception {
		MessageId localVersionsId = new MessageId(getRandomId());
		BdfList localVersionsBody =
				BdfList.of(BdfList.of(clientId.getString(), 123, 234));

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(txn));

			oneOf(db).getMessageIds(txn, localGroup.getId());
			will(returnValue(singletonList(localVersionsId)));
			oneOf(clientHelper).getMessageAsList(txn, localVersionsId);
			will(returnValue(localVersionsBody));

		}});

		ClientVersioningManagerImpl c = createInstance();
		c.registerClient(clientId, 123, 234, hook);
		c.startService();
	}

	@Test
	public void testStoresClientVersionsAtSubsequentStartupIfChanged()
			throws Exception {

		BdfList oldLocalVersionsBody =
				BdfList.of(BdfList.of(clientId.getString(), 123, 234));

		BdfList newLocalVersionsBody =
				BdfList.of(BdfList.of(clientId.getString(), 123, 345));

		BdfList oldLocalUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, false)), 1L);

		BdfList newLocalUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 345, false)), 2L);

		MessageId oldLocalVersionsId = new MessageId(getRandomId());
		Message newLocalVersions = getMessage(localGroup.getId());
		MessageId oldLocalUpdateId = new MessageId(getRandomId());
		BdfDictionary oldLocalUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		Message newLocalUpdate = getMessage(contactGroup.getId());
		BdfDictionary newLocalUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 2L),
				new BdfEntry(MSG_KEY_LOCAL, true));

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(txn));

			oneOf(db).getMessageIds(txn, localGroup.getId());
			will(returnValue(singletonList(oldLocalVersionsId)));
			oneOf(clientHelper).getMessageAsList(txn, oldLocalVersionsId);
			will(returnValue(oldLocalVersionsBody));

			oneOf(db).removeMessage(txn, oldLocalVersionsId);

			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(clientHelper).createMessage(localGroup.getId(), now,
					newLocalVersionsBody);
			will(returnValue(newLocalVersions));
			oneOf(db).addLocalMessage(txn, newLocalVersions, new Metadata(),
					false, false);

			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(singletonMap(oldLocalUpdateId,
					oldLocalUpdateMeta)));

			oneOf(clientHelper).getMessageAsList(txn, oldLocalUpdateId);
			will(returnValue(oldLocalUpdateBody));

			oneOf(db).deleteMessage(txn, oldLocalUpdateId);
			oneOf(db).deleteMessageMetadata(txn, oldLocalUpdateId);

			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(clientHelper).createMessage(contactGroup.getId(), now,
					newLocalUpdateBody);
			will(returnValue(newLocalUpdate));
			oneOf(clientHelper).addLocalMessage(txn, newLocalUpdate,
					newLocalUpdateMeta, true, false);

		}});

		ClientVersioningManagerImpl c = createInstance();
		c.registerClient(clientId, 123, 345, hook);
		c.startService();
	}

	@Test
	public void testActivatesNewClientAtStartupIfAlreadyAdvertisedByContact()
			throws Exception {
		testActivatesNewClientAtStartup(false, VISIBLE);
	}

	@Test
	public void testActivatesNewClientAtStartupIfAlreadyActivatedByContact()
			throws Exception {
		testActivatesNewClientAtStartup(true, SHARED);
	}

	private void testActivatesNewClientAtStartup(boolean remoteActive,
			Visibility visibility) throws Exception {

		BdfList oldLocalVersionsBody = new BdfList();

		BdfList newLocalVersionsBody =
				BdfList.of(BdfList.of(clientId.getString(), 123, 234));

		BdfList oldLocalUpdateBody = BdfList.of(new BdfList(), 1L);

		BdfList oldRemoteUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 345, remoteActive)), 1L);

		BdfList newLocalUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, true)), 2L);

		MessageId oldLocalVersionsId = new MessageId(getRandomId());
		Message newLocalVersions = getMessage(localGroup.getId());
		MessageId oldLocalUpdateId = new MessageId(getRandomId());
		BdfDictionary oldLocalUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId oldRemoteUpdateId = new MessageId(getRandomId());
		BdfDictionary oldRemoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(oldLocalUpdateId, oldLocalUpdateMeta);
		messageMetadata.put(oldRemoteUpdateId, oldRemoteUpdateMeta);
		Message newLocalUpdate = getMessage(localGroup.getId());
		BdfDictionary newLocalUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 2L),
				new BdfEntry(MSG_KEY_LOCAL, true));

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(txn));

			oneOf(db).getMessageIds(txn, localGroup.getId());
			will(returnValue(singletonList(oldLocalVersionsId)));
			oneOf(clientHelper).getMessageAsList(txn, oldLocalVersionsId);
			will(returnValue(oldLocalVersionsBody));

			oneOf(db).removeMessage(txn, oldLocalVersionsId);

			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(clientHelper).createMessage(localGroup.getId(), now,
					newLocalVersionsBody);
			will(returnValue(newLocalVersions));
			oneOf(db).addLocalMessage(txn, newLocalVersions, new Metadata(),
					false, false);

			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));

			oneOf(clientHelper).getMessageAsList(txn, oldLocalUpdateId);
			will(returnValue(oldLocalUpdateBody));

			oneOf(clientHelper).getMessageAsList(txn, oldRemoteUpdateId);
			will(returnValue(oldRemoteUpdateBody));

			oneOf(db).deleteMessage(txn, oldLocalUpdateId);
			oneOf(db).deleteMessageMetadata(txn, oldLocalUpdateId);

			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(clientHelper).createMessage(contactGroup.getId(), now,
					newLocalUpdateBody);
			will(returnValue(newLocalUpdate));
			oneOf(clientHelper).addLocalMessage(txn, newLocalUpdate,
					newLocalUpdateMeta, true, false);

			oneOf(hook).onClientVisibilityChanging(txn, contact, visibility);
		}});

		ClientVersioningManagerImpl c = createInstance();
		c.registerClient(clientId, 123, 234, hook);
		c.startService();
	}

	@Test
	public void testDeletesObsoleteRemoteUpdate() throws Exception {
		Message newRemoteUpdate = getMessage(contactGroup.getId());
		BdfList newRemoteUpdateBody = BdfList.of(new BdfList(), 1L);
		MessageId oldLocalUpdateId = new MessageId(getRandomId());
		BdfDictionary oldLocalUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId oldRemoteUpdateId = new MessageId(getRandomId());
		BdfDictionary oldRemoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 2L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(oldLocalUpdateId, oldLocalUpdateMeta);
		messageMetadata.put(oldRemoteUpdateId, oldRemoteUpdateMeta);

		context.checking(new Expectations() {{
			oneOf(clientHelper).toList(newRemoteUpdate);
			will(returnValue(newRemoteUpdateBody));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));

			oneOf(db).deleteMessage(txn, newRemoteUpdate.getId());
			oneOf(db).deleteMessageMetadata(txn, newRemoteUpdate.getId());
		}});

		ClientVersioningManagerImpl c = createInstance();
		c.registerClient(clientId, 123, 234, hook);
		assertEquals(ACCEPT_DO_NOT_SHARE,
				c.incomingMessage(txn, newRemoteUpdate, new Metadata()));
	}

	@Test
	public void testDeletesPreviousRemoteUpdate() throws Exception {
		Message newRemoteUpdate = getMessage(contactGroup.getId());
		BdfList newRemoteUpdateBody = BdfList.of(new BdfList(), 2L);
		MessageId oldLocalUpdateId = new MessageId(getRandomId());
		BdfDictionary oldLocalUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId oldRemoteUpdateId = new MessageId(getRandomId());
		BdfDictionary oldRemoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(oldLocalUpdateId, oldLocalUpdateMeta);
		messageMetadata.put(oldRemoteUpdateId, oldRemoteUpdateMeta);
		BdfList oldLocalUpdateBody = BdfList.of(new BdfList(), 1L);
		BdfList oldRemoteUpdateBody = BdfList.of(new BdfList(), 1L);

		context.checking(new Expectations() {{
			oneOf(clientHelper).toList(newRemoteUpdate);
			will(returnValue(newRemoteUpdateBody));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));

			oneOf(clientHelper).getMessageAsList(txn, oldLocalUpdateId);
			will(returnValue(oldLocalUpdateBody));

			oneOf(clientHelper).getMessageAsList(txn, oldRemoteUpdateId);
			will(returnValue(oldRemoteUpdateBody));

			oneOf(db).deleteMessage(txn, oldRemoteUpdateId);
			oneOf(db).deleteMessageMetadata(txn, oldRemoteUpdateId);

			oneOf(clientHelper).getContactId(txn, contactGroup.getId());
			will(returnValue(contact.getId()));

		}});

		ClientVersioningManagerImpl c = createInstance();
		c.registerClient(clientId, 123, 234, hook);
		assertEquals(ACCEPT_DO_NOT_SHARE,
				c.incomingMessage(txn, newRemoteUpdate, new Metadata()));
	}

	@Test
	public void testAcceptsFirstRemoteUpdate() throws Exception {
		Message newRemoteUpdate = getMessage(contactGroup.getId());
		BdfList newRemoteUpdateBody = BdfList.of(new BdfList(), 1L);
		MessageId oldLocalUpdateId = new MessageId(getRandomId());
		BdfDictionary oldLocalUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		BdfList oldLocalUpdateBody = BdfList.of(new BdfList(), 1L);

		context.checking(new Expectations() {{
			oneOf(clientHelper).toList(newRemoteUpdate);
			will(returnValue(newRemoteUpdateBody));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(singletonMap(oldLocalUpdateId,
					oldLocalUpdateMeta)));

			oneOf(clientHelper).getMessageAsList(txn, oldLocalUpdateId);
			will(returnValue(oldLocalUpdateBody));

			oneOf(clientHelper).getContactId(txn, contactGroup.getId());
			will(returnValue(contact.getId()));

		}});

		ClientVersioningManagerImpl c = createInstance();
		c.registerClient(clientId, 123, 234, hook);
		assertEquals(ACCEPT_DO_NOT_SHARE,
				c.incomingMessage(txn, newRemoteUpdate, new Metadata()));
	}

	@Test
	public void testActivatesClientOnIncomingMessageWhenAdvertisedByContact()
			throws Exception {
		testActivatesClientOnIncomingMessage(false, VISIBLE);
	}

	@Test
	public void testActivatesClientOnIncomingMessageWhenActivatedByContact()
			throws Exception {
		testActivatesClientOnIncomingMessage(true, SHARED);
	}

	private void testActivatesClientOnIncomingMessage(boolean remoteActive,
			Visibility visibility) throws Exception {

		BdfList oldRemoteUpdateBody = BdfList.of(new BdfList(), 1L);

		BdfList oldLocalUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, false)), 1L);

		BdfList newRemoteUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, remoteActive)), 2L);

		BdfList newLocalUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, true)), 2L);

		Message newRemoteUpdate = getMessage(contactGroup.getId());
		MessageId oldLocalUpdateId = new MessageId(getRandomId());
		BdfDictionary oldLocalUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId oldRemoteUpdateId = new MessageId(getRandomId());
		BdfDictionary oldRemoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(oldLocalUpdateId, oldLocalUpdateMeta);
		messageMetadata.put(oldRemoteUpdateId, oldRemoteUpdateMeta);
		Message newLocalUpdate = getMessage(contactGroup.getId());
		BdfDictionary newLocalUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 2L),
				new BdfEntry(MSG_KEY_LOCAL, true));

		context.checking(new Expectations() {{
			oneOf(clientHelper).toList(newRemoteUpdate);
			will(returnValue(newRemoteUpdateBody));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));

			oneOf(clientHelper).getMessageAsList(txn, oldLocalUpdateId);
			will(returnValue(oldLocalUpdateBody));

			oneOf(clientHelper).getMessageAsList(txn, oldRemoteUpdateId);
			will(returnValue(oldRemoteUpdateBody));

			oneOf(db).deleteMessage(txn, oldRemoteUpdateId);
			oneOf(db).deleteMessageMetadata(txn, oldRemoteUpdateId);

			oneOf(db).deleteMessage(txn, oldLocalUpdateId);
			oneOf(db).deleteMessageMetadata(txn, oldLocalUpdateId);

			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(clientHelper).createMessage(contactGroup.getId(), now,
					newLocalUpdateBody);
			will(returnValue(newLocalUpdate));
			oneOf(clientHelper).addLocalMessage(txn, newLocalUpdate,
					newLocalUpdateMeta, true, false);

			oneOf(clientHelper).getContactId(txn, contactGroup.getId());
			will(returnValue(contact.getId()));
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			oneOf(hook).onClientVisibilityChanging(txn, contact, visibility);
		}});

		ClientVersioningManagerImpl c = createInstance();
		c.registerClient(clientId, 123, 234, hook);
		assertEquals(ACCEPT_DO_NOT_SHARE,
				c.incomingMessage(txn, newRemoteUpdate, new Metadata()));
	}

	@Test
	public void testDeactivatesClientOnIncomingMessage() throws Exception {

		BdfList oldLocalUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, true)), 1L);
		BdfList oldRemoteUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, true)), 1L);

		BdfList newRemoteUpdateBody = BdfList.of(new BdfList(), 2L);

		BdfList newLocalUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, false)), 2L);

		Message newRemoteUpdate = getMessage(contactGroup.getId());
		MessageId oldLocalUpdateId = new MessageId(getRandomId());
		BdfDictionary oldLocalUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId oldRemoteUpdateId = new MessageId(getRandomId());
		BdfDictionary oldRemoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(oldLocalUpdateId, oldLocalUpdateMeta);
		messageMetadata.put(oldRemoteUpdateId, oldRemoteUpdateMeta);
		Message newLocalUpdate = getMessage(contactGroup.getId());
		BdfDictionary newLocalUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 2L),
				new BdfEntry(MSG_KEY_LOCAL, true));

		context.checking(new Expectations() {{
			oneOf(clientHelper).toList(newRemoteUpdate);
			will(returnValue(newRemoteUpdateBody));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));

			oneOf(clientHelper).getMessageAsList(txn, oldLocalUpdateId);
			will(returnValue(oldLocalUpdateBody));

			oneOf(clientHelper).getMessageAsList(txn, oldRemoteUpdateId);
			will(returnValue(oldRemoteUpdateBody));

			oneOf(db).deleteMessage(txn, oldRemoteUpdateId);
			oneOf(db).deleteMessageMetadata(txn, oldRemoteUpdateId);

			oneOf(db).deleteMessage(txn, oldLocalUpdateId);
			oneOf(db).deleteMessageMetadata(txn, oldLocalUpdateId);

			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(clientHelper).createMessage(contactGroup.getId(), now,
					newLocalUpdateBody);
			will(returnValue(newLocalUpdate));
			oneOf(clientHelper).addLocalMessage(txn, newLocalUpdate,
					newLocalUpdateMeta, true, false);

			oneOf(clientHelper).getContactId(txn, contactGroup.getId());
			will(returnValue(contact.getId()));
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			oneOf(hook).onClientVisibilityChanging(txn, contact, INVISIBLE);
		}});

		ClientVersioningManagerImpl c = createInstance();
		c.registerClient(clientId, 123, 234, hook);
		assertEquals(ACCEPT_DO_NOT_SHARE,
				c.incomingMessage(txn, newRemoteUpdate, new Metadata()));
	}

	@Test
	public void testReturnsInvisibleIfContactGroupDoesNotExist()
			throws Exception {
		expectGetContactGroup(false);

		ClientVersioningManagerImpl c = createInstance();
		assertEquals(INVISIBLE, c.getClientVisibility(txn, contact.getId(),
				clientId, 123));
	}

	@Test
	public void testReturnsInvisibleIfNoRemoteUpdateExists() throws Exception {
		MessageId localUpdateId = new MessageId(getRandomId());
		BdfDictionary localUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));

		expectGetContactGroup(true);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(singletonMap(localUpdateId, localUpdateMeta)));
		}});

		ClientVersioningManagerImpl c = createInstance();
		assertEquals(INVISIBLE, c.getClientVisibility(txn, contact.getId(),
				clientId, 123));
	}

	@Test(expected = DbException.class)
	public void testThrowsExceptionIfNoLocalUpdateExists() throws Exception {
		MessageId remoteUpdateId = new MessageId(getRandomId());
		BdfDictionary remoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));

		expectGetContactGroup(true);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(singletonMap(remoteUpdateId, remoteUpdateMeta)));
		}});

		ClientVersioningManagerImpl c = createInstance();
		c.getClientVisibility(txn, contact.getId(), clientId, 123);
	}

	@Test
	public void testReturnsInvisibleIfClientNotSupportedLocally()
			throws Exception {
		MessageId localUpdateId = new MessageId(getRandomId());
		BdfDictionary localUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId remoteUpdateId = new MessageId(getRandomId());
		BdfDictionary remoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(localUpdateId, localUpdateMeta);
		messageMetadata.put(remoteUpdateId, remoteUpdateMeta);

		BdfList localUpdateBody = BdfList.of(new BdfList(), 1L);
		BdfList remoteUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, false)), 1L);

		expectGetContactGroup(true);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, localUpdateId);
			will(returnValue(localUpdateBody));
			oneOf(clientHelper).getMessageAsList(txn, remoteUpdateId);
			will(returnValue(remoteUpdateBody));
		}});

		ClientVersioningManagerImpl c = createInstance();
		assertEquals(INVISIBLE, c.getClientVisibility(txn, contact.getId(),
				clientId, 123));
	}

	@Test
	public void testReturnsInvisibleIfClientNotSupportedRemotely()
			throws Exception {
		MessageId localUpdateId = new MessageId(getRandomId());
		BdfDictionary localUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId remoteUpdateId = new MessageId(getRandomId());
		BdfDictionary remoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(localUpdateId, localUpdateMeta);
		messageMetadata.put(remoteUpdateId, remoteUpdateMeta);

		BdfList localUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, false)), 1L);
		BdfList remoteUpdateBody = BdfList.of(new BdfList(), 1L);

		expectGetContactGroup(true);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, localUpdateId);
			will(returnValue(localUpdateBody));
			oneOf(clientHelper).getMessageAsList(txn, remoteUpdateId);
			will(returnValue(remoteUpdateBody));
		}});

		ClientVersioningManagerImpl c = createInstance();
		assertEquals(INVISIBLE, c.getClientVisibility(txn, contact.getId(),
				clientId, 123));
	}

	@Test
	public void testReturnsVisibleIfClientNotActiveRemotely() throws Exception {
		MessageId localUpdateId = new MessageId(getRandomId());
		BdfDictionary localUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId remoteUpdateId = new MessageId(getRandomId());
		BdfDictionary remoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(localUpdateId, localUpdateMeta);
		messageMetadata.put(remoteUpdateId, remoteUpdateMeta);

		BdfList localUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, false)), 1L);
		BdfList remoteUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, false)), 1L);

		expectGetContactGroup(true);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, localUpdateId);
			will(returnValue(localUpdateBody));
			oneOf(clientHelper).getMessageAsList(txn, remoteUpdateId);
			will(returnValue(remoteUpdateBody));
		}});

		ClientVersioningManagerImpl c = createInstance();
		assertEquals(VISIBLE, c.getClientVisibility(txn, contact.getId(),
				clientId, 123));
	}

	@Test
	public void testReturnsSharedIfClientActiveRemotely() throws Exception {
		MessageId localUpdateId = new MessageId(getRandomId());
		BdfDictionary localUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId remoteUpdateId = new MessageId(getRandomId());
		BdfDictionary remoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(localUpdateId, localUpdateMeta);
		messageMetadata.put(remoteUpdateId, remoteUpdateMeta);

		BdfList localUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, true)), 1L);
		BdfList remoteUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, true)), 1L);

		expectGetContactGroup(true);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, localUpdateId);
			will(returnValue(localUpdateBody));
			oneOf(clientHelper).getMessageAsList(txn, remoteUpdateId);
			will(returnValue(remoteUpdateBody));
		}});

		ClientVersioningManagerImpl c = createInstance();
		assertEquals(SHARED, c.getClientVisibility(txn, contact.getId(),
				clientId, 123));
	}

	@Test
	public void testReturnsNegativeIfContactGroupDoesNotExist()
			throws Exception {
		expectGetContactGroup(false);

		ClientVersioningManagerImpl c = createInstance();
		assertEquals(-1, c.getClientMinorVersion(txn, contact.getId(),
				clientId, 123));
	}

	@Test
	public void testReturnsNegativeIfNoRemoteUpdateExists() throws Exception {
		MessageId localUpdateId = new MessageId(getRandomId());
		BdfDictionary localUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));

		expectGetContactGroup(true);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(singletonMap(localUpdateId, localUpdateMeta)));
		}});

		ClientVersioningManagerImpl c = createInstance();
		assertEquals(-1, c.getClientMinorVersion(txn, contact.getId(),
				clientId, 123));
	}

	@Test
	public void testReturnsNegativeIfClientNotSupportedRemotely()
			throws Exception {
		MessageId localUpdateId = new MessageId(getRandomId());
		BdfDictionary localUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId remoteUpdateId = new MessageId(getRandomId());
		BdfDictionary remoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(localUpdateId, localUpdateMeta);
		messageMetadata.put(remoteUpdateId, remoteUpdateMeta);

		BdfList remoteUpdateBody = BdfList.of(new BdfList(), 1L);

		expectGetContactGroup(true);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, remoteUpdateId);
			will(returnValue(remoteUpdateBody));
		}});

		ClientVersioningManagerImpl c = createInstance();
		assertEquals(-1, c.getClientMinorVersion(txn, contact.getId(),
				clientId, 123));
	}

	@Test
	public void testReturnsMinorVersionIfClientNotActiveRemotely()
			throws Exception {
		MessageId localUpdateId = new MessageId(getRandomId());
		BdfDictionary localUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId remoteUpdateId = new MessageId(getRandomId());
		BdfDictionary remoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(localUpdateId, localUpdateMeta);
		messageMetadata.put(remoteUpdateId, remoteUpdateMeta);

		BdfList remoteUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, false)), 1L);

		expectGetContactGroup(true);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, remoteUpdateId);
			will(returnValue(remoteUpdateBody));
		}});

		ClientVersioningManagerImpl c = createInstance();
		assertEquals(234, c.getClientMinorVersion(txn, contact.getId(),
				clientId, 123));
	}

	@Test
	public void testReturnsMinorVersionIfClientActiveRemotely()
			throws Exception {
		MessageId localUpdateId = new MessageId(getRandomId());
		BdfDictionary localUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, true));
		MessageId remoteUpdateId = new MessageId(getRandomId());
		BdfDictionary remoteUpdateMeta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_UPDATE_VERSION, 1L),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Map<MessageId, BdfDictionary> messageMetadata = new HashMap<>();
		messageMetadata.put(localUpdateId, localUpdateMeta);
		messageMetadata.put(remoteUpdateId, remoteUpdateMeta);

		BdfList remoteUpdateBody = BdfList.of(BdfList.of(
				BdfList.of(clientId.getString(), 123, 234, true)), 1L);

		expectGetContactGroup(true);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, remoteUpdateId);
			will(returnValue(remoteUpdateBody));
		}});

		ClientVersioningManagerImpl c = createInstance();
		assertEquals(234, c.getClientMinorVersion(txn, contact.getId(),
				clientId, 123));
	}

	private void expectGetContactGroup(boolean exists) throws Exception {
		context.checking(new Expectations() {{
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).containsGroup(txn, contactGroup.getId());
			will(returnValue(exists));
		}});
	}
}
