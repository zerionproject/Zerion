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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives the manager through the activation protocol against a recording
 * Tor control and an in-memory store, with the peer's records constructed
 * directly, and pins the invariants of the design: no dial of the open
 * address once committed, refusal of a committed contact over the open
 * service, commits bound to the generation, immediate revocation with the
 * old service deleted and the new set excluding the revoked key, rotation
 * with overlap and acknowledgement, pre-commit abort cleanup, and the
 * re-feed of Tor from persisted state after a restart.
 */
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

	private final Contact c1 = TestUtils.getContact();
	private final Contact c2 = TestUtils.getContact();
	private final Group g1 = TestUtils.getGroup(OnionAuthRecords.CLIENT_ID,
			OnionAuthRecords.MAJOR_VERSION);
	private final Group g2 = TestUtils.getGroup(OnionAuthRecords.CLIENT_ID,
			OnionAuthRecords.MAJOR_VERSION);
	private final Map<MessageId, BdfList> bodies = new HashMap<>();
	private final Map<ContactId, TransportProperties> remote = new HashMap<>();
	/** Records this device sent, per contact, in order. */
	private final Map<ContactId, List<OnionAuthRecords.Record>> sent =
			new HashMap<>();

	private OnionClientAuthManagerImpl manager;
	/** The real database refuses a transaction inside another on a thread. */
	private final ThreadLocal<Integer> txnDepth =
			ThreadLocal.withInitial(() -> 0);

	private Transaction begin() {
		if (txnDepth.get() > 0) throw new IllegalStateException(
				"nested transaction");
		txnDepth.set(txnDepth.get() + 1);
		return new Transaction(null, false);
	}


	/** Tor control that records commands and mints valid addresses. */
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
				Runnable::run, scheduler);
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

	private byte[] peerPub(int fill) {
		byte[] b = new byte[32];
		Arrays.fill(b, (byte) fill);
		return b;
	}

	/** Runs the whole activation for c1 and returns at AUTH_REQUIRED. */
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
	public void testCommitAfterRevocationIsIgnoredAndRevocationIsImmediate()
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
		assertNull(service().oldOnion);
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
		assertTrue(control.commands.contains("DEL " + oldOnion));
		assertTrue(control.commands.contains("ADD " + newOnion + " keys=1 port=4444"));
		assertFalse(control.authorizes(newOnion, peerPub(1)));
		assertTrue(control.authorizes(newOnion, peerPub(2)));
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

	/* SC-TOR-06: credentials re-installed after a Tor reconfiguration */

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
}
