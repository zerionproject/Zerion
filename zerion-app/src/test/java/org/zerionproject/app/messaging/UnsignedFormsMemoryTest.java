package org.zerionproject.app.messaging;

import org.jmock.Expectations;
import org.junit.Test;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.messaging.UnsignedFormsMemory.Form;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.versioning.ClientVersion;
import org.zerionproject.core.test.BrambleMockTestCase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.zerionproject.app.messaging.UnsignedFormsMemory.NAMESPACE;
import static org.zerionproject.app.messaging.UnsignedFormsMemory.UNSIGNED_ONLY;

public class UnsignedFormsMemoryTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final SettingsManager settingsManager =
			context.mock(SettingsManager.class);
	private final ContactManager contactManager =
			context.mock(ContactManager.class);
	private final MessagingManager messagingManager =
			context.mock(MessagingManager.class);
	private final Transaction txn = new Transaction(null, false);
	private final ContactId contactId = new ContactId(7);
	private final UnsignedFormsMemory memory = new UnsignedFormsMemory(db,
			settingsManager, contactManager, messagingManager);

	@Test
	public void version17IsStoredInTheTransactionThatStoresTheUpdate()
			throws Exception {
		Settings expected = new Settings();
		expected.putBoolean(UNSIGNED_ONLY + contactId.getInt(), true);
		context.checking(new Expectations() {{
			oneOf(settingsManager).mergeSettings(txn, expected, NAMESPACE);
		}});

		memory.remoteVersionStored(txn, contactId, messaging(
				UnsignedFormsMemory.MIN_VERSION));
	}

	@Test
	public void aFailedWriteFailsTheTransaction() throws Exception {
		context.checking(new Expectations() {{
			oneOf(settingsManager).mergeSettings(with(txn),
					with(any(Settings.class)), with(NAMESPACE));
			will(throwException(new DbException()));
		}});

		try {
			memory.remoteVersionStored(txn, contactId, messaging(
					UnsignedFormsMemory.MIN_VERSION));
			fail("the failure was swallowed");
		} catch (DbException expected) {
		}
	}

	@Test
	public void olderVersionsAndOtherClientsAreNotStored() throws Exception {
		memory.remoteVersionStored(txn, contactId, messaging(
				UnsignedFormsMemory.MIN_VERSION - 1));
		memory.remoteVersionStored(txn, contactId, new ClientVersion(
				new ClientId("org.zerionproject.other"),
				MessagingManager.MAJOR_VERSION,
				UnsignedFormsMemory.MIN_VERSION));
		memory.remoteVersionStored(txn, contactId, new ClientVersion(
				MessagingManager.CLIENT_ID, MessagingManager.MAJOR_VERSION + 1,
				UnsignedFormsMemory.MIN_VERSION));
	}

	@Test
	public void theFormFollowsTheStoredFlagAndTheKnownVersion()
			throws Exception {
		assertEquals(Form.UNKNOWN, formWith(false, -1));
		assertEquals(Form.SIGNED, formWith(false, 0));
		assertEquals(Form.SIGNED, formWith(false, 13));
		assertEquals(Form.SIGNED, formWith(false,
				UnsignedFormsMemory.MIN_VERSION - 1));
		assertEquals(Form.UNSIGNED, formWith(false,
				UnsignedFormsMemory.MIN_VERSION));
		assertEquals(Form.UNSIGNED, formWith(true, -1));
		assertEquals(Form.UNSIGNED, formWith(true, 13));
	}

	private Form formWith(boolean held, int minor) throws Exception {
		Settings s = new Settings();
		if (held) s.putBoolean(UNSIGNED_ONLY + contactId.getInt(), true);
		context.checking(new Expectations() {{
			oneOf(settingsManager).getSettings(txn, NAMESPACE);
			will(returnValue(s));
		}});
		if (!held) {
			context.checking(new Expectations() {{
				oneOf(messagingManager).getContactClientMinorVersion(txn,
						contactId);
				will(returnValue(minor));
			}});
		}
		return memory.form(txn, contactId);
	}

	private static ClientVersion messaging(int minor) {
		return new ClientVersion(MessagingManager.CLIENT_ID,
				MessagingManager.MAJOR_VERSION, minor);
	}
}
