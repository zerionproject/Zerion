package org.zerionproject.core.versioning;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.zerionproject.core.api.contact.B3Constants.B3_PEER_MESSAGING_MINOR_KEY_PREFIX;
import static org.zerionproject.core.api.contact.B3Constants.B3_SETTINGS_NAMESPACE;
import static org.zerionproject.core.api.contact.B3Constants.B3_SLOT_PRESENT_KEY_PREFIX;
import static org.zerionproject.core.api.contact.B3Constants.B3_STRICT_REJECT_KEY_PREFIX;
import static org.zerionproject.core.api.versioning.ClientVersioningManager.CLIENT_ID;
import static org.zerionproject.core.api.versioning.ClientVersioningManager.MAJOR_VERSION;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClientVersioningContactRemovalTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final Clock clock = context.mock(Clock.class);
	private final InMemorySettings settings = new InMemorySettings();
	private final AuthorId local = new AuthorId(getRandomId());
	private final Contact removed =
			getContact(new ContactId(2), getAuthor(), local, true);
	private final Group localGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Group contactGroup = getGroup(CLIENT_ID, MAJOR_VERSION);

	@Test
	public void theRemovedContactsFlagsAreNotInherited() throws Exception {
		Settings flags = new Settings();
		flags.put(B3_SLOT_PRESENT_KEY_PREFIX + 2, "0");
		flags.put(B3_PEER_MESSAGING_MINOR_KEY_PREFIX + 2, "5");
		flags.put(B3_STRICT_REJECT_KEY_PREFIX + 2, "1");
		flags.put(B3_SLOT_PRESENT_KEY_PREFIX + 3, "1");
		flags.put(B3_PEER_MESSAGING_MINOR_KEY_PREFIX + 3, "5");
		settings.mergeSettings(flags, B3_SETTINGS_NAMESPACE);
		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createLocalGroup(CLIENT_ID,
					MAJOR_VERSION);
			will(returnValue(localGroup));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, removed);
			will(returnValue(contactGroup));
			oneOf(db).removeGroup(with(any(Transaction.class)),
					with(contactGroup));
		}});
		ClientVersioningManagerImpl c = new ClientVersioningManagerImpl(db,
				clientHelper, contactGroupFactory, clock, settings);
		c.removingContact(new Transaction(null, false), removed);

		Settings after = settings.getSettings(B3_SETTINGS_NAMESPACE);
		assertTrue("the removed contact's slot flag is cleared",
				isEmpty(after.get(B3_SLOT_PRESENT_KEY_PREFIX + 2)));
		assertTrue(isEmpty(after.get(B3_PEER_MESSAGING_MINOR_KEY_PREFIX + 2)));
		assertTrue(isEmpty(after.get(B3_STRICT_REJECT_KEY_PREFIX + 2)));
		assertEquals("1", after.get(B3_SLOT_PRESENT_KEY_PREFIX + 3));
		assertEquals("5", after.get(B3_PEER_MESSAGING_MINOR_KEY_PREFIX + 3));
		assertNull(after.get(B3_STRICT_REJECT_KEY_PREFIX + 3));
	}

	private static boolean isEmpty(String value) {
		return value == null || value.isEmpty();
	}

	private static class InMemorySettings implements SettingsManager {

		private final Map<String, Settings> byNamespace = new HashMap<>();

		@Override
		public synchronized Settings getSettings(String namespace) {
			Settings s = new Settings();
			Settings stored = byNamespace.get(namespace);
			if (stored != null) s.putAll(stored);
			return s;
		}

		@Override
		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		@Override
		public synchronized void mergeSettings(Settings s, String namespace) {
			Settings stored = byNamespace.get(namespace);
			if (stored == null) {
				stored = new Settings();
				byNamespace.put(namespace, stored);
			}
			stored.putAll(s);
		}

		@Override
		public void mergeSettings(Transaction txn, Settings s,
				String namespace) {
			mergeSettings(s, namespace);
		}
	}
}
