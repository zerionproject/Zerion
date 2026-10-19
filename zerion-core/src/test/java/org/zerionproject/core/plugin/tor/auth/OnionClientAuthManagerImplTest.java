package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.CommitAction;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.EventAction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.plugin.event.ConnectionOpenedEvent;
import org.zerionproject.core.api.sync.event.MessageAddedEvent;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.TaskAction;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.properties.event.RemoteTransportPropertiesUpdatedEvent;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.crypto.AgreementPrivateKey;
import org.zerionproject.core.api.crypto.AgreementPublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.test.TestUtils;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.jmock.lib.concurrent.Synchroniser;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OnionClientAuthManagerImplTest {

	private static final String PEER_ONION =
			"ru3bfvgi52cq7zgscrqw6nhcaga5thrvrnnb23tefpdv7hxwrrhfl6yd";
	private static final String PEER_ONION_2 =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";

	private final Mockery context = new Mockery() {{
		setThreadingPolicy(new Synchroniser());
	}};
	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final MetadataParser metadataParser =
			context.mock(MetadataParser.class);
	private final TaskScheduler scheduler = context.mock(TaskScheduler.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final TransportPropertyManager transportPropertyManager =
			context.mock(TransportPropertyManager.class);

	private final InMemorySettingsManager settings =
			new InMemorySettingsManager();
	private final OnionAuthStore store = new OnionAuthStore(settings);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final java.security.SecureRandom random =
			new java.security.SecureRandom();
	private final AtomicLong now = new AtomicLong(1_000_000L);
	private final RecordingControl control = new RecordingControl();
	private final List<ContactId> dialed = Collections.synchronizedList(new ArrayList<>());

	private final Contact c1 = contactWeDial(false);
	private final Contact c2 = contactWeDial(true);
	private final Group g1 = TestUtils.getGroup(OnionAuthRecords.CLIENT_ID,
			OnionAuthRecords.MAJOR_VERSION);
	private final Group g2 = TestUtils.getGroup(OnionAuthRecords.CLIENT_ID,
			OnionAuthRecords.MAJOR_VERSION);
	private final Map<MessageId, BdfList> bodies = new HashMap<>();
	private final Map<ContactId, TransportProperties> remote = new HashMap<>();
	private final Map<ContactId, List<OnionAuthRecords.Record>> sent =
			new HashMap<>();

	private OnionClientAuthManagerImpl manager;
	private boolean deferring = false;
	private final List<Runnable> deferred = new ArrayList<>();
	private final java.util.concurrent.atomic.AtomicBoolean skipNew =
			new java.util.concurrent.atomic.AtomicBoolean(false);
	private final java.util.concurrent.atomic.AtomicBoolean holdCommits =
			new java.util.concurrent.atomic.AtomicBoolean(false);
	private final OnionAuthDebugHooks hooks = new OnionAuthDebugHooks() {
		@Override
		public boolean skipsNewNegotiations(Transaction txn) {
			return skipNew.get();
		}

		@Override
		public boolean holdsConfirmations(Transaction txn) {
			return holdCommits.get();
		}
	};
	private final ThreadLocal<Integer> txnDepth =
			ThreadLocal.withInitial(() -> 0);

	private Transaction begin() {
		if (txnDepth.get() > 0) throw new IllegalStateException(
				"nested transaction");
		txnDepth.set(txnDepth.get() + 1);
		return new Transaction(null, false);
	}


	static final class RecordingControl implements OnionServiceControl {

		final List<String> commands = Collections.synchronizedList(
				new ArrayList<>());
		final Map<String, List<byte[]>> keysByOnion = new HashMap<>();
		int minted = 0;
		boolean refuseCredentials = false;
		boolean failPublish = false;

		private String mint() {
			char c = (char) ('a' + (minted++ % 26));
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < 56; i++) b.append(c);
			return b.toString();
		}

		@Override
		public Published publish(@Nullable String privateKey, int localPort,
				int remotePort, Collection<byte[]> clientPublicKeys)
				throws IOException {
			if (failPublish) throw new IOException("publish refused");
			String onion = privateKey == null ? mint()
					: privateKey.substring("KEY:".length());
			keysByOnion.put(onion, new ArrayList<>(clientPublicKeys));
			commands.add("ADD " + onion + " keys=" + clientPublicKeys.size()
					+ " port=" + localPort);
			return new Published(onion, "KEY:" + onion);
		}

		@Override
		public void remove(String onion) {
			keysByOnion.remove(onion);
			commands.add("DEL " + onion);
		}

		@Override
		public void addClientKey(String onion, byte[] clientPrivateKey)
				throws IOException {
			if (onion.length() != 56) throw new IllegalArgumentException("onion");
			if (refuseCredentials) throw new IOException("refused");
			commands.add("CRED " + onion);
		}

		@Override
		public void removeClientKey(String onion) {
			commands.add("UNCRED " + onion);
		}

		boolean authorizes(String onion, byte[] pub) {
			List<byte[]> keys = keysByOnion.get(onion);
			if (keys == null) return false;
			for (byte[] k : keys) if (Arrays.equals(k, pub)) return true;
			return false;
		}
	}

	private static Contact contactWeDial(boolean weDial) {
		byte[] low = new byte[AuthorId.LENGTH];
		byte[] high = new byte[AuthorId.LENGTH];
		Arrays.fill(high, (byte) 0x70);
		Author them = new Author(new AuthorId(weDial ? high : low),
				Author.FORMAT_VERSION, "them",
				TestUtils.getAuthor().getPublicKey());
		return TestUtils.getContact(TestUtils.getContactId(), them,
				new AuthorId(weDial ? low : high), true);
	}

	@Before
	public void setUp() throws Exception {
		context.checking(new Expectations() {{
			allowing(crypto).generateAgreementKeyPair();
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) {
					byte[] priv = new byte[32], pub = new byte[32];
					random.nextBytes(priv);
					random.nextBytes(pub);
					return new KeyPair(new AgreementPublicKey(pub),
							new AgreementPrivateKey(priv));
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("generates a random key pair");
				}
			});
			allowing(db).transaction(with(any(boolean.class)),
					with(any(DbRunnable.class)));
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) throws Throwable {
					Transaction txn = begin();
					try {
						((DbRunnable<?>) invocation.getParameter(1)).run(txn);
					} finally {
						txnDepth.set(txnDepth.get() - 1);
					}
					commit(txn);
					return null;
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("runs the transaction");
				}
			});
			allowing(db).transactionWithResult(with(any(boolean.class)),
					with(any(DbCallable.class)));
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) throws Throwable {
					Transaction txn = begin();
					Object r;
					try {
						r = ((DbCallable<?, ?>) invocation.getParameter(1))
								.call(txn);
					} finally {
						txnDepth.set(txnDepth.get() - 1);
					}
					commit(txn);
					return r;
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("runs the transaction with a result");
				}
			});
			allowing(db).getContacts(with(any(Transaction.class)));
			will(returnValue(Arrays.asList(c1, c2)));
			allowing(db).getContact(with(any(Transaction.class)),
					with(c1.getId()));
			will(returnValue(c1));
			allowing(db).getContact(with(any(Transaction.class)),
					with(c2.getId()));
			will(returnValue(c2));
			allowing(db).containsGroup(with(any(Transaction.class)),
					with(any(GroupId.class)));
			will(returnValue(true));
			allowing(db).getGroups(with(any(Transaction.class)),
					with(OnionAuthRecords.CLIENT_ID),
					with(OnionAuthRecords.MAJOR_VERSION));
			will(returnValue(Arrays.asList(g1, g2)));
			allowing(db).removeGroup(with(any(Transaction.class)),
					with(any(Group.class)));
			allowing(contactGroupFactory).createContactGroup(
					OnionAuthRecords.CLIENT_ID, OnionAuthRecords.MAJOR_VERSION,
					c1);
			will(returnValue(g1));
			allowing(contactGroupFactory).createContactGroup(
					OnionAuthRecords.CLIENT_ID, OnionAuthRecords.MAJOR_VERSION,
					c2);
			will(returnValue(g2));
			allowing(clientHelper).setContactId(with(any(Transaction.class)),
					with(any(GroupId.class)), with(any(ContactId.class)));
			allowing(clientHelper).getContactId(with(any(Transaction.class)),
					with(g1.getId()));
			will(returnValue(c1.getId()));
			allowing(clientHelper).getContactId(with(any(Transaction.class)),
					with(g2.getId()));
			will(returnValue(c2.getId()));
			allowing(clientHelper).createMessage(with(any(GroupId.class)),
					with(any(long.class)), with(any(BdfList.class)));
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) {
					GroupId g = (GroupId) invocation.getParameter(0);
					BdfList body = (BdfList) invocation.getParameter(2);
					Message m = new Message(new MessageId(TestUtils.getRandomId()),
							g, (long) invocation.getParameter(1), new byte[1]);
					bodies.put(m.getId(), body);
					return m;
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("creates a message");
				}
			});
			allowing(clientHelper).addLocalMessage(with(any(Transaction.class)),
					with(any(Message.class)), with(any(BdfDictionary.class)),
					with(any(boolean.class)), with(any(boolean.class)));
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) throws Throwable {
					Message m = (Message) invocation.getParameter(1);
					ContactId c = m.getGroupId().equals(g1.getId())
							? c1.getId() : c2.getId();
					sent.computeIfAbsent(c, k -> new ArrayList<>()).add(
							OnionAuthRecords.parse(bodies.get(m.getId())));
					return null;
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("records a sent message");
				}
			});
			allowing(clientHelper).toList(with(any(Message.class)),
					with(any(boolean.class)));
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) {
					return bodies.get(((Message) invocation.getParameter(0))
							.getId());
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("returns the body");
				}
			});
			allowing(metadataParser).parse(with(any(Metadata.class)));
			will(returnValue(new BdfDictionary()));
			allowing(scheduler).scheduleWithFixedDelay(
					with(any(Runnable.class)),
					with(any(java.util.concurrent.Executor.class)),
					with(any(long.class)), with(any(long.class)),
					with(any(java.util.concurrent.TimeUnit.class)));
			will(returnValue(null));
			allowing(eventBus).broadcast(with(any(
					org.zerionproject.core.api.event.Event.class)));
			allowing(transportPropertyManager).getRemoteProperties(
					TorConstants.ID);
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) {
					Transaction txn = begin();
					txnDepth.set(txnDepth.get() - 1);
					return new HashMap<>(remote);
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("returns the remote properties");
				}
			});
		}});
		manager = new OnionClientAuthManagerImpl(db, clientHelper,
				contactGroupFactory, metadataParser, store, crypto,
				() -> transportPropertyManager, eventBus,
				new org.zerionproject.core.api.system.Clock() {
					@Override
					public long currentTimeMillis() {
						return now.get();
					}

					@Override
					public void sleep(long milliseconds)
							throws InterruptedException {
					}
				},
				this::execute, scheduler, java.util.Optional.of(hooks));
		manager.onDatabaseOpened(new Transaction(null, false));
		manager.attachTor(control, 4444, dialed::add);
	}

	private static void commit(Transaction txn) {
		List<CommitAction> actions = txn.getActions();
		if (actions == null) return;
		for (CommitAction a : actions) {
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

	private void peerAdvertisesSupport(Contact c) {
		TransportProperties p = new TransportProperties();
		p.put(TorConstants.PROP_ONION_AUTH_SUPPORTED, "1");
		remote.put(c.getId(), p);
		manager.eventOccurred(
				new RemoteTransportPropertiesUpdatedEvent(TorConstants.ID));
	}

	private void receive(Contact c, Group g, BdfList body) throws Exception {
		Message m = new Message(new MessageId(TestUtils.getRandomId()),
				g.getId(), now.get(), new byte[1]);
		bodies.put(m.getId(), body);
		Transaction txn = new Transaction(null, false);
		manager.incomingMessage(txn, m, new Metadata());
		commit(txn);
	}

	private OnionAuthRecord record(Contact c) throws Exception {
		return store.load(null, c.getId());
	}

	private OnionAuthStore.ServiceRecord service() throws Exception {
		return store.loadService(null);
	}

	private List<OnionAuthRecords.Record> sentTo(Contact c) {
		return sent.getOrDefault(c.getId(), Collections.emptyList());
	}

	private OnionAuthRecords.Record lastSent(Contact c, int type) {
		OnionAuthRecords.Record found = null;
		for (OnionAuthRecords.Record r : sentTo(c)) if (r.type == type) found = r;
		return found;
	}

	private void execute(Runnable task) {
		if (deferring) deferred.add(task);
		else task.run();
	}

	private boolean published(String onion) {
		return control.keysByOnion.containsKey(onion);
	}

	private byte[] peerPub(int fill) {
		byte[] b = new byte[32];
		Arrays.fill(b, (byte) fill);
		return b;
	}

	private void activate(Contact c, Group g, String peerOnion, byte[] peerPub)
			throws Exception {
		peerAdvertisesSupport(c);
		assertEquals(State.AUTH_NEGOTIATING, record(c).state);
		assertNotNull(lastSent(c, OnionAuthRecords.TYPE_OFFER));
		receive(c, g, OnionAuthRecords.offer(1, peerOnion, peerPub));
		OnionAuthRecord r = record(c);
		assertTrue(control.authorizes(service().onion, peerPub));
		assertTrue(control.commands.contains("CRED " + peerOnion));
		assertNotNull(lastSent(c, OnionAuthRecords.TYPE_READY));
		receive(c, g, OnionAuthRecords.ready(1));
		assertEquals(peerOnion, manager.getDialOnion(c.getId()));
		assertTrue(dialed.contains(c.getId()));
		manager.dialSucceeded(c.getId(), peerOnion);
		assertNotNull(lastSent(c, OnionAuthRecords.TYPE_PROBE_SUCCESS));
		receive(c, g, OnionAuthRecords.probeSuccess(1));
		OnionAuthRecords.Record commit = lastSent(c, OnionAuthRecords.TYPE_COMMIT);
		assertNotNull(commit);
		receive(c, g, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub)));
		assertEquals(State.AUTH_REQUIRED, record(c).state);
	}

	@Test
	public void testActivationEndsAtAuthRequiredWithNoOpenDial()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
		assertFalse(manager.acceptsInbound(c1.getId(), false));
		assertTrue(manager.acceptsInbound(c1.getId(), true));
		assertNull(manager.getDialOnion(c2.getId()));
		assertTrue(manager.acceptsInbound(c2.getId(), false));
	}

	@Test
	public void testAnUnansweredOfferIsSentAgainWhenTheDatabaseOpens()
			throws Exception {
		peerAdvertisesSupport(c1);
		assertEquals(1, countSent(c1, OnionAuthRecords.TYPE_OFFER));
		byte[] firstKey = record(c1).dialPublicKey;
		manager.onDatabaseOpened(new Transaction(null, false));
		assertEquals(2, countSent(c1, OnionAuthRecords.TYPE_OFFER));
		assertArrayEquals(firstKey,
				lastSent(c1, OnionAuthRecords.TYPE_OFFER).publicKey);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		int answered = countSent(c1, OnionAuthRecords.TYPE_OFFER);
		manager.onDatabaseOpened(new Transaction(null, false));
		assertEquals(answered, countSent(c1, OnionAuthRecords.TYPE_OFFER));
		assertEquals(0, countSent(c2, OnionAuthRecords.TYPE_OFFER));
	}

	private int countSent(Contact c, int type) {
		int n = 0;
		for (OnionAuthRecords.Record r : sentTo(c)) if (r.type == type) n++;
		return n;
	}

	@Test
	public void testNoFallbackAfterCommitWhateverHappens() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		control.refuseCredentials = true;
		manager.detachTor();
		manager.attachTor(control, 4444);
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
		assertFalse(manager.acceptsInbound(c1.getId(), false));
		now.addAndGet(OnionClientAuthManagerImpl.NEGOTIATION_TIMEOUT_MS * 2);
		manager.resetNegotiation(c1.getId());
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
	}

	@Test
	public void testPeerCannotRollBackACommittedPair() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		int commitsBefore = sentTo(c1).size();
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		receive(c1, g1, OnionAuthRecords.ready(1));
		receive(c1, g1, OnionAuthRecords.probeSuccess(1));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
		assertTrue(sentTo(c1).size() > commitsBefore);
		assertEquals(OnionAuthRecords.TYPE_COMMIT,
				sentTo(c1).get(sentTo(c1).size() - 1).type);
	}

	@Test
	public void testStaleReplayedAndMismatchedCommitsChangeNothing()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		receive(c1, g1, OnionAuthRecords.ready(1));
		manager.dialSucceeded(c1.getId(), PEER_ONION);
		receive(c1, g1, OnionAuthRecords.probeSuccess(1));
		byte[] fp = OnionAuthCommitCheck.fingerprint(peerPub(1));
		String myOnion = service().onion;
		receive(c1, g1, OnionAuthRecords.commit(0, myOnion, fp));
		assertEquals(State.AUTH_CONFIRMED, record(c1).state);
		receive(c1, g1, OnionAuthRecords.commit(2, myOnion, fp));
		assertEquals(State.AUTH_CONFIRMED, record(c1).state);
		receive(c1, g1, OnionAuthRecords.commit(1, PEER_ONION_2, fp));
		assertEquals(State.AUTH_CONFIRMED, record(c1).state);
		receive(c1, g1, OnionAuthRecords.commit(1, myOnion,
				OnionAuthCommitCheck.fingerprint(peerPub(9))));
		assertEquals(State.AUTH_CONFIRMED, record(c1).state);
		receive(c1, g1, OnionAuthRecords.commit(1, myOnion, fp));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
		receive(c1, g1, OnionAuthRecords.commit(1, myOnion, fp));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
	}

	@Test
	public void testCommitAfterRevocationIsIgnoredAndTheKeyIsRemovedAtOnce()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		String oldOnion = service().onion;
		control.commands.clear();
		Transaction txn = new Transaction(null, false);
		manager.removingContact(txn, c1);
		commit(txn);
		assertEquals(State.REVOKED, record(c1).state);
		assertNull(record(c1).dialPrivateKey);
		assertFalse(manager.acceptsInbound(c1.getId(), true));
		assertFalse(manager.acceptsInbound(c1.getId(), false));
		int uncred = control.commands.indexOf("UNCRED " + PEER_ONION);
		int del = control.commands.indexOf("DEL " + oldOnion);
		assertTrue(uncred >= 0);
		assertTrue(del > uncred);
		String newOnion = service().onion;
		assertNotNull(newOnion);
		assertFalse(newOnion.equals(oldOnion));
		assertTrue(control.commands.indexOf("ADD " + newOnion + " keys=1 port=4444") > del);
		assertFalse(control.authorizes(newOnion, peerPub(1)));
		assertTrue(control.authorizes(newOnion, peerPub(2)));
		assertEquals(oldOnion, service().oldOnion);
		assertFalse(control.authorizes(oldOnion, peerPub(1)));
		assertTrue(control.authorizes(oldOnion, peerPub(2)));
		OnionAuthRecords.Record rot = lastSent(c2, OnionAuthRecords.TYPE_ROTATE);
		assertNotNull(rot);
		assertEquals(newOnion, rot.onion);
		byte[] fp = OnionAuthCommitCheck.fingerprint(peerPub(1));
		receive(c1, g1, OnionAuthRecords.commit(1, newOnion, fp));
		assertEquals(State.REVOKED, record(c1).state);
	}

	@Test
	public void testNormalRotationOverlapsUntilAcknowledged() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String oldOnion = service().onion;
		manager.rotateAuthorizedService();
		OnionAuthStore.ServiceRecord svc = service();
		assertEquals(oldOnion, svc.oldOnion);
		assertFalse(oldOnion.equals(svc.onion));
		assertEquals(2, svc.gen);
		assertFalse(control.commands.contains("DEL " + oldOnion));
		assertTrue(control.authorizes(svc.onion, peerPub(1)));
		OnionAuthRecords.Record rot = lastSent(c1, OnionAuthRecords.TYPE_ROTATE);
		assertEquals(2, rot.keyVersion);
		assertEquals(svc.onion, rot.onion);
		receive(c1, g1, OnionAuthRecords.rotateAck(2));
		assertTrue(control.commands.contains("DEL " + oldOnion));
		assertNull(service().oldOnion);
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
	}

	@Test
	public void testTheOldAddressIsNeverRetiredWithoutAnAcknowledgement()
			throws Exception {
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		String oldOnion = service().onion;
		manager.rotateAuthorizedService();
		for (int i = 0; i < 100; i++) {
			now.addAndGet(TimeUnit.DAYS.toMillis(1));
			manager.tickForTest();
		}
		assertEquals("the old address was retired without an acknowledgement",
				oldOnion, service().oldOnion);
		assertTrue(control.authorizes(oldOnion, peerPub(2)));
		receive(c2, g2, OnionAuthRecords.rotateAck(2));
		assertFalse(published(oldOnion));
		assertNull(service().oldOnion);
	}

	@Test
	public void testARotationRequestDuringAnOverlapChangesNothing()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String first = service().onion;
		manager.rotateAuthorizedService();
		String second = service().onion;
		manager.rotateAuthorizedService();
		assertEquals("a second rotation replaced the address still in use",
				2, service().gen);
		assertEquals(first, service().oldOnion);
		assertEquals(second, service().onion);
		assertTrue(control.authorizes(first, peerPub(1)));
		assertTrue(control.authorizes(second, peerPub(1)));
	}

	@Test
	public void testACommitAtTheCurrentGenerationCountsAsAnAcknowledgement()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String first = service().onion;
		manager.rotateAuthorizedService();
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		assertEquals(first, service().oldOnion);
		assertTrue(published(first));
		receive(c1, g1, OnionAuthRecords.rotateAck(2));
		assertFalse("a contact that committed to the new address still holds"
				+ " the old one", published(first));
		assertNull(service().oldOnion);
	}

	@Test
	public void testAContactLockedInAfterTheRotationByAnEarlierVersionDoesNotHoldTheOldAddress()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String first = service().onion;
		manager.rotateAuthorizedService();
		now.addAndGet(TimeUnit.HOURS.toMillis(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		OnionAuthRecord stored = record(c2);
		stored.rotateAcked = false;
		store.save(null, stored);
		receive(c1, g1, OnionAuthRecords.rotateAck(2));
		assertFalse("a contact that only ever knew the new address holds the"
				+ " old one", published(first));
		assertNull(service().oldOnion);
	}

	@Test
	public void testACommitOfTheCommittedRoundToTheNewAddressCountsAsAnAcknowledgement()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String first = service().onion;
		manager.rotateAuthorizedService();
		String second = service().onion;
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(9)));
		assertTrue(record(c1).rekeying);
		receive(c1, g1, OnionAuthRecords.commit(1, second,
				OnionAuthCommitCheck.fingerprint(peerPub(1))));
		assertFalse(record(c1).rekeying);
		assertFalse("a commit to the new address left the old one published",
				published(first));
		assertNull(service().oldOnion);
	}

	@Test
	public void testAStaleAcknowledgementIsNoEvidence() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String first = service().onion;
		manager.rotateAuthorizedService();
		receive(c1, g1, OnionAuthRecords.rotateAck(1));
		manager.tickForTest();
		assertTrue("an acknowledgement of an earlier generation retired the"
				+ " old address", published(first));
		assertEquals(first, service().oldOnion);
		receive(c1, g1, OnionAuthRecords.rotateAck(2));
		assertFalse(published(first));
	}

	@Test
	public void testTheCheckRetiresTheOldAddressOnceTheLastContactHoldingItIsGone()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String first = service().onion;
		manager.rotateAuthorizedService();
		peerAdvertisesSupport(c2);
		receive(c2, g2, OnionAuthRecords.offer(1, PEER_ONION_2, peerPub(2)));
		assertEquals(State.AUTH_NEGOTIATING, record(c2).state);
		receive(c1, g1, OnionAuthRecords.rotateAck(2));
		assertTrue("the old address went while a contact still held it",
				published(first));
		now.addAndGet(OnionClientAuthManagerImpl.NEGOTIATION_TIMEOUT_MS
				+ TimeUnit.DAYS.toMillis(1));
		manager.tickForTest();
		assertEquals(State.LEGACY, record(c2).state);
		assertFalse("the check did not retire the old address once nobody"
				+ " held it", published(first));
		assertNull(service().oldOnion);
	}

	@Test
	public void testAQueuedRetirementSparesTheAddressOfARotationThatOvertookIt()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		manager.rotateAuthorizedService();
		String second = service().onion;
		receive(c2, g2, OnionAuthRecords.rotateAck(2));
		deferring = true;
		receive(c1, g1, OnionAuthRecords.rotateAck(2));
		Transaction txn = new Transaction(null, false);
		manager.removingContact(txn, c2);
		commit(txn);
		deferring = false;
		List<Runnable> tasks = new ArrayList<>(deferred);
		deferred.clear();
		Collections.reverse(tasks);
		for (Runnable t : tasks) t.run();
		assertEquals(3, service().gen);
		assertEquals(second, service().oldOnion);
		assertTrue("a retirement queued before the rotation removed the"
				+ " address the remaining contact knows", published(second));
		assertTrue(control.authorizes(second, peerPub(1)));
	}

	@Test
	public void testWithNoAuthorizedKeyLeftTheOldAddressGoesToo()
			throws Exception {
		OnionAuthStore.ServiceRecord svc = new OnionAuthStore.ServiceRecord();
		svc.gen = 2;
		svc.oldOnion = PEER_ONION;
		svc.oldPrivateKey = "KEY:" + PEER_ONION;
		svc.oldSinceMs = now.get();
		svc.lastRotationMs = now.get();
		store.saveService(null, svc);
		manager.detachTor();
		manager.attachTor(control, 4444, dialed::add);
		assertNull("an old address with no authorized contact left was kept",
				service().oldOnion);
		assertTrue(control.commands.contains("DEL " + PEER_ONION));
	}

	private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);

	private void rotateWithOnlyTheDialerAcknowledging() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		manager.rotateAuthorizedService();
		receive(c2, g2, OnionAuthRecords.rotateAck(2));
	}

	@Test
	public void testAStaleQueuedRevocationRotationDoesNotRotateASecondTime()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		OnionAuthStore.ServiceRecord svc = service();
		svc.revocationPending = true;
		store.saveService(null, svc);
		deferring = true;
		manager.tickForTest();
		deferring = false;
		OnionAuthStore.ServiceRecord done = service();
		done.revocationPending = false;
		store.saveService(null, done);
		long gen = done.gen;
		String onion = done.onion;
		for (Runnable t : new ArrayList<>(deferred)) t.run();
		deferred.clear();
		assertEquals("a queued revocation rotated after it was already done",
				gen, service().gen);
		assertEquals(onion, service().onion);
	}

	@Test
	public void testTheTickRepublishesWhenOnlyTheAuthorizedKeysChanged()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String onion = service().onion;
		OnionAuthRecord r = record(c1);
		r.peerPublicKey = peerPub(9);
		store.save(null, r);
		assertFalse(control.authorizes(onion, peerPub(9)));
		manager.tickForTest();
		assertTrue("the published key set was left stale",
				control.authorizes(service().onion, peerPub(9)));
	}

	@Test
	public void testTheTickLeavesAnUnchangedServiceAlone() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		int before = publishes();
		manager.tickForTest();
		manager.tickForTest();
		assertEquals(before, publishes());
	}

	@Test
	public void testTheSideThatIsNotTheDialerRetiresTheOldAddressAfterNinetyDaysOfSilence()
			throws Exception {
		rotateWithOnlyTheDialerAcknowledging();
		String first = service().oldOnion;
		now.addAndGet(90 * DAY_MS + 60_000L);
		manager.tickForTest();
		assertFalse(published(first));
		assertNull(service().oldOnion);
		assertTrue(manager.getContactsHoldingOldAddress().isEmpty());
	}

	@Test
	public void testTheOldAddressIsKeptAfterEightyNineDaysOfSilence()
			throws Exception {
		rotateWithOnlyTheDialerAcknowledging();
		String first = service().oldOnion;
		now.addAndGet(89 * DAY_MS);
		manager.tickForTest();
		assertTrue(published(first));
		assertEquals(Collections.singletonList(c1.getId()),
				manager.getContactsHoldingOldAddress());
	}

	@Test
	public void testTheDialerIsNeverTimedOut() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		manager.rotateAuthorizedService();
		receive(c1, g1, OnionAuthRecords.rotateAck(2));
		String first = service().oldOnion;
		now.addAndGet(400 * DAY_MS);
		manager.tickForTest();
		assertTrue(published(first));
		assertEquals(Collections.singletonList(c2.getId()),
				manager.getContactsHoldingOldAddress());
	}

	@Test
	public void testAConnectionRestartsTheNinetyDayClock() throws Exception {
		rotateWithOnlyTheDialerAcknowledging();
		String first = service().oldOnion;
		now.addAndGet(80 * DAY_MS);
		manager.eventOccurred(new ConnectionOpenedEvent(c1.getId(),
				TorConstants.ID, true));
		now.addAndGet(80 * DAY_MS);
		manager.tickForTest();
		assertTrue(published(first));
		now.addAndGet(11 * DAY_MS);
		manager.tickForTest();
		assertFalse(published(first));
	}

	@Test
	public void testAClockSetBackDoesNotShortenTheContactRecord()
			throws Exception {
		rotateWithOnlyTheDialerAcknowledging();
		now.addAndGet(80 * DAY_MS);
		manager.eventOccurred(new ConnectionOpenedEvent(c1.getId(),
				TorConstants.ID, true));
		long recorded = manager.getLastContactMs(c1.getId());
		assertEquals(now.get(), recorded);
		OnionClientAuthManagerImpl again = restartedManager();
		now.addAndGet(-70 * DAY_MS);

		again.eventOccurred(new ConnectionOpenedEvent(c1.getId(),
				TorConstants.ID, true));

		assertEquals("a time before the stored one was written", recorded,
				again.getLastContactMs(c1.getId()));
	}

	@Test
	public void testAClockSetBackDoesNotShortenTheLastSeenTime()
			throws Exception {
		rotateWithOnlyTheDialerAcknowledging();
		now.addAndGet(80 * DAY_MS);
		manager.inboundViaAuthorizedService(c1.getId());
		long seen = record(c1).lastSeenMs;
		assertEquals(now.get(), seen);
		OnionClientAuthManagerImpl again = restartedManager();
		now.addAndGet(-70 * DAY_MS);

		again.inboundViaAuthorizedService(c1.getId());

		assertEquals("a time before the stored one was written", seen,
				record(c1).lastSeenMs);
	}

	@Test
	public void testAReceivedMessageOnAnyTransportRestartsTheNinetyDayClock()
			throws Exception {
		rotateWithOnlyTheDialerAcknowledging();
		String first = service().oldOnion;
		now.addAndGet(80 * DAY_MS);
		Message m = new Message(new MessageId(TestUtils.getRandomId()),
				g1.getId(), now.get(), new byte[1]);
		manager.eventOccurred(new MessageAddedEvent(m, c1.getId()));
		now.addAndGet(80 * DAY_MS);
		manager.tickForTest();
		assertTrue(published(first));
		now.addAndGet(11 * DAY_MS);
		manager.tickForTest();
		assertFalse(published(first));
	}

	@Test
	public void testTheContactsStillHoldingTheOldAddressAreListedAfterADay()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		manager.dialSucceeded(c1.getId(), PEER_ONION);
		manager.rotateAuthorizedService();
		receive(c2, g2, OnionAuthRecords.rotateAck(2));
		assertTrue(manager.getContactsHoldingOldAddress().isEmpty());
		now.addAndGet(OnionClientAuthManagerImpl.HOLD_NOTICE_MS);
		assertEquals(Collections.singletonList(c1.getId()),
				manager.getContactsHoldingOldAddress());
		assertTrue(manager.getLastContactMs(c1.getId()) > 0);
		receive(c1, g1, OnionAuthRecords.rotateAck(2));
		assertTrue(manager.getContactsHoldingOldAddress().isEmpty());
	}

	@Test
	public void testTheDiagnosticsNameTheContactsStillHoldingTheOldAddress()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		assertFalse(manager.diagnostics(c1.getId()).isHoldingPreviousAddress());
		manager.rotateAuthorizedService();
		receive(c2, g2, OnionAuthRecords.rotateAck(2));
		assertTrue(manager.diagnostics(c1.getId()).isHoldingPreviousAddress());
		assertFalse(manager.diagnostics(c2.getId()).isHoldingPreviousAddress());
		receive(c1, g1, OnionAuthRecords.rotateAck(2));
		assertFalse(manager.diagnostics(c1.getId()).isHoldingPreviousAddress());
	}

	@Test
	public void testARevocationRemovesTheKeyAtOnceAndKeepsTheOldAddressForTheOthers()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		String oldOnion = service().onion;
		control.commands.clear();
		Transaction txn = new Transaction(null, false);
		manager.removingContact(txn, c1);
		commit(txn);
		assertEquals(State.REVOKED, record(c1).state);
		assertTrue(control.commands.contains("UNCRED " + PEER_ONION));
		assertFalse(control.authorizes(oldOnion, peerPub(1)));
		assertTrue("the old address was deleted before the remaining contact"
				+ " learned the new one",
				control.authorizes(oldOnion, peerPub(2)));
		String newOnion = service().onion;
		assertNotEquals(oldOnion, newOnion);
		assertEquals(oldOnion, service().oldOnion);
		assertFalse(control.authorizes(newOnion, peerPub(1)));
		assertTrue(control.authorizes(newOnion, peerPub(2)));
		assertFalse(service().revocationPending);
		assertEquals(newOnion, lastSent(c2, OnionAuthRecords.TYPE_ROTATE).onion);
		assertNull(lastSent(c1, OnionAuthRecords.TYPE_ROTATE));
		receive(c2, g2, OnionAuthRecords.rotateAck(2));
		assertFalse(published(oldOnion));
		assertNull(service().oldOnion);
		assertTrue(control.authorizes(newOnion, peerPub(2)));
	}

	@Test
	public void testARevocationDuringAnOverlapWaitsUntilTheOldAddressRetires()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		String first = service().onion;
		manager.rotateAuthorizedService();
		String second = service().onion;
		receive(c2, g2, OnionAuthRecords.rotateAck(2));
		assertEquals(first, service().oldOnion);
		control.commands.clear();
		Transaction txn = new Transaction(null, false);
		manager.removingContact(txn, c2);
		commit(txn);
		assertTrue("the address the remaining contact knows was deleted",
				control.authorizes(first, peerPub(1)));
		assertFalse(control.authorizes(first, peerPub(2)));
		assertTrue(control.authorizes(second, peerPub(1)));
		assertFalse(control.authorizes(second, peerPub(2)));
		assertEquals(2, service().gen);
		assertEquals(first, service().oldOnion);
		assertEquals(second, service().onion);
		assertTrue(service().revocationPending);
		receive(c1, g1, OnionAuthRecords.rotateAck(2));
		assertFalse(published(first));
		assertEquals(3, service().gen);
		assertEquals(second, service().oldOnion);
		String third = service().onion;
		assertNotEquals(second, third);
		assertFalse(service().revocationPending);
		assertTrue(control.authorizes(third, peerPub(1)));
		assertFalse(control.authorizes(third, peerPub(2)));
		assertTrue(control.authorizes(second, peerPub(1)));
		assertEquals(third, lastSent(c1, OnionAuthRecords.TYPE_ROTATE).onion);
		receive(c1, g1, OnionAuthRecords.rotateAck(3));
		assertFalse(published(second));
		assertNull(service().oldOnion);
		assertTrue(control.authorizes(third, peerPub(1)));
	}

	@Test
	public void testAContactInTouchOverAnyTransportLetsTheScheduledRotationRun()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		long period = service().rotationPeriodMs;
		now.addAndGet(period + TimeUnit.DAYS.toMillis(1));
		manager.tickForTest();
		assertEquals(1, service().gen);
		manager.eventOccurred(new org.zerionproject.core.api.plugin.event
				.ConnectionOpenedEvent(c1.getId(),
				new org.zerionproject.core.api.plugin.TransportId("mesh"),
				true));
		Message m = new Message(new MessageId(TestUtils.getRandomId()),
				g2.getId(), now.get(), new byte[1]);
		manager.eventOccurred(new org.zerionproject.core.api.sync.event
				.MessageAddedEvent(m, c2.getId()));
		manager.tickForTest();
		assertEquals("contacts in touch over another transport held the"
				+ " rotation back", 2, service().gen);
	}

	@Test
	public void testContactOverAnotherTransportLeavesTheSilenceRepairDue()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		now.addAndGet(OnionClientAuthManagerImpl.SILENCE_MS
				+ TimeUnit.HOURS.toMillis(1));
		manager.eventOccurred(new org.zerionproject.core.api.plugin.event
				.ConnectionOpenedEvent(c1.getId(),
				new org.zerionproject.core.api.plugin.TransportId("mesh"),
				true));
		manager.tickForTest();
		assertEquals(1, record(c1).silenceRepairs);
	}

	@Test
	public void testNoPeriodicRotationWhileAContactIsSilentSinceTheLastOne()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		long period = service().rotationPeriodMs;
		now.addAndGet(period + TimeUnit.DAYS.toMillis(1));
		manager.tickForTest();
		assertEquals("rotated while the contact was silent", 1,
				service().gen);
		manager.dialSucceeded(c1.getId(), PEER_ONION);
		manager.tickForTest();
		assertEquals("the periodic rotation never ran", 2, service().gen);
	}

	@Test
	public void testSkippingNewNegotiationsKeepsAFreshPairOnTheOpenAddress()
			throws Exception {
		skipNew.set(true);
		peerAdvertisesSupport(c1);
		assertEquals(State.LEGACY, record(c1).state);
		assertNull(lastSent(c1, OnionAuthRecords.TYPE_OFFER));
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		assertEquals(State.LEGACY, record(c1).state);
		assertNull(manager.getDialOnion(c1.getId()));
		assertTrue(manager.acceptsInbound(c1.getId(), false));
		assertFalse(control.authorizes(String.valueOf(service().onion),
				peerPub(1)));
		skipNew.set(false);
		peerAdvertisesSupport(c1);
		assertEquals(State.AUTH_NEGOTIATING, record(c1).state);
		assertNotNull(lastSent(c1, OnionAuthRecords.TYPE_OFFER));
	}

	@Test
	public void testSkippingNewNegotiationsLeavesALockedPairLocked()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		skipNew.set(true);
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
		assertFalse(manager.acceptsInbound(c1.getId(), false));
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
	}

	@Test
	public void testAHeldConfirmationIsSentByTheRepairOnceReleased()
			throws Exception {
		holdCommits.set(true);
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		receive(c1, g1, OnionAuthRecords.ready(1));
		manager.dialSucceeded(c1.getId(), PEER_ONION);
		receive(c1, g1, OnionAuthRecords.probeSuccess(1));
		assertTrue(record(c1).commitSent);
		assertNull(lastSent(c1, OnionAuthRecords.TYPE_COMMIT));
		receive(c1, g1, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub(1))));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
		assertNull(lastSent(c1, OnionAuthRecords.TYPE_COMMIT));
		holdCommits.set(false);
		now.addAndGet(OnionClientAuthManagerImpl.REPAIR_DELAY_MS + 1);
		receive(c1, g1, OnionAuthRecords.probeSuccess(1));
		assertNotNull(lastSent(c1, OnionAuthRecords.TYPE_COMMIT));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
	}

	@Test
	public void testTheAuthorizedAddressRotatesAfterAPeriodDrawnEachTime()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		OnionAuthStore.ServiceRecord first = service();
		assertTrue(OnionClientAuthManagerImpl.isRotationPeriod(
				first.rotationPeriodMs));
		now.addAndGet(TimeUnit.DAYS.toMillis(3));
		manager.rotateAuthorizedService();
		OnionAuthStore.ServiceRecord second = service();
		assertFalse(first.onion.equals(second.onion));
		assertEquals(now.get(), second.lastRotationMs);
		assertTrue(OnionClientAuthManagerImpl.isRotationPeriod(
				second.rotationPeriodMs));
		Set<Long> drawn = new HashSet<>();
		for (int i = 0; i < 20; i++) drawn.add(manager.drawRotationPeriodMs());
		assertTrue("periods differ between rotations", drawn.size() > 1);
		for (long p : drawn) {
			assertTrue(OnionClientAuthManagerImpl.isRotationPeriod(p));
		}
	}

	@Test
	public void testAPeriodicRotationIsDueOnlyAfterItsPeriodAndOldRetired() {
		OnionAuthStore.ServiceRecord svc = new OnionAuthStore.ServiceRecord();
		svc.onion = PEER_ONION;
		svc.lastRotationMs = 1_000L;
		svc.rotationPeriodMs = OnionClientAuthManagerImpl.MIN_ROTATION_PERIOD_MS
				+ 123_456L;
		long due = svc.lastRotationMs + svc.rotationPeriodMs;
		assertFalse(OnionClientAuthManagerImpl.periodicRotationDue(svc, due));
		assertTrue(OnionClientAuthManagerImpl.periodicRotationDue(svc,
				due + 1));
		svc.oldOnion = PEER_ONION_2;
		assertFalse("the old address is still served",
				OnionClientAuthManagerImpl.periodicRotationDue(svc, due + 1));
		svc.oldOnion = null;
		svc.rotationPeriodMs = 0L;
		assertFalse("no period drawn yet",
				OnionClientAuthManagerImpl.periodicRotationDue(svc,
						Long.MAX_VALUE));
		svc.rotationPeriodMs = OnionClientAuthManagerImpl.MIN_ROTATION_PERIOD_MS;
		svc.onion = null;
		assertFalse(OnionClientAuthManagerImpl.periodicRotationDue(svc,
				Long.MAX_VALUE));
	}

	@Test
	public void testAStoredServiceWithoutAPeriodGetsOneAndKeepsItsAddress()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		store.saveRotationPeriod(null, 0L);
		String onion = service().onion;
		now.addAndGet(TimeUnit.DAYS.toMillis(1));
		manager.tickForTest();
		assertTrue(OnionClientAuthManagerImpl.isRotationPeriod(
				service().rotationPeriodMs));
		assertEquals(onion, service().onion);
	}

	@Test
	public void testPeerRotationInstallsTheNewAddressAndAcks() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		receive(c1, g1, OnionAuthRecords.rotate(2, PEER_ONION_2, null));
		assertEquals(PEER_ONION_2, record(c1).peerOnion);
		assertEquals(2, record(c1).peerGen);
		assertEquals(PEER_ONION_2, manager.getDialOnion(c1.getId()));
		assertTrue(control.commands.contains("CRED " + PEER_ONION_2));
		assertEquals(2, lastSent(c1, OnionAuthRecords.TYPE_ROTATE_ACK).keyVersion);
		receive(c1, g1, OnionAuthRecords.rotate(1, PEER_ONION, null));
		assertEquals(PEER_ONION_2, record(c1).peerOnion);
	}

	@Test
	public void testPreCommitAbortLeavesNothingBehind() throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		String onion = service().onion;
		assertTrue(control.authorizes(onion, peerPub(1)));
		assertEquals(State.AUTH_NEGOTIATING, record(c1).state);
		now.addAndGet(OnionClientAuthManagerImpl.NEGOTIATION_TIMEOUT_MS + 1);
		manager.resetNegotiation(c1.getId());
		assertEquals(State.LEGACY, record(c1).state);
		assertNull(record(c1).dialPrivateKey);
		assertTrue(store.loadAll(null).isEmpty());
		assertTrue(control.commands.contains("UNCRED " + PEER_ONION));
		assertFalse(control.authorizes(onion, peerPub(1)));
		assertNull(service().onion);
		assertNull(manager.getDialOnion(c1.getId()));
		assertTrue(manager.acceptsInbound(c1.getId(), false));
	}

	@Test
	public void testTorRestartRefeedsExactlyThePersistedState() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		String onion = service().onion;
		String key = service().privateKey;
		control.commands.clear();
		manager.detachTor();
		manager.attachTor(control, 5555);
		assertEquals(onion, service().onion);
		assertEquals(key, service().privateKey);
		assertTrue(control.commands.contains("ADD " + onion + " keys=2 port=5555"));
		assertTrue(control.commands.contains("CRED " + PEER_ONION));
		assertTrue(control.commands.contains("CRED " + PEER_ONION_2));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
		assertEquals(State.AUTH_REQUIRED, record(c2).state);
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
	}

	@Test
	public void testAnOfferReceivedWhileTorIsDownIsAnsweredAtTheNextRefeed()
			throws Exception {
		manager.detachTor();
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		assertNull(lastSent(c1, OnionAuthRecords.TYPE_READY));
		assertNull(service().onion);
		manager.attachTor(control, 4444);
		assertNotNull(service().onion);
		assertTrue(control.authorizes(service().onion, peerPub(1)));
		assertTrue(control.commands.contains("CRED " + PEER_ONION));
		assertNotNull(lastSent(c1, OnionAuthRecords.TYPE_READY));
		assertEquals(State.AUTH_NEGOTIATING, record(c1).state);
	}

	@Test
	public void testAnOfferWithoutAnAddressIsAnsweredAndSurvivesARefeed()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, null, peerPub(1)));
		assertNull(record(c1).peerOnion);
		assertNotNull(service().onion);
		assertTrue(control.authorizes(service().onion, peerPub(1)));
		assertFalse(control.commands.contains("CRED " + PEER_ONION));
		assertNull(lastSent(c1, OnionAuthRecords.TYPE_READY));
		OnionAuthRecords.Record offer = lastSent(c1, OnionAuthRecords.TYPE_OFFER);
		assertEquals(service().onion, offer.onion);
		manager.detachTor();
		manager.attachTor(control, 4444);
		assertTrue(control.authorizes(service().onion, peerPub(1)));
		assertFalse(control.commands.contains("CRED " + PEER_ONION));
		assertNull(lastSent(c1, OnionAuthRecords.TYPE_READY));
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		assertEquals(PEER_ONION, record(c1).peerOnion);
		assertTrue(control.commands.contains("CRED " + PEER_ONION));
		assertNotNull(lastSent(c1, OnionAuthRecords.TYPE_READY));
	}

	@Test
	public void testOnlyADialThatReachedTheAuthorizedAddressIsAProbe()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		assertFalse(dialed.contains(c1.getId()));
		receive(c1, g1, OnionAuthRecords.ready(1));
		assertTrue(dialed.contains(c1.getId()));
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
		manager.dialSucceeded(c1.getId(), PEER_ONION_2);
		manager.dialSucceeded(c2.getId(), PEER_ONION);
		assertNull(lastSent(c1, OnionAuthRecords.TYPE_PROBE_SUCCESS));
		assertFalse(record(c1).probeSucceeded);
		manager.dialSucceeded(c1.getId(), PEER_ONION);
		assertNotNull(lastSent(c1, OnionAuthRecords.TYPE_PROBE_SUCCESS));
		assertTrue(record(c1).probeSucceeded);
		assertEquals(State.AUTH_CONFIRMED, record(c1).state);
	}

	@Test
	public void testAnInboundThroughTheAuthorizedServiceIsAProbe()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		manager.inboundViaAuthorizedService(c1.getId());
		assertNull(lastSent(c1, OnionAuthRecords.TYPE_PROBE_SUCCESS));
		receive(c1, g1, OnionAuthRecords.ready(1));
		manager.inboundViaAuthorizedService(c2.getId());
		assertNull(lastSent(c1, OnionAuthRecords.TYPE_PROBE_SUCCESS));
		manager.inboundViaAuthorizedService(c1.getId());
		assertNotNull(lastSent(c1, OnionAuthRecords.TYPE_PROBE_SUCCESS));
		assertTrue(record(c1).probeSucceeded);
	}

	@Test
	public void testAProbeInProgressIsDialedAgainAfterATorRestart()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		receive(c1, g1, OnionAuthRecords.ready(1));
		dialed.clear();
		manager.detachTor();
		manager.attachTor(control, 4444, dialed::add);
		assertTrue(dialed.contains(c1.getId()));
	}

	@Test
	public void testACommitWithoutAnAnswerIsSentAgainWhenTheDatabaseOpens()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		receive(c1, g1, OnionAuthRecords.ready(1));
		manager.dialSucceeded(c1.getId(), PEER_ONION);
		receive(c1, g1, OnionAuthRecords.probeSuccess(1));
		assertEquals(1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		assertEquals(State.AUTH_CONFIRMED, record(c1).state);
		manager.onDatabaseOpened(new Transaction(null, false));
		assertEquals(2, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		receive(c1, g1, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub(1))));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
		manager.onDatabaseOpened(new Transaction(null, false));
		assertEquals(2, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
	}

	@Test
	public void testALockedInSideAnswersAnyCommitWithItsOwn()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		int before = countSent(c1, OnionAuthRecords.TYPE_COMMIT);
		receive(c1, g1, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub(1))));
		assertEquals(before + 1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
	}

	@Test
	public void testARevocationWhileTorIsDownRotatesAtTheNextRefeed()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		String oldOnion = service().onion;
		manager.detachTor();
		control.commands.clear();
		Transaction txn = new Transaction(null, false);
		manager.removingContact(txn, c1);
		commit(txn);
		assertTrue(service().revocationPending);
		assertEquals(State.REVOKED, record(c1).state);
		assertTrue(control.commands.isEmpty());
		manager.attachTor(control, 4444, dialed::add);
		String newOnion = service().onion;
		assertNotNull(newOnion);
		assertNotEquals(oldOnion, newOnion);
		assertFalse(service().revocationPending);
		assertTrue(control.commands.contains("ADD " + newOnion + " keys=1 port=4444"));
		assertFalse(control.authorizes(newOnion, peerPub(1)));
		assertTrue(control.authorizes(newOnion, peerPub(2)));
		assertEquals(oldOnion, service().oldOnion);
		assertFalse(control.authorizes(oldOnion, peerPub(1)));
		assertTrue(control.authorizes(oldOnion, peerPub(2)));
		OnionAuthRecords.Record rotate = lastSent(c2, OnionAuthRecords.TYPE_ROTATE);
		assertNotNull(rotate);
		assertEquals(newOnion, rotate.onion);
		assertNull(lastSent(c1, OnionAuthRecords.TYPE_ROTATE));
	}

	@Test
	public void testARevocationWhosePublishFailsIsCompletedAtTheNextRefeed()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		String oldOnion = service().onion;
		control.failPublish = true;
		control.commands.clear();
		Transaction txn = new Transaction(null, false);
		manager.removingContact(txn, c1);
		commit(txn);
		assertTrue(control.commands.contains("DEL " + oldOnion));
		assertTrue(service().revocationPending);
		assertNull(lastSent(c2, OnionAuthRecords.TYPE_ROTATE));
		control.failPublish = false;
		manager.detachTor();
		manager.attachTor(control, 4444, dialed::add);
		String newOnion = service().onion;
		assertNotEquals(oldOnion, newOnion);
		assertFalse(service().revocationPending);
		assertTrue(control.authorizes(newOnion, peerPub(2)));
		assertFalse(control.authorizes(newOnion, peerPub(1)));
		assertEquals(newOnion,
				lastSent(c2, OnionAuthRecords.TYPE_ROTATE).onion);
	}

	@Test
	public void testDialingKeysAreRandomPerContact() throws Exception {
		peerAdvertisesSupport(c1);
		peerAdvertisesSupport(c2);
		byte[] k1 = record(c1).dialPrivateKey;
		byte[] k2 = record(c2).dialPrivateKey;
		assertNotNull(k1);
		assertNotNull(k2);
		assertFalse(Arrays.equals(k1, k2));
		assertFalse(Arrays.equals(record(c1).dialPublicKey,
				record(c2).dialPublicKey));
	}

	@Test
	public void testProbeWindowExpiresAndResumesWithoutOpenDialAfterReady()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		receive(c1, g1, OnionAuthRecords.ready(1));
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
		now.addAndGet(OnionClientAuthManagerImpl.PROBE_WINDOW_MS + 1);
		manager.tickForTest();
		assertNull(manager.getDialOnion(c1.getId()));
		assertEquals(State.AUTH_NEGOTIATING, record(c1).state);
		now.addAndGet(OnionClientAuthManagerImpl.PROBE_PAUSE_MS + 1);
		manager.tickForTest();
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
	}

	@Test
	public void testRefeedCredentialsReinstallsOnlyTheCredentials()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String onion = service().onion;
		control.commands.clear();
		manager.refeedCredentials();
		assertEquals(Collections.singletonList("CRED " + PEER_ONION),
				control.commands);
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
		assertEquals(onion, service().onion);
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
	}

	@Test
	public void testRefeedCredentialsCoversEveryPairThatHoldsOne()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		peerAdvertisesSupport(c2);
		receive(c2, g2, OnionAuthRecords.offer(1, PEER_ONION_2, peerPub(2)));
		assertEquals(State.AUTH_NEGOTIATING, record(c2).state);
		control.commands.clear();
		manager.refeedCredentials();
		assertEquals(2, control.commands.size());
		assertTrue(control.commands.contains("CRED " + PEER_ONION));
		assertTrue(control.commands.contains("CRED " + PEER_ONION_2));
	}

	@Test
	public void testRefeedCredentialsSkipsRevokedAndLegacyPairs()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		Transaction txn = new Transaction(null, false);
		manager.removingContact(txn, c1);
		commit(txn);
		assertEquals(State.REVOKED, record(c1).state);
		control.commands.clear();
		manager.refeedCredentials();
		assertEquals(Collections.singletonList("CRED " + PEER_ONION_2),
				control.commands);
	}

	@Test
	public void testRefeedCredentialsInstallsNothingBeforeThePeerAnswered()
			throws Exception {
		peerAdvertisesSupport(c1);
		assertEquals(State.AUTH_NEGOTIATING, record(c1).state);
		control.commands.clear();
		manager.refeedCredentials();
		assertTrue(control.commands.isEmpty());
	}

	@Test
	public void testRefeedCredentialsSurvivesARefusalUnchanged()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String onion = service().onion;
		control.refuseCredentials = true;
		control.commands.clear();
		manager.refeedCredentials();
		assertTrue(control.commands.isEmpty());
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
		assertEquals(onion, service().onion);
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
		assertFalse(manager.acceptsInbound(c1.getId(), false));
	}

	@Test
	public void testRefeedCredentialsWithoutTorDoesNothing() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		manager.detachTor();
		control.commands.clear();
		manager.refeedCredentials();
		assertTrue(control.commands.isEmpty());
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
	}

	@Test
	public void testRefeedCredentialsIsIdempotent() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		control.commands.clear();
		for (int i = 0; i < 3; i++) manager.refeedCredentials();
		assertEquals(Collections.nCopies(3, "CRED " + PEER_ONION),
				control.commands);
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
	}

	private void commitWithoutAnAnswer(Contact c, Group g, String peerOnion,
			byte[] peerPub) throws Exception {
		peerAdvertisesSupport(c);
		receive(c, g, OnionAuthRecords.offer(1, peerOnion, peerPub));
		receive(c, g, OnionAuthRecords.ready(1));
		manager.dialSucceeded(c.getId(), peerOnion);
		receive(c, g, OnionAuthRecords.probeSuccess(1));
		assertEquals(State.AUTH_CONFIRMED, record(c).state);
		assertTrue(record(c).commitSent);
		assertEquals(1, countSent(c, OnionAuthRecords.TYPE_COMMIT));
	}

	private OnionClientAuthManagerImpl restartedManager() throws Exception {
		OnionClientAuthManagerImpl m = new OnionClientAuthManagerImpl(db,
				clientHelper, contactGroupFactory, metadataParser, store,
				crypto, () -> transportPropertyManager, eventBus,
				new org.zerionproject.core.api.system.Clock() {
					@Override
					public long currentTimeMillis() {
						return now.get();
					}

					@Override
					public void sleep(long milliseconds) {
					}
				},
				Runnable::run, scheduler);
		m.onDatabaseOpened(new Transaction(null, false));
		return m;
	}

	@Test
	public void testAReadyCarriesOurGenerationAndEitherGenerationIsAccepted()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(2, PEER_ONION, peerPub(1)));
		long ours = service().gen;
		assertNotEquals(2, ours);
		assertEquals(ours, lastSent(c1, OnionAuthRecords.TYPE_READY).keyVersion);
		receive(c1, g1, OnionAuthRecords.ready(5));
		assertFalse(record(c1).readyReceived);
		receive(c1, g1, OnionAuthRecords.ready(ours));
		assertTrue(record(c1).readyReceived);
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
		peerAdvertisesSupport(c2);
		receive(c2, g2, OnionAuthRecords.offer(2, PEER_ONION_2, peerPub(2)));
		receive(c2, g2, OnionAuthRecords.ready(2));
		assertTrue(record(c2).readyReceived);
	}

	private final List<ContactId> repairCalls =
			Collections.synchronizedList(new ArrayList<>());
	private final List<ContactId> openCalls =
			Collections.synchronizedList(new ArrayList<>());

	private void attachRecordingDialer() {
		manager.attachTor(control, 4444,
				new OnionClientAuthManagerImpl.ProbeDialer() {
					@Override
					public void dial(ContactId c) {
						dialed.add(c);
					}

					@Override
					public void repair(ContactId c) {
						repairCalls.add(c);
					}

					@Override
					public void repairOpen(ContactId c) {
						openCalls.add(c);
					}
				});
	}

	private void completeRekey(Contact c, Group g, String peerOnion,
			byte[] peerPub) throws Exception {
		receive(c, g, OnionAuthRecords.ready(1));
		manager.dialSucceeded(c.getId(), peerOnion);
		receive(c, g, OnionAuthRecords.probeSuccess(1));
		receive(c, g, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub)));
		assertTrue(record(c).lockedIn());
	}

	@Test
	public void testPeerRotationsDoNotRepublishAnUnchangedService()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String onion = service().onion;
		control.commands.clear();
		for (int i = 0; i < 6; i++) {
			receive(c1, g1, OnionAuthRecords.rotate(2 + i,
					i % 2 == 0 ? PEER_ONION_2 : PEER_ONION, null));
		}
		assertFalse(control.commands.contains("DEL " + onion));
		assertTrue(control.commands.contains("CRED " + PEER_ONION_2));
		assertTrue(control.authorizes(onion, peerPub(1)));
		assertEquals(6, countSent(c1, OnionAuthRecords.TYPE_ROTATE_ACK));
		activate(c2, g2, PEER_ONION_2, peerPub(2));
		assertTrue(control.commands.contains("DEL " + onion));
		assertTrue(control.authorizes(onion, peerPub(1)));
		assertTrue(control.authorizes(onion, peerPub(2)));
		control.commands.clear();
		manager.detachTor();
		manager.attachTor(control, 4444);
		assertTrue(control.commands.contains("ADD " + onion
				+ " keys=2 port=4444"));
	}

	@Test
	public void testNoRecordFromThePeerOpensALockedInSide() throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String myOnion = service().onion;
		long[] gens = {0, 1, 2, 99};
		List<BdfList> records = new ArrayList<>();
		for (long gen : gens) {
			records.add(OnionAuthRecords.offer(gen, PEER_ONION, peerPub(1)));
			records.add(OnionAuthRecords.offer(gen, PEER_ONION_2, peerPub(7)));
			records.add(OnionAuthRecords.offer(gen, null, peerPub(8)));
			records.add(OnionAuthRecords.ready(gen));
			records.add(OnionAuthRecords.probeSuccess(gen));
			records.add(OnionAuthRecords.commit(gen, myOnion,
					OnionAuthCommitCheck.fingerprint(peerPub(1))));
			records.add(OnionAuthRecords.commit(gen, PEER_ONION_2,
					OnionAuthCommitCheck.fingerprint(peerPub(7))));
			records.add(OnionAuthRecords.rotate(gen, PEER_ONION_2, null));
			records.add(OnionAuthRecords.rotate(gen, null, peerPub(9)));
			records.add(OnionAuthRecords.rotateAck(gen));
		}
		for (int round = 0; round < 3; round++) {
			for (BdfList body : records) {
				receive(c1, g1, body);
				assertEquals(State.AUTH_REQUIRED, record(c1).state);
				assertFalse(manager.acceptsInbound(c1.getId(), false));
				assertTrue(manager.acceptsInbound(c1.getId(), true));
				assertNotNull(manager.getDialOnion(c1.getId()));
			}
			now.addAndGet(OnionClientAuthManagerImpl.TICK_MS);
			manager.tickForTest();
			manager.detachTor();
			manager.attachTor(control, 4444);
			manager.onDatabaseOpened(new Transaction(null, false));
			assertFalse(manager.acceptsInbound(c1.getId(), false));
		}
	}

	@Test
	public void testAPeerThatLostItsKeysStartsOneRoundADayKeepingTheCommittedPath()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		byte[] dialKey = record(c1).dialPublicKey;
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION_2, peerPub(2)));
		OnionAuthRecord r = record(c1);
		assertTrue(r.rekeying);
		assertEquals(State.AUTH_REQUIRED, r.state);
		assertFalse(manager.acceptsInbound(c1.getId(), false));
		assertArrayEquals(dialKey, r.dialPublicKey);
		assertArrayEquals(peerPub(2), r.peerPublicKey);
		assertArrayEquals(peerPub(1), r.committedPeerKey);
		assertEquals(PEER_ONION, r.committedPeerOnion);
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
		assertTrue(control.authorizes(service().onion, peerPub(1)));
		assertTrue(control.authorizes(service().onion, peerPub(2)));
		assertTrue(control.commands.contains("CRED " + PEER_ONION_2));
		assertArrayEquals(dialKey,
				lastSent(c1, OnionAuthRecords.TYPE_OFFER).publicKey);
		completeRekey(c1, g1, PEER_ONION_2, peerPub(2));
		assertEquals(PEER_ONION_2, manager.getDialOnion(c1.getId()));
		assertFalse(control.authorizes(service().onion, peerPub(1)));
		assertTrue(control.authorizes(service().onion, peerPub(2)));
		assertNull(record(c1).committedPeerKey);
		assertNull(record(c1).committedPeerOnion);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION_2, peerPub(3)));
		assertTrue(record(c1).lockedIn());
		assertArrayEquals(peerPub(2), record(c1).peerPublicKey);
		now.addAndGet(OnionClientAuthManagerImpl.REKEY_INTERVAL_MS);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION_2, peerPub(3)));
		assertTrue(record(c1).rekeying);
		assertArrayEquals(dialKey, record(c1).dialPublicKey);
		assertArrayEquals(peerPub(3), record(c1).peerPublicKey);
		assertArrayEquals(peerPub(2), record(c1).committedPeerKey);
	}

	@Test
	public void testACommitOfTheCommittedRoundEndsARoundInItsFavour()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(2)));
		assertTrue(record(c1).rekeying);
		receive(c1, g1, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub(2))));
		assertTrue(record(c1).rekeying);
		receive(c1, g1, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub(1))));
		OnionAuthRecord r = record(c1);
		assertTrue(r.lockedIn());
		assertArrayEquals(peerPub(1), r.peerPublicKey);
		assertNull(r.committedPeerKey);
		assertTrue(control.authorizes(service().onion, peerPub(1)));
		assertFalse(control.authorizes(service().onion, peerPub(2)));
		assertFalse(manager.acceptsInbound(c1.getId(), false));
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
	}

	@Test
	public void testAnOfferWithTheCommittedKeyEndsARoundInItsFavour()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(2)));
		assertTrue(record(c1).rekeying);
		int commits = countSent(c1, OnionAuthRecords.TYPE_COMMIT);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		OnionAuthRecord r = record(c1);
		assertTrue(r.lockedIn());
		assertArrayEquals(peerPub(1), r.peerPublicKey);
		assertEquals(commits + 1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		assertTrue(control.authorizes(service().onion, peerPub(1)));
		assertFalse(control.authorizes(service().onion, peerPub(2)));
	}

	@Test
	public void testACommitSentSideReturnsToItsCommitWhenThePeerStillHoldsIt()
			throws Exception {
		commitWithoutAnAnswer(c1, g1, PEER_ONION, peerPub(1));
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(2)));
		OnionAuthRecord r = record(c1);
		assertTrue(r.rekeying);
		assertEquals(State.AUTH_CONFIRMED, r.state);
		assertFalse(r.commitSent);
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
		assertTrue(control.authorizes(service().onion, peerPub(1)));
		assertTrue(control.authorizes(service().onion, peerPub(2)));
		now.addAndGet(OnionClientAuthManagerImpl.NEGOTIATION_TIMEOUT_MS + 1);
		manager.tickForTest();
		assertTrue(record(c1).rekeying);
		assertTrue(control.authorizes(service().onion, peerPub(1)));
		receive(c1, g1, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub(1))));
		r = record(c1);
		assertTrue(r.lockedIn());
		assertArrayEquals(peerPub(1), r.peerPublicKey);
		assertFalse(control.authorizes(service().onion, peerPub(2)));
	}

	@Test
	public void testAReadyBeforeTheAddressIsKeptUntilTheAddressArrives()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, null, peerPub(1)));
		receive(c1, g1, OnionAuthRecords.ready(7));
		assertTrue(record(c1).readyReceived);
		assertFalse(record(c1).probing);
		assertNull(manager.getDialOnion(c1.getId()));
		assertFalse(dialed.contains(c1.getId()));
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		assertTrue(record(c1).probing);
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
		assertTrue(dialed.contains(c1.getId()));
		manager.dialSucceeded(c1.getId(), PEER_ONION);
		assertTrue(record(c1).probeSucceeded);
	}

	@Test
	public void testAPeerThatNeverCommitsKeepsTheRepairsBounded()
			throws Exception {
		attachRecordingDialer();
		commitWithoutAnAnswer(c1, g1, PEER_ONION, peerPub(1));
		for (int i = 0; i < 25 * 4; i++) {
			now.addAndGet(TimeUnit.HOURS.toMillis(6));
			manager.tickForTest();
			if (i % 20 == 0) {
				receive(c1, g1, OnionAuthRecords.ready(1));
				receive(c1, g1, OnionAuthRecords.probeSuccess(1));
			}
		}
		assertEquals(OnionClientAuthManagerImpl.MAX_REPAIRS, repairCalls.size());
		assertTrue(openCalls.size() >= 1);
		assertTrue(openCalls.size() <= 12);
		assertEquals(1 + OnionClientAuthManagerImpl.MAX_REPAIRS,
				countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		assertEquals(State.AUTH_CONFIRMED, record(c1).state);
		assertNotNull(record(c1).dialPrivateKey);
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
	}

	@Test
	public void testAnOpenDialFollowsOnlyADayWithoutTheAuthorizedPath()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		attachRecordingDialer();
		manager.tickForTest();
		int offers = countSent(c1, OnionAuthRecords.TYPE_OFFER);
		for (int i = 0; i < 4 * 23; i++) {
			now.addAndGet(OnionClientAuthManagerImpl.TICK_MS);
			manager.tickForTest();
		}
		assertTrue(openCalls.isEmpty());
		manager.dialSucceeded(c1.getId(), PEER_ONION);
		for (int i = 0; i < 4 * 23; i++) {
			now.addAndGet(OnionClientAuthManagerImpl.TICK_MS);
			manager.tickForTest();
		}
		assertTrue(openCalls.isEmpty());
		List<Long> times = new ArrayList<>();
		for (int i = 0; i < 4 * 24 * 20; i++) {
			now.addAndGet(OnionClientAuthManagerImpl.TICK_MS);
			int before = openCalls.size();
			manager.tickForTest();
			if (openCalls.size() > before) times.add(now.get());
		}
		assertTrue(times.size() >= 3);
		assertTrue(times.size() <= 12);
		for (int i = 1; i < times.size(); i++) {
			long gap = times.get(i) - times.get(i - 1);
			assertTrue(gap >= OnionClientAuthManagerImpl.REPAIR_DELAY_MS);
			assertTrue(gap <= OnionClientAuthManagerImpl.MAX_OPEN_DIAL_DELAY_MS
					+ OnionClientAuthManagerImpl.TICK_MS);
		}
		assertEquals(offers + 1, countSent(c1, OnionAuthRecords.TYPE_OFFER));
		assertTrue(record(c1).lockedIn());
		assertFalse(manager.acceptsInbound(c1.getId(), false));
		manager.inboundViaAuthorizedService(c1.getId());
		int calls = openCalls.size();
		for (int i = 0; i < 4 * 20; i++) {
			now.addAndGet(OnionClientAuthManagerImpl.TICK_MS);
			manager.tickForTest();
		}
		assertEquals(calls, openCalls.size());
	}

	@Test
	public void testRepairDialsAreRequestedOnlyWithRepairsAndTheResend()
			throws Exception {
		List<ContactId> repaired = Collections.synchronizedList(
				new ArrayList<>());
		manager.attachTor(control, 4444,
				new OnionClientAuthManagerImpl.ProbeDialer() {
					@Override
					public void dial(ContactId c) {
						dialed.add(c);
					}

					@Override
					public void repair(ContactId c) {
						repaired.add(c);
					}
				});
		commitWithoutAnAnswer(c1, g1, PEER_ONION, peerPub(1));
		assertTrue(dialed.contains(c1.getId()));
		assertTrue(repaired.isEmpty());
		for (int i = 0; i < OnionClientAuthManagerImpl.MAX_REPAIRS + 2; i++) {
			now.addAndGet(OnionClientAuthManagerImpl.MAX_REPAIR_DELAY_MS);
			manager.tickForTest();
		}
		assertEquals(Collections.nCopies(OnionClientAuthManagerImpl.MAX_REPAIRS,
				c1.getId()), repaired);
		receive(c1, g1, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub(1))));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
		manager.tickForTest();
		assertEquals(OnionClientAuthManagerImpl.MAX_REPAIRS, repaired.size());
		now.addAndGet(OnionClientAuthManagerImpl.REPAIR_DELAY_MS);
		manager.tickForTest();
		assertEquals(OnionClientAuthManagerImpl.MAX_REPAIRS + 1,
				repaired.size());
		now.addAndGet(OnionClientAuthManagerImpl.REPAIR_DELAY_MS);
		receive(c1, g1, OnionAuthRecords.probeSuccess(1));
		receive(c1, g1, OnionAuthRecords.ready(1));
		for (int i = 0; i < 5; i++) {
			manager.dialSucceeded(c1.getId(), PEER_ONION);
			now.addAndGet(OnionClientAuthManagerImpl.MAX_REPAIR_DELAY_MS
					- OnionClientAuthManagerImpl.TICK_MS);
			manager.tickForTest();
		}
		assertEquals(OnionClientAuthManagerImpl.MAX_REPAIRS + 1,
				repaired.size());
		assertEquals(c1.getId(), repaired.get(repaired.size() - 1));
	}

	@Test
	public void testASilentLockedInContactGetsBoundedRepairDials()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		attachRecordingDialer();
		List<Long> repairs = new ArrayList<>();
		List<Long> opens = new ArrayList<>();
		long start = now.get();
		while (now.get() - start < TimeUnit.DAYS.toMillis(30)) {
			now.addAndGet(OnionClientAuthManagerImpl.TICK_MS);
			int r = repairCalls.size();
			int o = openCalls.size();
			manager.tickForTest();
			if (repairCalls.size() > r) repairs.add(now.get());
			if (openCalls.size() > o) opens.add(now.get());
		}
		assertTrue(repairs.get(0) - start
				< OnionClientAuthManagerImpl.SILENCE_MS);
		List<Long> silence = repairs.subList(1, repairs.size());
		assertTrue(silence.size() >= 3);
		assertTrue(silence.size() <= 12);
		assertTrue(silence.get(0) - start
				>= OnionClientAuthManagerImpl.SILENCE_MS);
		for (int i = 1; i < silence.size(); i++) {
			long gap = silence.get(i) - silence.get(i - 1);
			assertTrue(gap >= OnionClientAuthManagerImpl.REPAIR_DELAY_MS);
			assertTrue(gap <= OnionClientAuthManagerImpl.MAX_OPEN_DIAL_DELAY_MS
					+ OnionClientAuthManagerImpl.TICK_MS);
		}
		for (Long t : opens) assertFalse(silence.contains(t));
		assertEquals(silence.size(), record(c1).silenceRepairs);
		assertTrue(record(c1).lockedIn());
		assertFalse(manager.acceptsInbound(c1.getId(), false));
		manager.inboundViaAuthorizedService(c1.getId());
		int calls = repairCalls.size();
		for (int i = 0; i < 4 * 23; i++) {
			now.addAndGet(OnionClientAuthManagerImpl.TICK_MS);
			manager.tickForTest();
		}
		assertEquals(calls, repairCalls.size());
		assertEquals(0, record(c1).silenceRepairs);
	}

	@Test
	public void testARotationCarryingAClientKeyChangesNoAuthorization()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		String onion = service().onion;
		control.commands.clear();
		receive(c1, g1, OnionAuthRecords.rotate(2, PEER_ONION_2, peerPub(9)));
		receive(c1, g1, OnionAuthRecords.rotate(3, null, peerPub(8)));
		OnionAuthRecord r = record(c1);
		assertArrayEquals(peerPub(1), r.peerPublicKey);
		assertEquals(PEER_ONION_2, r.peerOnion);
		assertEquals(3, r.peerGen);
		assertEquals(3, lastSent(c1, OnionAuthRecords.TYPE_ROTATE_ACK)
				.keyVersion);
		assertFalse(control.commands.contains("DEL " + onion));
		assertTrue(control.authorizes(onion, peerPub(1)));
		assertFalse(control.authorizes(onion, peerPub(9)));
		assertFalse(control.authorizes(onion, peerPub(8)));
		assertTrue(record(c1).lockedIn());
	}

	@Test
	public void testACommitThatArrivesBeforeThePeersProbeSuccessLocksIn()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		receive(c1, g1, OnionAuthRecords.ready(1));
		manager.dialSucceeded(c1.getId(), PEER_ONION);
		assertEquals(0, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		receive(c1, g1, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub(1))));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
		assertTrue(record(c1).peerProbeSucceeded);
		assertEquals(1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		receive(c1, g1, OnionAuthRecords.probeSuccess(1));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
	}

	@Test
	public void testACommitBeforeOurOwnProbeSucceededChangesNothing()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		receive(c1, g1, OnionAuthRecords.ready(1));
		receive(c1, g1, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub(1))));
		assertEquals(State.AUTH_NEGOTIATING, record(c1).state);
		assertFalse(record(c1).peerProbeSucceeded);
		assertFalse(record(c1).peerCommitReceived);
		assertEquals(0, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		assertTrue(manager.acceptsInbound(c1.getId(), false));
	}

	@Test
	public void testASentCommitSurvivesTheNegotiationTimeoutAndAReset()
			throws Exception {
		commitWithoutAnAnswer(c1, g1, PEER_ONION, peerPub(1));
		peerAdvertisesSupport(c2);
		receive(c2, g2, OnionAuthRecords.offer(1, PEER_ONION_2, peerPub(2)));
		String onion = service().onion;
		now.addAndGet(OnionClientAuthManagerImpl.NEGOTIATION_TIMEOUT_MS + 1);
		manager.tickForTest();
		manager.resetNegotiation(c1.getId());
		OnionAuthRecord r = record(c1);
		assertEquals(State.AUTH_CONFIRMED, r.state);
		assertTrue(r.commitSent);
		assertNotNull(r.dialPrivateKey);
		assertEquals(PEER_ONION, r.peerOnion);
		assertArrayEquals(peerPub(1), r.peerPublicKey);
		assertEquals(onion, service().onion);
		assertTrue(control.authorizes(onion, peerPub(1)));
		assertFalse(control.commands.contains("UNCRED " + PEER_ONION));
		assertEquals(State.LEGACY, record(c2).state);
		assertFalse(control.authorizes(onion, peerPub(2)));
		assertTrue(control.commands.contains("UNCRED " + PEER_ONION_2));
	}

	@Test
	public void testASentCommitDialsOnlyTheAuthorizedAddress()
			throws Exception {
		commitWithoutAnAnswer(c1, g1, PEER_ONION, peerPub(1));
		assertEquals(PEER_ONION, manager.getDialOnion(c1.getId()));
		assertTrue(manager.acceptsInbound(c1.getId(), false));
		assertTrue(manager.acceptsInbound(c1.getId(), true));
		OnionClientAuthManagerImpl restarted = restartedManager();
		assertEquals(PEER_ONION, restarted.getDialOnion(c1.getId()));
		assertNull(restarted.getDialOnion(c2.getId()));
	}

	@Test
	public void testAnUnansweredCommitIsRepairedWithABoundedBackoff()
			throws Exception {
		commitWithoutAnAnswer(c1, g1, PEER_ONION, peerPub(1));
		long committedAt = now.get();
		List<Long> repairs = new ArrayList<>();
		int seen = countSent(c1, OnionAuthRecords.TYPE_PROBE_SUCCESS);
		while (now.get() - committedAt < TimeUnit.DAYS.toMillis(14)) {
			now.addAndGet(OnionClientAuthManagerImpl.TICK_MS);
			manager.tickForTest();
			int ps = countSent(c1, OnionAuthRecords.TYPE_PROBE_SUCCESS);
			if (ps > seen) repairs.add(now.get());
			seen = ps;
		}
		assertEquals(OnionClientAuthManagerImpl.MAX_REPAIRS, repairs.size());
		assertEquals(1 + OnionClientAuthManagerImpl.MAX_REPAIRS,
				countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		assertTrue(repairs.get(0) - committedAt
				>= OnionClientAuthManagerImpl.REPAIR_DELAY_MS);
		for (int i = 1; i < repairs.size(); i++) {
			long gap = repairs.get(i) - repairs.get(i - 1);
			long previous = i == 1 ? 0 : repairs.get(i - 1) - repairs.get(i - 2);
			assertTrue(gap >= previous);
			assertTrue(gap <= OnionClientAuthManagerImpl.MAX_REPAIR_DELAY_MS
					+ OnionClientAuthManagerImpl.TICK_MS);
		}
		OnionAuthRecords.Record commit = lastSent(c1,
				OnionAuthRecords.TYPE_COMMIT);
		assertEquals(PEER_ONION, commit.onion);
		assertEquals(State.AUTH_CONFIRMED, record(c1).state);
	}

	@Test
	public void testARotationByThePeerMovesTheRepairsToTheNewAddressButAddsNone()
			throws Exception {
		commitWithoutAnAnswer(c1, g1, PEER_ONION, peerPub(1));
		for (int i = 0; i < 3; i++) {
			now.addAndGet(OnionClientAuthManagerImpl.MAX_REPAIR_DELAY_MS);
			manager.tickForTest();
		}
		assertEquals(4, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		receive(c1, g1, OnionAuthRecords.rotate(2, PEER_ONION_2, null));
		assertEquals(PEER_ONION_2, manager.getDialOnion(c1.getId()));
		manager.tickForTest();
		assertEquals(4, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		now.addAndGet(OnionClientAuthManagerImpl.REPAIR_DELAY_MS);
		manager.tickForTest();
		assertEquals(5, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		assertEquals(PEER_ONION_2,
				lastSent(c1, OnionAuthRecords.TYPE_COMMIT).onion);
		for (int i = 0; i < 2 * OnionClientAuthManagerImpl.MAX_REPAIRS; i++) {
			receive(c1, g1, OnionAuthRecords.rotate(3 + i,
					i % 2 == 0 ? PEER_ONION : PEER_ONION_2, null));
			now.addAndGet(OnionClientAuthManagerImpl.MAX_REPAIR_DELAY_MS);
			manager.tickForTest();
		}
		assertEquals(1 + OnionClientAuthManagerImpl.MAX_REPAIRS,
				countSent(c1, OnionAuthRecords.TYPE_COMMIT));
	}

	@Test
	public void testARotationOnThisSideRestartsTheRepairs() throws Exception {
		commitWithoutAnAnswer(c1, g1, PEER_ONION, peerPub(1));
		for (int i = 0; i < OnionClientAuthManagerImpl.MAX_REPAIRS + 2; i++) {
			now.addAndGet(OnionClientAuthManagerImpl.MAX_REPAIR_DELAY_MS);
			manager.tickForTest();
		}
		int commits = countSent(c1, OnionAuthRecords.TYPE_COMMIT);
		assertEquals(1 + OnionClientAuthManagerImpl.MAX_REPAIRS, commits);
		manager.rotateAuthorizedService();
		assertEquals(2, service().gen);
		now.addAndGet(OnionClientAuthManagerImpl.REPAIR_DELAY_MS);
		manager.tickForTest();
		assertEquals(commits + 1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		assertEquals(2, lastSent(c1, OnionAuthRecords.TYPE_COMMIT).keyVersion);
	}

	@Test
	public void testALockedInSideAnswersOneCommitAndPacesItsOtherAnswers()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		int before = countSent(c1, OnionAuthRecords.TYPE_COMMIT);
		byte[] fp = OnionAuthCommitCheck.fingerprint(peerPub(1));
		for (int i = 0; i < 3; i++) {
			receive(c1, g1, OnionAuthRecords.commit(1, service().onion, fp));
		}
		assertEquals(before + 1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		receive(c1, g1, OnionAuthRecords.ready(1));
		receive(c1, g1, OnionAuthRecords.probeSuccess(1));
		assertEquals(before + 1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		now.addAndGet(OnionClientAuthManagerImpl.REPAIR_DELAY_MS);
		receive(c1, g1, OnionAuthRecords.commit(1, service().onion, fp));
		assertEquals(before + 1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		receive(c1, g1, OnionAuthRecords.probeSuccess(1));
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		assertEquals(before + 2, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
	}

	@Test
	public void testALockedInSideResendsItsCommitOnceAfterLockingIn()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		int before = countSent(c1, OnionAuthRecords.TYPE_COMMIT);
		manager.tickForTest();
		assertEquals(before, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		now.addAndGet(OnionClientAuthManagerImpl.REPAIR_DELAY_MS);
		manager.tickForTest();
		assertEquals(before + 1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		for (int i = 0; i < 10; i++) {
			now.addAndGet(OnionClientAuthManagerImpl.MAX_REPAIR_DELAY_MS);
			manager.tickForTest();
		}
		assertEquals(before + 1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		receive(c1, g1, OnionAuthRecords.commit(1, service().onion,
				OnionAuthCommitCheck.fingerprint(peerPub(1))));
		assertEquals(before + 1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
	}

	@Test
	public void testAPairLockedInByAnEarlierVersionResendsItsCommitOnce()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		OnionAuthRecord r = record(c1);
		r.commitResent = false;
		r.nextRepairMs = 0;
		r.repairs = 0;
		store.save(null, r);
		int before = countSent(c1, OnionAuthRecords.TYPE_COMMIT);
		OnionClientAuthManagerImpl restarted = restartedManager();
		restarted.tickForTest();
		restarted.tickForTest();
		assertEquals(before + 1, countSent(c1, OnionAuthRecords.TYPE_COMMIT));
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
	}

	private int publishes() {
		int n = 0;
		synchronized (control.commands) {
			for (String c : control.commands) if (c.startsWith("ADD ")) n++;
		}
		return n;
	}

	@Test
	public void testAPeerInANewRoundCannotRepublishTheServiceWithEveryKey()
			throws Exception {
		activate(c1, g1, PEER_ONION, peerPub(1));
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(2)));
		assertTrue(record(c1).rekeying);
		String onion = service().onion;
		int before = publishes();
		for (int i = 3; i < 13; i++) {
			receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION,
					peerPub(i)));
		}
		assertEquals(before, publishes());
		assertArrayEquals(peerPub(2), record(c1).peerPublicKey);
		assertTrue(control.authorizes(onion, peerPub(2)));
		assertTrue(record(c1).rekeying);
		assertFalse(manager.acceptsInbound(c1.getId(), false));
		now.addAndGet(OnionClientAuthManagerImpl.REKEY_INTERVAL_MS);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(13)));
		assertArrayEquals(peerPub(13), record(c1).peerPublicKey);
		assertEquals(before + 1, publishes());
		assertTrue(control.authorizes(onion, peerPub(13)));
		assertFalse(control.authorizes(onion, peerPub(2)));
		assertTrue(record(c1).rekeying);
		assertEquals(State.AUTH_REQUIRED, record(c1).state);
	}

	@Test
	public void testANegotiatingPeerReplacesItsKeyAtMostOnceADay()
			throws Exception {
		peerAdvertisesSupport(c1);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(1)));
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION, peerPub(2)));
		assertArrayEquals(peerPub(2), record(c1).peerPublicKey);
		String onion = service().onion;
		int before = publishes();
		for (int i = 3; i < 13; i++) {
			receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION,
					peerPub(i)));
		}
		assertEquals(before, publishes());
		assertArrayEquals(peerPub(2), record(c1).peerPublicKey);
		assertTrue(control.authorizes(onion, peerPub(2)));
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION_2, peerPub(2)));
		assertEquals(PEER_ONION_2, record(c1).peerOnion);
		now.addAndGet(OnionClientAuthManagerImpl.REKEY_INTERVAL_MS);
		receive(c1, g1, OnionAuthRecords.offer(1, PEER_ONION_2,
				peerPub(13)));
		assertArrayEquals(peerPub(13), record(c1).peerPublicKey);
		assertEquals(before + 1, publishes());
		assertTrue(control.authorizes(onion, peerPub(13)));
		assertEquals(State.AUTH_NEGOTIATING, record(c1).state);
	}
}
