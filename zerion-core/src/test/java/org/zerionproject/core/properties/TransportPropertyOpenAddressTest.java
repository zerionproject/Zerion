package org.zerionproject.core.properties;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.zerionproject.core.test.PermissiveOnionClientAuth;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.singletonMap;
import static org.zerionproject.core.api.plugin.TorConstants.PROP_ONION_V3;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_LOCAL;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_TRANSPORT_ID;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_VERSION;
import static org.zerionproject.core.api.properties.TransportPropertyManager.CLIENT_ID;
import static org.zerionproject.core.api.properties.TransportPropertyManager.MAJOR_VERSION;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.junit.Assert.assertEquals;

public class TransportPropertyOpenAddressTest extends BrambleMockTestCase {

	private static final String OPEN_ONION =
			"ru3bfvgi52cq7zgscrqw6nhcaga5thrvrnnb23tefpdv7hxwrrhfl6yd";
	private static final String AUTHORIZED_ONION =
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
	private final Clock clock = new Clock() {
		@Override
		public long currentTimeMillis() {
			return 1_700_000_000_000L;
		}

		@Override
		public void sleep(long milliseconds) {
		}
	};
	private final Map<String, Settings> stored = new HashMap<>();
	private final SettingsManager settings = new SettingsManager() {
		@Override
		public Settings getSettings(String namespace) {
			Settings s = new Settings();
			Settings kept = stored.get(namespace);
			if (kept != null) s.putAll(kept);
			return s;
		}

		@Override
		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		@Override
		public void mergeSettings(Settings s, String namespace) {
			stored.computeIfAbsent(namespace, k -> new Settings()).putAll(s);
		}

		@Override
		public void mergeSettings(Transaction txn, Settings s,
				String namespace) {
			mergeSettings(s, namespace);
		}
	};
	private final B4OnionRotation b4 =
			new B4OnionRotation(db, settings, accountManager, clock);
	private final Contact contact = getContact(new ContactId(4), getAuthor(),
			new AuthorId(getRandomId()), true);
	private final Group localGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Group group = getGroup(CLIENT_ID, MAJOR_VERSION);

	private TransportProperties open() {
		TransportProperties p = new TransportProperties();
		p.put(PROP_ONION_V3, OPEN_ONION);
		return p;
	}

	@Test
	public void theOpenAddressLeavesOutOnlyTheAuthorizedAddress()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		MessageId update = new MessageId(getRandomId());
		Map<MessageId, BdfDictionary> metadata = singletonMap(update,
				BdfDictionary.of(
						new BdfEntry(MSG_KEY_TRANSPORT_ID,
								TorConstants.ID.getString()),
						new BdfEntry(MSG_KEY_VERSION, 1),
						new BdfEntry(MSG_KEY_LOCAL, false)));
		BdfDictionary dict = new BdfDictionary(open());
		BdfList body = BdfList.of(TorConstants.ID.getString(), 1, dict);
		context.checking(new DbExpectations() {{
			allowing(accountManager).getDatabaseKey();
			will(returnValue(fieldKey));
			oneOf(contactGroupFactory).createLocalGroup(CLIENT_ID,
					MAJOR_VERSION);
			will(returnValue(localGroup));
			allowing(db).transactionWithResult(with(true),
					withDbCallable(txn));
			allowing(db).getContact(txn, contact.getId());
			will(returnValue(contact));
			allowing(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(group));
			allowing(clientHelper).getMessageMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(metadata));
			allowing(clientHelper).getMessageAsList(txn, update, false);
			will(returnValue(body));
			allowing(clientHelper).parseAndValidateTransportProperties(dict);
			will(onConsecutiveCalls(returnValue(open()), returnValue(open())));
			allowing(clientHelper).getGroupMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(new BdfDictionary()));
		}});
		TransportPropertyManagerImpl t = new TransportPropertyManagerImpl(db,
				clientHelper, clientVersioningManager, metadataParser,
				contactGroupFactory, clock, b4,
				() -> new PermissiveOnionClientAuth() {
					@Override
					public String getDialOnion(ContactId c) {
						return AUTHORIZED_ONION;
					}
				});
		assertEquals(AUTHORIZED_ONION, t.getRemoteProperties(contact.getId(),
				TorConstants.ID).get(PROP_ONION_V3));
		assertEquals(OPEN_ONION, t.getOpenRemoteProperties(contact.getId(),
				TorConstants.ID).get(PROP_ONION_V3));
	}
}
