package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.TestUtils;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class OnionClientAuthOrphanGroupTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final MetadataParser metadataParser =
			context.mock(MetadataParser.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final TaskScheduler scheduler = context.mock(TaskScheduler.class);
	private final OnionAuthStore store =
			new OnionAuthStore(new InMemorySettingsManager());
	private final AuthorId local = new AuthorId(getRandomId());
	private final Contact kept =
			getContact(new ContactId(1), getAuthor(), local, true);
	private final ContactId removedId = new ContactId(2);
	private final Group keptGroup = TestUtils.getGroup(
			OnionAuthRecords.CLIENT_ID, OnionAuthRecords.MAJOR_VERSION);
	private final Group orphanGroup = TestUtils.getGroup(
			OnionAuthRecords.CLIENT_ID, OnionAuthRecords.MAJOR_VERSION);

	@Test
	public void theGroupOfARemovedContactIsRemovedWhenTheDatabaseOpens()
			throws Exception {
		OnionClientAuthManagerImpl manager = new OnionClientAuthManagerImpl(
				db, clientHelper, contactGroupFactory, metadataParser, store,
				crypto, () -> null, eventBus, new Clock() {
					@Override
					public long currentTimeMillis() {
						return 1_000_000L;
					}

					@Override
					public void sleep(long milliseconds) {
					}
				}, Runnable::run, scheduler);
		Transaction txn = new Transaction(null, false);
		context.checking(new Expectations() {{
			oneOf(db).getGroups(txn, OnionAuthRecords.CLIENT_ID,
					OnionAuthRecords.MAJOR_VERSION);
			will(returnValue(Arrays.asList(keptGroup, orphanGroup)));
			oneOf(clientHelper).getContactId(txn, keptGroup.getId());
			will(returnValue(kept.getId()));
			oneOf(db).getContact(txn, kept.getId());
			will(returnValue(kept));
			oneOf(clientHelper).getContactId(txn, orphanGroup.getId());
			will(returnValue(removedId));
			oneOf(db).getContact(txn, removedId);
			will(throwException(new NoSuchContactException()));
			oneOf(db).removeGroup(txn, orphanGroup);
			oneOf(db).getContacts(txn);
			will(returnValue(Collections.singletonList(kept)));
			oneOf(contactGroupFactory).createContactGroup(
					OnionAuthRecords.CLIENT_ID, OnionAuthRecords.MAJOR_VERSION,
					kept);
			will(returnValue(keptGroup));
			oneOf(db).containsGroup(txn, keptGroup.getId());
			will(returnValue(true));
			oneOf(clientHelper).setContactId(txn, keptGroup.getId(),
					kept.getId());
			oneOf(scheduler).scheduleWithFixedDelay(with(any(Runnable.class)),
					with(any(java.util.concurrent.Executor.class)),
					with(any(long.class)), with(any(long.class)),
					with(TimeUnit.MILLISECONDS));
		}});
		manager.onDatabaseOpened(txn);
	}
}
