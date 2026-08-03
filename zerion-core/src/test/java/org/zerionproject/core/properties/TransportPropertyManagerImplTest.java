package org.zerionproject.core.properties;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.event.RemoteTransportPropertiesUpdatedEvent;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.GROUP_KEY_DISCOVERED;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_LOCAL;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_TRANSPORT_ID;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_VERSION;
import static org.zerionproject.core.api.properties.TransportPropertyManager.CLIENT_ID;
import static org.zerionproject.core.api.properties.TransportPropertyManager.MAJOR_VERSION;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.hasEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransportPropertyManagerImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ClientVersioningManager clientVersioningManager =
			context.mock(ClientVersioningManager.class);
	private final MetadataParser metadataParser =
			context.mock(MetadataParser.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final Clock clock = context.mock(Clock.class);
	private final SettingsManager settingsManager =
			context.mock(SettingsManager.class);
	private final AccountManager accountManager =
			context.mock(AccountManager.class);
	private final B4OnionRotation b4OnionRotation =
			new B4OnionRotation(db, settingsManager, accountManager, clock);

	private final Group localGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final BdfDictionary fooPropertiesDict, barPropertiesDict;
	private final BdfDictionary discoveredPropertiesDict, mergedPropertiesDict;
	private final TransportProperties fooProperties, barProperties;
	private final TransportProperties discoveredProperties;

	public TransportPropertyManagerImplTest() {
		fooProperties = new TransportProperties();
		fooProperties.put("fooKey1", "fooValue1");
		fooProperties.put("fooKey2", "fooValue2");
		fooPropertiesDict = new BdfDictionary(fooProperties);

		barProperties = new TransportProperties();
		barProperties.put("barKey1", "barValue1");
		barProperties.put("barKey2", "barValue2");
		barPropertiesDict = new BdfDictionary(barProperties);

		discoveredProperties = new TransportProperties();
		discoveredProperties.put("fooKey3", "fooValue3");
		discoveredPropertiesDict = new BdfDictionary(discoveredProperties);

		mergedPropertiesDict = new BdfDictionary(fooProperties);
		mergedPropertiesDict.put("u:fooKey3", "fooValue3");
	}

	private TransportPropertyManagerImpl createInstance() {
		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createLocalGroup(CLIENT_ID,
					MAJOR_VERSION);
			will(returnValue(localGroup));
		}});
		return new TransportPropertyManagerImpl(db, clientHelper,
				clientVersioningManager, metadataParser, contactGroupFactory,
				clock, b4OnionRotation);
	}

	@Test
	public void testCreatesGroupsAtStartup() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);

		context.checking(new Expectations() {{
			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(false));
			oneOf(db).addGroup(txn, localGroup);
			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).addGroup(txn, contactGroup);
			oneOf(db).setGroupVisibility(txn, contact.getId(),
					contactGroup.getId(), SHARED);
			oneOf(clientHelper).setContactId(txn, contactGroup.getId(),
					contact.getId());
		}});

		expectGetLocalProperties(txn);
		expectStoreMessage(txn, contactGroup.getId(), "foo", fooPropertiesDict,
				1, true, true);
		expectStoreMessage(txn, contactGroup.getId(), "bar", barPropertiesDict,
				1, true, true);

		TransportPropertyManagerImpl t = createInstance();
		t.onDatabaseOpened(txn);
	}

	@Test
	public void testDoesNotCreateGroupsAtStartupIfAlreadyCreated()
			throws Exception {
		Transaction txn = new Transaction(null, false);

		context.checking(new Expectations() {{
			oneOf(db).containsGroup(txn, localGroup.getId());
			will(returnValue(true));
			oneOf(db).getContacts(txn);
			will(returnValue(emptyList()));
		}});

		TransportPropertyManagerImpl t = createInstance();
		t.onDatabaseOpened(txn);
	}

	@Test
	public void testCreatesContactGroupWhenAddingContact() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);

		context.checking(new Expectations() {{

			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).addGroup(txn, contactGroup);
			oneOf(db).setGroupVisibility(txn, contact.getId(),
					contactGroup.getId(), SHARED);
			oneOf(clientHelper).setContactId(txn, contactGroup.getId(),
					contact.getId());
		}});

		expectGetLocalProperties(txn);
		expectStoreMessage(txn, contactGroup.getId(), "foo", fooPropertiesDict,
				1, true, true);
		expectStoreMessage(txn, contactGroup.getId(), "bar", barPropertiesDict,
				1, true, true);

		TransportPropertyManagerImpl t = createInstance();
		t.addingContact(txn, contact);
	}

	@Test
	public void testRemovesGroupWhenRemovingContact() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);

		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(db).removeGroup(txn, contactGroup);
		}});

		TransportPropertyManagerImpl t = createInstance();
		t.removingContact(txn, contact);
	}

	@Test
	public void testDoesNotDeleteAnythingWhenFirstUpdateIsDelivered()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId contactGroupId = new GroupId(getRandomId());
		Message message = getMessage(contactGroupId);
		Metadata meta = new Metadata();
		BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 2),
				new BdfEntry(MSG_KEY_LOCAL, false)
		);
		Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<>();

		MessageId barUpdateId = new MessageId(getRandomId());
		messageMetadata.put(barUpdateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "bar"),
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, false)
		));

		MessageId localUpdateId = new MessageId(getRandomId());
		messageMetadata.put(localUpdateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		));

		context.checking(new Expectations() {{
			oneOf(metadataParser).parse(meta);
			will(returnValue(metaDictionary));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroupId);
			will(returnValue(messageMetadata));
		}});

		TransportPropertyManagerImpl t = createInstance();
		assertEquals(ACCEPT_DO_NOT_SHARE,
				t.incomingMessage(txn, message, meta));
		assertTrue(hasEvent(txn, RemoteTransportPropertiesUpdatedEvent.class));
	}

	@Test
	public void testDeletesOlderUpdatesWhenUpdateIsDelivered()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId contactGroupId = new GroupId(getRandomId());
		Message message = getMessage(contactGroupId);
		Metadata meta = new Metadata();

		BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 4),
				new BdfEntry(MSG_KEY_LOCAL, false)
		);
		Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<>();

		MessageId fooVersion3 = new MessageId(getRandomId());
		messageMetadata.put(fooVersion3, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 3),
				new BdfEntry(MSG_KEY_LOCAL, false)
		));

		context.checking(new Expectations() {{
			oneOf(metadataParser).parse(meta);
			will(returnValue(metaDictionary));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroupId);
			will(returnValue(messageMetadata));

			oneOf(db).deleteMessage(txn, fooVersion3);
			oneOf(db).deleteMessageMetadata(txn, fooVersion3);
		}});

		TransportPropertyManagerImpl t = createInstance();
		assertEquals(ACCEPT_DO_NOT_SHARE,
				t.incomingMessage(txn, message, meta));
		assertTrue(hasEvent(txn, RemoteTransportPropertiesUpdatedEvent.class));
	}

	@Test
	public void testDeletesObsoleteUpdateWhenDelivered() throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId contactGroupId = new GroupId(getRandomId());
		Message message = getMessage(contactGroupId);
		Metadata meta = new Metadata();

		BdfDictionary metaDictionary = BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 3),
				new BdfEntry(MSG_KEY_LOCAL, false)
		);
		Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<>();

		MessageId fooVersion4 = new MessageId(getRandomId());
		messageMetadata.put(fooVersion4, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 4),
				new BdfEntry(MSG_KEY_LOCAL, false)
		));

		context.checking(new Expectations() {{
			oneOf(metadataParser).parse(meta);
			will(returnValue(metaDictionary));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroupId);
			will(returnValue(messageMetadata));

			oneOf(db).deleteMessage(txn, message.getId());
			oneOf(db).deleteMessageMetadata(txn, message.getId());

		}});

		TransportPropertyManagerImpl t = createInstance();
		assertEquals(ACCEPT_DO_NOT_SHARE,
				t.incomingMessage(txn, message, meta));
		assertFalse(hasEvent(txn, RemoteTransportPropertiesUpdatedEvent.class));
	}

	@Test
	public void testStoresRemotePropertiesWithVersion0() throws Exception {
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		Transaction txn = new Transaction(null, false);
		Map<TransportId, TransportProperties> properties =
				new LinkedHashMap<>();
		properties.put(new TransportId("foo"), fooProperties);
		properties.put(new TransportId("bar"), barProperties);

		context.checking(new Expectations() {{
			oneOf(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
		}});
		expectStoreMessage(txn, contactGroup.getId(), "foo", fooPropertiesDict,
				0, false, false);
		expectStoreMessage(txn, contactGroup.getId(), "bar", barPropertiesDict,
				0, false, false);

		TransportPropertyManagerImpl t = createInstance();
		t.addRemoteProperties(txn, contact.getId(), properties);
	}

	@Test
	public void testReturnsLatestLocalProperties() throws Exception {
		Transaction txn = new Transaction(null, true);

		expectGetLocalProperties(txn);

		TransportPropertyManagerImpl t = createInstance();
		Map<TransportId, TransportProperties> local = t.getLocalProperties(txn);
		assertEquals(2, local.size());
		assertEquals(fooProperties, local.get(new TransportId("foo")));
		assertEquals(barProperties, local.get(new TransportId("bar")));
	}

	@Test
	public void testReturnsEmptyPropertiesIfNoLocalPropertiesAreFound()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<>();

		MessageId barUpdateId = new MessageId(getRandomId());
		messageMetadata.put(barUpdateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "bar"),
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		));

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(messageMetadata));
		}});

		TransportPropertyManagerImpl t = createInstance();
		assertEquals(0, t.getLocalProperties(new TransportId("foo")).size());
	}

	@Test
	public void testReturnsLocalProperties() throws Exception {
		Transaction txn = new Transaction(null, true);
		Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<>();

		MessageId barUpdateId = new MessageId(getRandomId());
		messageMetadata.put(barUpdateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "bar"),
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		));

		MessageId fooUpdateId = new MessageId(getRandomId());
		messageMetadata.put(fooUpdateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		));
		BdfList fooUpdate = BdfList.of("foo", 1, fooPropertiesDict);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, fooUpdateId, false);
			will(returnValue(fooUpdate));
			oneOf(clientHelper).parseAndValidateTransportProperties(
					fooPropertiesDict);
			will(returnValue(fooProperties));
		}});

		TransportPropertyManagerImpl t = createInstance();
		assertEquals(fooProperties,
				t.getLocalProperties(new TransportId("foo")));
	}

	@Test
	public void testReturnsRemotePropertiesOrEmptyProperties()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Contact contact1 = getContact();
		Contact contact2 = getContact();
		List<Contact> contacts = asList(contact1, contact2);
		Group contactGroup1 = getGroup(CLIENT_ID, MAJOR_VERSION);
		Group contactGroup2 = getGroup(CLIENT_ID, MAJOR_VERSION);
		Map<MessageId, BdfDictionary> messageMetadata =
				new LinkedHashMap<>();

		MessageId barUpdateId = new MessageId(getRandomId());
		messageMetadata.put(barUpdateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "bar"),
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, false)
		));

		MessageId localUpdateId = new MessageId(getRandomId());
		messageMetadata.put(localUpdateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		));

		MessageId fooUpdateId = new MessageId(getRandomId());
		messageMetadata.put(fooUpdateId, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, false)
		));
		BdfList fooUpdate = BdfList.of("foo", 1, fooPropertiesDict);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getContacts(txn);
			will(returnValue(contacts));

			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact1);
			will(returnValue(contactGroup1));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup1.getId());
			will(returnValue(emptyMap()));
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn,
					contactGroup1.getId());
			will(returnValue(new BdfDictionary()));

			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact2);
			will(returnValue(contactGroup2));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup2.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, fooUpdateId, false);
			will(returnValue(fooUpdate));
			oneOf(clientHelper).parseAndValidateTransportProperties(
					fooPropertiesDict);
			will(returnValue(fooProperties));
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn,
					contactGroup2.getId());
			will(returnValue(new BdfDictionary()));
		}});

		TransportPropertyManagerImpl t = createInstance();
		Map<ContactId, TransportProperties> properties =
				t.getRemoteProperties(new TransportId("foo"));
		assertEquals(2, properties.size());
		assertEquals(0, properties.get(contact1.getId()).size());
		assertEquals(fooProperties, properties.get(contact2.getId()));
	}

	@Test
	public void testReceivePropertiesOverrideDiscoveredProperties()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Contact contact = getContact();
		List<Contact> contacts = singletonList(contact);
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		MessageId updateId = new MessageId(getRandomId());
		Map<MessageId, BdfDictionary> messageMetadata = singletonMap(updateId,
				BdfDictionary.of(
						new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
						new BdfEntry(MSG_KEY_VERSION, 1),
						new BdfEntry(MSG_KEY_LOCAL, false)
				));
		BdfList update = BdfList.of("foo", 1, fooPropertiesDict);
		TransportProperties discovered = new TransportProperties();
		discovered.put("fooKey1", "overridden");
		discovered.put("fooKey3", "fooValue3");
		BdfDictionary discoveredDict = new BdfDictionary(discovered);
		BdfDictionary groupMeta = BdfDictionary.of(
				new BdfEntry(GROUP_KEY_DISCOVERED, discoveredDict)
		);
		TransportProperties merged = new TransportProperties();
		merged.putAll(fooProperties);
		merged.put("fooKey3", "fooValue3");

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getContacts(txn);
			will(returnValue(contacts));

			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, updateId, false);
			will(returnValue(update));
			oneOf(clientHelper).parseAndValidateTransportProperties(
					fooPropertiesDict);
			will(returnValue(fooProperties));
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(groupMeta));
			oneOf(clientHelper).parseAndValidateTransportProperties(
					discoveredDict);
			will(returnValue(discovered));
		}});

		TransportPropertyManagerImpl t = createInstance();
		Map<ContactId, TransportProperties> properties =
				t.getRemoteProperties(new TransportId("foo"));
		assertEquals(merged, properties.get(contact.getId()));
	}

	@Test
	public void testMergingUnchangedPropertiesDoesNotCreateUpdate()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		MessageId updateId = new MessageId(getRandomId());
		Map<MessageId, BdfDictionary> messageMetadata = singletonMap(updateId,
				BdfDictionary.of(
						new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
						new BdfEntry(MSG_KEY_VERSION, 1),
						new BdfEntry(MSG_KEY_LOCAL, true)
				));
		BdfList update = BdfList.of("foo", 1, fooPropertiesDict);

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(txn));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(messageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, updateId, false);
			will(returnValue(update));
			oneOf(clientHelper).parseAndValidateTransportProperties(
					fooPropertiesDict);
			will(returnValue(fooProperties));

		}});

		TransportPropertyManagerImpl t = createInstance();
		t.mergeLocalProperties(new TransportId("foo"), fooProperties);
	}

	@Test
	public void testMergingNewPropertiesCreatesUpdate() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);

		TransportProperties properties = new TransportProperties(fooProperties);
		properties.put("fooKey3", "");

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(txn));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(emptyMap()));

			expectStoreMessage(txn, localGroup.getId(), "foo",
					fooPropertiesDict, 1, true, false);

			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(emptyMap()));
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(new BdfDictionary()));
			expectStoreMessage(txn, contactGroup.getId(), "foo",
					fooPropertiesDict, 1, true, true);
		}});

		TransportPropertyManagerImpl t = createInstance();
		t.mergeLocalProperties(new TransportId("foo"), properties);
	}

	@Test
	public void testMergingNewPropertiesCreatesUpdateWithReflectedProperties()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		BdfDictionary contactGroupMeta = BdfDictionary.of(
				new BdfEntry(GROUP_KEY_DISCOVERED, discoveredPropertiesDict)
		);

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(txn));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(emptyMap()));

			expectStoreMessage(txn, localGroup.getId(), "foo",
					fooPropertiesDict, 1, true, false);

			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(emptyMap()));
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(contactGroupMeta));

			oneOf(clientHelper).parseAndValidateTransportProperties(
					discoveredPropertiesDict);
			will(returnValue(discoveredProperties));
			expectStoreMessage(txn, contactGroup.getId(), "foo",
					mergedPropertiesDict, 1, true, true);
		}});

		TransportPropertyManagerImpl t = createInstance();
		t.mergeLocalProperties(new TransportId("foo"), fooProperties);
	}

	@Test
	public void testMergingUpdatedPropertiesCreatesUpdate() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		BdfDictionary oldMetadata = BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		);
		MessageId localGroupUpdateId = new MessageId(getRandomId());
		Map<MessageId, BdfDictionary> localGroupMessageMetadata =
				singletonMap(localGroupUpdateId, oldMetadata);

		MessageId contactGroupUpdateId = new MessageId(getRandomId());
		Map<MessageId, BdfDictionary> contactGroupMessageMetadata =
				singletonMap(contactGroupUpdateId, oldMetadata);

		TransportProperties oldProperties = new TransportProperties();
		oldProperties.put("fooKey1", "oldFooValue1");
		oldProperties.put("fooKey3", "oldFooValue3");
		BdfDictionary oldPropertiesDict = BdfDictionary.of(
				new BdfEntry("fooKey1", "oldFooValue1"),
				new BdfEntry("fooKey3", "oldFooValue3")
		);
		BdfList oldUpdate = BdfList.of("foo", 1, oldPropertiesDict);

		TransportProperties properties = new TransportProperties(fooProperties);
		properties.put("fooKey3", "");

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(txn));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(localGroupMessageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, localGroupUpdateId,
					false);
			will(returnValue(oldUpdate));
			oneOf(clientHelper).parseAndValidateTransportProperties(
					oldPropertiesDict);
			will(returnValue(oldProperties));

			expectStoreMessage(txn, localGroup.getId(), "foo",
					fooPropertiesDict, 2, true, false);

			oneOf(db).removeMessage(txn, localGroupUpdateId);

			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(contactGroupMessageMetadata));
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(new BdfDictionary()));
			expectStoreMessage(txn, contactGroup.getId(), "foo",
					fooPropertiesDict, 2, true, true);

			oneOf(db).removeMessage(txn, contactGroupUpdateId);
		}});

		TransportPropertyManagerImpl t = createInstance();
		t.mergeLocalProperties(new TransportId("foo"), properties);
	}

	@Test
	public void testMergingUpdatedPropertiesCreatesUpdateWithReflectedProperties()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
		BdfDictionary contactGroupMeta = BdfDictionary.of(
				new BdfEntry(GROUP_KEY_DISCOVERED, discoveredPropertiesDict)
		);
		BdfDictionary oldMetadata = BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 1),
				new BdfEntry(MSG_KEY_LOCAL, true)
		);
		MessageId localGroupUpdateId = new MessageId(getRandomId());
		Map<MessageId, BdfDictionary> localGroupMessageMetadata =
				singletonMap(localGroupUpdateId, oldMetadata);
		MessageId contactGroupUpdateId = new MessageId(getRandomId());
		Map<MessageId, BdfDictionary> contactGroupMessageMetadata =
				singletonMap(contactGroupUpdateId, oldMetadata);
		TransportProperties oldProperties = new TransportProperties();
		oldProperties.put("fooKey1", "oldFooValue1");
		BdfDictionary oldPropertiesDict = BdfDictionary.of(
				new BdfEntry("fooKey1", "oldFooValue1")
		);
		BdfList oldUpdate = BdfList.of("foo", 1, oldPropertiesDict);

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(txn));

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(localGroupMessageMetadata));
			oneOf(clientHelper).getMessageAsList(txn, localGroupUpdateId,
					false);
			will(returnValue(oldUpdate));
			oneOf(clientHelper).parseAndValidateTransportProperties(
					oldPropertiesDict);
			will(returnValue(oldProperties));

			expectStoreMessage(txn, localGroup.getId(), "foo",
					fooPropertiesDict, 2, true, false);

			oneOf(db).removeMessage(txn, localGroupUpdateId);

			oneOf(db).getContacts(txn);
			will(returnValue(singletonList(contact)));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(contactGroup));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(contactGroupMessageMetadata));
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn,
					contactGroup.getId());
			will(returnValue(contactGroupMeta));

			oneOf(clientHelper).parseAndValidateTransportProperties(
					discoveredPropertiesDict);
			will(returnValue(discoveredProperties));
			expectStoreMessage(txn, contactGroup.getId(), "foo",
					mergedPropertiesDict, 2, true, true);

			oneOf(db).removeMessage(txn, contactGroupUpdateId);
		}});

		TransportPropertyManagerImpl t = createInstance();
		t.mergeLocalProperties(new TransportId("foo"), fooProperties);
	}

	private void expectGetLocalProperties(Transaction txn) throws Exception {
		Map<MessageId, BdfDictionary> messageMetadata = new LinkedHashMap<>();

		MessageId fooVersion999 = new MessageId(getRandomId());
		messageMetadata.put(fooVersion999, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "foo"),
				new BdfEntry(MSG_KEY_VERSION, 999)
		));

		MessageId barVersion3 = new MessageId(getRandomId());
		messageMetadata.put(barVersion3, BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, "bar"),
				new BdfEntry(MSG_KEY_VERSION, 3)
		));
		BdfList fooUpdate = BdfList.of("foo", 999, fooPropertiesDict);
		BdfList barUpdate = BdfList.of("bar", 3, barPropertiesDict);

		context.checking(new Expectations() {{

			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					localGroup.getId());
			will(returnValue(messageMetadata));

			oneOf(clientHelper).getMessageAsList(txn, fooVersion999, false);
			will(returnValue(fooUpdate));
			oneOf(clientHelper).parseAndValidateTransportProperties(
					fooPropertiesDict);
			will(returnValue(fooProperties));
			oneOf(clientHelper).getMessageAsList(txn, barVersion3, false);
			will(returnValue(barUpdate));
			oneOf(clientHelper).parseAndValidateTransportProperties(
					barPropertiesDict);
			will(returnValue(barProperties));
		}});
	}

	private void expectStoreMessage(Transaction txn, GroupId g,
			String transportId, BdfDictionary properties, long version,
			boolean local, boolean shared) throws Exception {
		BdfList body = BdfList.of(transportId, version, properties);
		Message message = getMessage(g);
		long timestamp = message.getTimestamp();
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, transportId),
				new BdfEntry(MSG_KEY_VERSION, version),
				new BdfEntry(MSG_KEY_LOCAL, local)
		);

		context.checking(new Expectations() {{
			oneOf(clock).currentTimeMillis();
			will(returnValue(timestamp));
			oneOf(clientHelper).createMessage(g, timestamp, body);
			will(returnValue(message));
			oneOf(clientHelper).addLocalMessage(txn, message, meta, shared,
					false);
		}});
	}
}
