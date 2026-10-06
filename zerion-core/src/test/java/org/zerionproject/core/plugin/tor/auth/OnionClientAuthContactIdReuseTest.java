package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.CommitAction;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.EventAction;
import org.zerionproject.core.api.db.TaskAction;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.zerionproject.core.test.TestUtils;
import org.jmock.Expectations;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OnionClientAuthContactIdReuseTest extends BrambleMockTestCase {

	private static final String PEER_ONION =
			"ru3bfvgi52cq7zgscrqw6nhcaga5thrvrnnb23tefpdv7hxwrrhfl6yd";
	private static final ContactId REUSED = new ContactId(2);

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final MetadataParser metadataParser =
			context.mock(MetadataParser.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final TaskScheduler scheduler = context.mock(TaskScheduler.class);
	private final InMemorySettingsManager settings =
			new InMemorySettingsManager();
	private final OnionAuthStore store = new OnionAuthStore(settings);
	private final AuthorId local = new AuthorId(getRandomId());
	private final Contact removed = getContact(REUSED, getAuthor(), local, true);
	private final Contact next = getContact(REUSED, getAuthor(), local, true);
	private final Group group = TestUtils.getGroup(OnionAuthRecords.CLIENT_ID,
			OnionAuthRecords.MAJOR_VERSION);

	private OnionClientAuthManagerImpl manager;

	@Before
	public void setUp() throws Exception {
		manager = new OnionClientAuthManagerImpl(db, clientHelper,
				contactGroupFactory, metadataParser, store, crypto,
				() -> null, eventBus, new Clock() {
					@Override
					public long currentTimeMillis() {
						return 1_000_000L;
					}

					@Override
					public void sleep(long milliseconds) {
					}
				}, Runnable::run, scheduler);
		OnionAuthRecord r = new OnionAuthRecord(REUSED);
		r.state = State.AUTH_REQUIRED;
		r.peerOnion = PEER_ONION;
		r.dialPrivateKey = bytes(1);
		r.dialPublicKey = bytes(2);
		r.peerPublicKey = bytes(3);
		r.commitSent = true;
		r.peerCommitReceived = true;
		store.save(null, r);
	}

	private void removeTheCommittedContact() throws Exception {
		Transaction revocationTxn = new Transaction(null, false);
		context.checking(new DbExpectations() {{
			oneOf(contactGroupFactory).createContactGroup(
					OnionAuthRecords.CLIENT_ID, OnionAuthRecords.MAJOR_VERSION,
					removed);
			will(returnValue(group));
			oneOf(db).containsGroup(with(any(Transaction.class)),
					with(group.getId()));
			will(returnValue(true));
			oneOf(db).removeGroup(with(any(Transaction.class)), with(group));
			oneOf(db).transaction(with(false), withDbRunnable(revocationTxn));
		}});
		Transaction txn = new Transaction(null, false);
		manager.removingContact(txn, removed);
		assertTrue("the revocation rotation is recorded with the removal,"
				+ " before any work runs", store.loadService(null)
				.revocationPending);
		commit(txn);
		assertEquals(State.REVOKED, store.load(null, REUSED).state);
		assertFalse(manager.acceptsInbound(REUSED, false));
		assertFalse(manager.acceptsInbound(REUSED, true));
		assertTrue(store.loadService(null).revocationPending);
	}

	@Test
	public void theNextContactGivenTheIdStartsAtLegacy() throws Exception {
		removeTheCommittedContact();
		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createContactGroup(
					with(OnionAuthRecords.CLIENT_ID),
					with(OnionAuthRecords.MAJOR_VERSION),
					with(any(Contact.class)));
			will(returnValue(group));
			oneOf(db).containsGroup(with(any(Transaction.class)),
					with(group.getId()));
			will(returnValue(true));
			oneOf(clientHelper).setContactId(with(any(Transaction.class)),
					with(any(GroupId.class)), with(REUSED));
		}});
		Transaction txn = new Transaction(null, false);
		manager.addingContact(txn, next);
		commit(txn);
		assertTrue("the next contact is not refused as revoked",
				manager.acceptsInbound(REUSED, false));
		assertEquals(State.LEGACY, store.load(null, REUSED).state);
		assertNull(manager.getDialOnion(REUSED));
		assertNull(store.load(null, REUSED).peerPublicKey);
		assertTrue("the removed contact's revocation rotation is kept",
				store.loadService(null).revocationPending);
	}

	@Test
	public void theRemovedContactStaysRevokedUntilTheIdIsGivenOut()
			throws Exception {
		removeTheCommittedContact();
		assertEquals(State.REVOKED, store.load(null, REUSED).state);
		assertFalse(manager.acceptsInbound(REUSED, false));
		assertNull(manager.getDialOnion(REUSED));
	}

	@Test
	public void theRevokedMarkKeepsNothingOfTheRemovedContact()
			throws Exception {
		removeTheCommittedContact();
		OnionAuthRecord kept = store.load(null, REUSED);
		assertEquals(State.REVOKED, kept.state);
		assertNull("no onion address of the removed contact is kept",
				kept.peerOnion);
		assertNull(kept.dialPrivateKey);
		assertNull(kept.dialPublicKey);
		assertNull(kept.peerPublicKey);
		assertFalse(kept.commitSent);
		assertFalse(kept.peerCommitReceived);
	}

	private static byte[] bytes(int fill) {
		byte[] b = new byte[32];
		Arrays.fill(b, (byte) fill);
		return b;
	}

	private static void commit(Transaction txn) {
		for (CommitAction a : txn.getActions()) {
			a.accept(new CommitAction.Visitor() {
				@Override
				public void visit(EventAction eventAction) {
				}

				@Override
				public void visit(TaskAction taskAction) {
					taskAction.getTask().run();
				}
			});
		}
	}
}
