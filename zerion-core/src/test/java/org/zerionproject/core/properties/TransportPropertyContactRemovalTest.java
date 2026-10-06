package org.zerionproject.core.properties;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.zerionproject.core.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.zerionproject.core.api.properties.TransportPropertyManager.CLIENT_ID;
import static org.zerionproject.core.api.properties.TransportPropertyManager.MAJOR_VERSION;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TransportPropertyContactRemovalTest extends BrambleMockTestCase {

	private static final String REMOVED_ONION =
			"ru3bfvgi52cq7zgscrqw6nhcaga5thrvrnnb23tefpdv7hxwrrhfl6yd";
	private static final String OTHER_ONION =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwd";

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ClientVersioningManager clientVersioningManager =
			context.mock(ClientVersioningManager.class);
	private final MetadataParser metadataParser =
			context.mock(MetadataParser.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final AccountManager accountManager =
			context.mock(AccountManager.class);
	private final SecretKey fieldKey = getSecretKey();
	private final InMemorySettings settings = new InMemorySettings();
	private final Clock clock = new Clock() {
		@Override
		public long currentTimeMillis() {
			return 1_700_000_000_000L;
		}

		@Override
		public void sleep(long milliseconds) {
		}
	};
	private final B4OnionRotation b4 =
			new B4OnionRotation(db, settings, accountManager, clock);
	private final AuthorId local = new AuthorId(getRandomId());
	private final Contact removed =
			getContact(new ContactId(2), getAuthor(), local, true);
	private final Contact other =
			getContact(new ContactId(3), getAuthor(), local, true);
	private final Group localGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Group removedGroup = getGroup(CLIENT_ID, MAJOR_VERSION);

	@Test
	public void theRemovedContactsAnnouncedOnionIsNotDialledForItsId()
			throws Exception {
		context.checking(new Expectations() {{
			allowing(accountManager).getDatabaseKey();
			will(returnValue(fieldKey));
			oneOf(contactGroupFactory).createLocalGroup(CLIENT_ID,
					MAJOR_VERSION);
			will(returnValue(localGroup));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, removed);
			will(returnValue(removedGroup));
			oneOf(db).removeGroup(with(any(Transaction.class)),
					with(removedGroup));
		}});
		Transaction txn = new Transaction(null, false);
		b4.onAnnounceReceived(txn, removed.getId(), REMOVED_ONION,
				clock.currentTimeMillis());
		b4.onAnnounceReceived(txn, other.getId(), OTHER_ONION,
				clock.currentTimeMillis());
		assertEquals(REMOVED_ONION,
				b4.getPendingOnionForContact(txn, removed.getId()));

		TransportPropertyManagerImpl t = new TransportPropertyManagerImpl(db,
				clientHelper, clientVersioningManager, metadataParser,
				contactGroupFactory, clock, b4,
				() -> new org.zerionproject.core.test.PermissiveOnionClientAuth());
		t.removingContact(txn, removed);

		assertNull("the next contact given the id is not dialled at the"
						+ " removed contact's onion",
				b4.getPendingOnionForContact(txn, removed.getId()));
		assertEquals(OTHER_ONION,
				b4.getPendingOnionForContact(txn, other.getId()));
		Settings kept = settings.getSettings(txn, B4_SETTINGS_NAMESPACE);
		for (Map.Entry<String, String> e : kept.entrySet()) {
			if (e.getKey().endsWith("." + removed.getId().getInt())) {
				assertEquals(e.getKey(), "", e.getValue());
			}
		}
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
