package org.zerionproject.core.plugin.tor.auth;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.api.Invocation;
import org.jmock.lib.action.CustomAction;
import org.jmock.lib.concurrent.Synchroniser;
import org.junit.Test;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.AgreementPrivateKey;
import org.zerionproject.core.api.crypto.AgreementPublicKey;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.CommitAction;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.EventAction;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.TaskAction;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.transport.ZtpPoller;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
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
import static org.zerionproject.core.api.plugin.OnionClientAuthManager.State.AUTH_CONFIRMED;
import static org.zerionproject.core.api.plugin.OnionClientAuthManager.State.AUTH_NEGOTIATING;
import static org.zerionproject.core.api.plugin.OnionClientAuthManager.State.AUTH_REQUIRED;
import static org.zerionproject.core.plugin.tor.auth.OnionAuthRecords.CLIENT_ID;
import static org.zerionproject.core.plugin.tor.auth.OnionAuthRecords.MAJOR_VERSION;
import static org.zerionproject.core.plugin.tor.auth.OnionAuthRecords.TYPE_COMMIT;
import static org.zerionproject.core.plugin.tor.auth.OnionAuthRecords.TYPE_PROBE_SUCCESS;
import static org.zerionproject.core.plugin.tor.auth.OnionClientAuthManagerImpl.NEGOTIATION_TIMEOUT_MS;
import static org.zerionproject.core.plugin.tor.auth.OnionClientAuthManagerImpl.TICK_MS;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class OnionClientAuthPairTest {

	private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
	private static final String KEY_PREFIX = "KEY:";
	private static final int AUTHORIZED_PORT = 4444;
	private static final int SESSIONS_PER_PUMP = 6;
	private static final int ROUNDS_PER_SESSION = 40;
	private static final long HOUR_MS = TimeUnit.HOURS.toMillis(1);
	private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);
	private static final int MAX_COMMITS_PER_SIDE = 4;

	private final Mockery context = new Mockery() {{
		setThreadingPolicy(new Synchroniser());
	}};
	private final SecureRandom random = new SecureRandom();
	private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
	private final Clock clock = new Clock() {
		@Override
		public long currentTimeMillis() {
			return now.get();
		}

		@Override
		public void sleep(long milliseconds) {
		}
	};
	private final Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Group sink = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Map<MessageId, BdfList> bodies = new HashMap<>();
	private final Map<String, byte[]> publicKeys = new HashMap<>();
	private final Map<String, List<byte[]>> services = new HashMap<>();
	private int minted = 0;
	private int sides = 0;
	private boolean unsettled = false;
	private boolean network = true;
	private boolean aDesignated = true;
	@Nullable
	private Side guarded = null;
	private boolean breach = false;
	private Side a;
	private Side b;
	private Side dialer;

	private interface Endpoint {

		void open(Transaction txn) throws DbException;

		void attach(OnionServiceControl tor);

		void detach();

		void incoming(Transaction txn, Message m) throws Exception;

		@Nullable
		String dialOnion(ContactId c);

		boolean accepts(ContactId c, boolean viaAuthorizedService);

		void dialed(ContactId c, String onion);

		void inboundAuthorized(ContactId c);

		void tick();

		void rotate() throws DbException;

		void remove(Transaction txn, Contact c) throws DbException;
	}

	private interface Build {

		Endpoint start(Side s);
	}

	private final Build fixed = fixedWith(OnionAuthDebugHooks.NONE);

	private Build fixedWith(OnionAuthDebugHooks hooks) {
		return s -> {
			OnionClientAuthManagerImpl m = new OnionClientAuthManagerImpl(s.db,
					s.clientHelper, s.groupFactory, s.parser, s.store, s.crypto,
					() -> s.properties, s.eventBus, clock, Runnable::run,
					s.scheduler, java.util.Optional.of(hooks));
			return new Endpoint() {
				@Override
				public void open(Transaction txn) throws DbException {
					m.onDatabaseOpened(txn);
				}

				@Override
				public void attach(OnionServiceControl tor) {
					m.attachTor(tor, AUTHORIZED_PORT,
							new OnionClientAuthManagerImpl.ProbeDialer() {
								@Override
								public void dial(ContactId c) {
								}

								@Override
								public void repair(ContactId c) {
									s.repairCalls++;
									s.pendingRepairs++;
								}

								@Override
								public void repairOpen(ContactId c) {
									s.openCalls++;
									s.pendingOpenDials++;
								}
							});
				}

				@Override
				public void detach() {
					m.detachTor();
				}

				@Override
				public void incoming(Transaction txn, Message msg) throws Exception {
					m.incomingMessage(txn, msg, new Metadata());
				}

				@Override
				public String dialOnion(ContactId c) {
					return m.getDialOnion(c);
				}

				@Override
				public boolean accepts(ContactId c, boolean viaAuthorizedService) {
					return m.acceptsInbound(c, viaAuthorizedService);
				}

				@Override
				public void dialed(ContactId c, String onion) {
					m.dialSucceeded(c, onion);
				}

				@Override
				public void inboundAuthorized(ContactId c) {
					m.inboundViaAuthorizedService(c);
				}

				@Override
				public void tick() {
					m.tickForTest();
				}

				@Override
				public void rotate() throws DbException {
					m.rotateAuthorizedService();
				}

				@Override
				public void remove(Transaction txn, Contact c) throws DbException {
					m.removingContact(txn, c);
				}
			};
		};
	}

	private final Build shipped = s -> {
		ShippedOnionClientAuthManager m = new ShippedOnionClientAuthManager(
				s.db, s.clientHelper, s.groupFactory, s.parser, s.store,
				s.crypto, () -> s.properties, s.eventBus, clock, Runnable::run,
				s.scheduler);
		return new Endpoint() {
			@Override
			public void open(Transaction txn) throws DbException {
				m.onDatabaseOpened(txn);
			}

			@Override
			public void attach(OnionServiceControl tor) {
				m.attachTor(tor, AUTHORIZED_PORT, c -> {
				});
			}

			@Override
			public void detach() {
				m.detachTor();
			}

			@Override
			public void incoming(Transaction txn, Message msg) throws Exception {
				m.incomingMessage(txn, msg, new Metadata());
			}

			@Override
			public String dialOnion(ContactId c) {
				return m.getDialOnion(c);
			}

			@Override
			public boolean accepts(ContactId c, boolean viaAuthorizedService) {
				return m.acceptsInbound(c, viaAuthorizedService);
			}

			@Override
			public void dialed(ContactId c, String onion) {
				m.dialSucceeded(c, onion);
			}

			@Override
			public void inboundAuthorized(ContactId c) {
				m.inboundViaAuthorizedService(c);
			}

			@Override
			public void tick() {
				m.tickForTest();
			}

			@Override
			public void rotate() throws DbException {
				m.rotateAuthorizedService();
			}

			@Override
			public void remove(Transaction txn, Contact c) throws DbException {
				m.removingContact(txn, c);
			}
		};
	};

	private final class Tor implements OnionServiceControl {

		final Set<String> published = new HashSet<>();
		final Map<String, byte[]> credentials = new HashMap<>();
		int failRemoves = 0;
		int failPublishes = 0;
		int maxPublished = 0;

		@Override
		public Published publish(@Nullable String privateKey, int localPort,
				int remotePort, Collection<byte[]> clientPublicKeys)
				throws java.io.IOException {
			if (failPublishes > 0) {
				failPublishes--;
				throw new java.io.IOException("publish failed");
			}
			String onion = privateKey == null ? mint()
					: privateKey.substring(KEY_PREFIX.length());
			if (published.contains(onion)) {
				throw new java.io.IOException("550 Onion address collision");
			}
			services.put(onion, new ArrayList<>(clientPublicKeys));
			published.add(onion);
			maxPublished = Math.max(maxPublished, published.size());
			return new Published(onion, KEY_PREFIX + onion);
		}

		@Override
		public void remove(String onion) throws java.io.IOException {
			if (failRemoves > 0) {
				failRemoves--;
				throw new java.io.IOException("control connection failed");
			}
			if (!published.contains(onion)) {
				throw new java.io.IOException("552 Unknown Onion Service id");
			}
			published.remove(onion);
			services.remove(onion);
		}

		@Override
		public void addClientKey(String onion, byte[] clientPrivateKey) {
			credentials.put(onion, clientPrivateKey.clone());
		}

		@Override
		public void removeClientKey(String onion) {
			credentials.remove(onion);
		}

		void stop() {
			for (String onion : published) services.remove(onion);
			published.clear();
			credentials.clear();
		}
	}

	private final class Side {

		Build build;
		final Contact contact;
		final InMemorySettingsManager settings = new InMemorySettingsManager();
		final OnionAuthStore store = new OnionAuthStore(settings);
		final DatabaseComponent db;
		final ClientHelper clientHelper;
		final ContactGroupFactory groupFactory;
		final MetadataParser parser;
		final CryptoComponent crypto;
		final TaskScheduler scheduler;
		final EventBus eventBus;
		final TransportPropertyManager properties;
		final String openOnion = mint();
		final List<BdfList> outbox = new ArrayList<>();
		final List<BdfList> held = new ArrayList<>();
		final Set<Integer> holding = new HashSet<>();
		final Map<Integer, Integer> dropping = new HashMap<>();
		final Map<Integer, Integer> sent = new HashMap<>();
		int depth = 0;
		int repairCalls = 0;
		int pendingRepairs = 0;
		int repairDials = 0;
		int openCalls = 0;
		int pendingOpenDials = 0;
		int openDials = 0;
		Side other;
		Tor tor = new Tor();
		Endpoint manager;

		Side(int peerId, Build build) throws Exception {
			this.build = build;
			String name = "side" + (sides++);
			boolean designated = (peerId == 1) == aDesignated;
			byte[] low = new byte[AuthorId.LENGTH];
			byte[] high = new byte[AuthorId.LENGTH];
			Arrays.fill(high, (byte) 0x70);
			Author them = new Author(new AuthorId(designated ? high : low),
					Author.FORMAT_VERSION, "them", getAuthor().getPublicKey());
			contact = getContact(new ContactId(peerId), them,
					new AuthorId(designated ? low : high), true);
			db = context.mock(DatabaseComponent.class, name + "Db");
			clientHelper = context.mock(ClientHelper.class, name + "Helper");
			groupFactory = context.mock(ContactGroupFactory.class,
					name + "Groups");
			parser = context.mock(MetadataParser.class, name + "Parser");
			crypto = context.mock(CryptoComponent.class, name + "Crypto");
			scheduler = context.mock(TaskScheduler.class, name + "Scheduler");
			eventBus = context.mock(EventBus.class, name + "Events");
			properties = context.mock(TransportPropertyManager.class,
					name + "Properties");
			expect();
		}

		private void expect() throws Exception {
			context.checking(new Expectations() {{
				allowing(db).transaction(with(any(boolean.class)),
						with(any(DbRunnable.class)));
				will(new CustomAction("runs the transaction") {
					@Override
					public Object invoke(Invocation i) throws Throwable {
						Transaction txn = begin();
						try {
							((DbRunnable<?>) i.getParameter(1)).run(txn);
						} finally {
							depth--;
						}
						commit(txn);
						return null;
					}
				});
				allowing(db).transactionWithResult(with(any(boolean.class)),
						with(any(DbCallable.class)));
				will(new CustomAction("runs the transaction with a result") {
					@Override
					public Object invoke(Invocation i) throws Throwable {
						Transaction txn = begin();
						Object result;
						try {
							result = ((DbCallable<?, ?>) i.getParameter(1))
									.call(txn);
						} finally {
							depth--;
						}
						commit(txn);
						return result;
					}
				});
				allowing(db).getContacts(with(any(Transaction.class)));
				will(returnValue(Collections.singletonList(contact)));
				allowing(db).getContact(with(any(Transaction.class)),
						with(contact.getId()));
				will(returnValue(contact));
				allowing(db).containsGroup(with(any(Transaction.class)),
						with(any(GroupId.class)));
				will(returnValue(true));
				allowing(db).getGroups(with(any(Transaction.class)),
						with(CLIENT_ID), with(MAJOR_VERSION));
				will(returnValue(Collections.singletonList(group)));
				allowing(groupFactory).createContactGroup(CLIENT_ID,
						MAJOR_VERSION, contact);
				will(returnValue(group));
				allowing(groupFactory).createContactGroup(with(CLIENT_ID),
						with(MAJOR_VERSION), with(any(Contact.class)));
				will(returnValue(sink));
				allowing(db).removeGroup(with(any(Transaction.class)),
						with(any(Group.class)));
				allowing(clientHelper).setContactId(
						with(any(Transaction.class)), with(any(GroupId.class)),
						with(any(ContactId.class)));
				allowing(clientHelper).getContactId(
						with(any(Transaction.class)), with(group.getId()));
				will(returnValue(contact.getId()));
				allowing(clientHelper).createMessage(with(any(GroupId.class)),
						with(any(long.class)), with(any(BdfList.class)));
				will(new CustomAction("creates a record") {
					@Override
					public Object invoke(Invocation i) {
						Message m = new Message(new MessageId(getRandomId()),
								(GroupId) i.getParameter(0),
								(long) i.getParameter(1), new byte[1]);
						bodies.put(m.getId(), (BdfList) i.getParameter(2));
						return m;
					}
				});
				allowing(clientHelper).addLocalMessage(
						with(any(Transaction.class)), with(any(Message.class)),
						with(any(BdfDictionary.class)), with(any(boolean.class)),
						with(any(boolean.class)));
				will(new CustomAction("queues the record for the peer") {
					@Override
					public Object invoke(Invocation i) throws Throwable {
						if (!((Message) i.getParameter(1)).getGroupId()
								.equals(group.getId())) {
							return null;
						}
						BdfList body = bodies.get(
								((Message) i.getParameter(1)).getId());
						outbox.add(body);
						sent.merge(type(body), 1, Integer::sum);
						return null;
					}
				});
				allowing(clientHelper).toList(with(any(Message.class)),
						with(any(boolean.class)));
				will(new CustomAction("returns the record") {
					@Override
					public Object invoke(Invocation i) {
						return bodies.get(((Message) i.getParameter(0)).getId());
					}
				});
				allowing(parser).parse(with(any(Metadata.class)));
				will(returnValue(new BdfDictionary()));
				allowing(crypto).generateAgreementKeyPair();
				will(new CustomAction("generates a key pair") {
					@Override
					public Object invoke(Invocation i) {
						byte[] priv = new byte[32];
						byte[] pub = new byte[32];
						random.nextBytes(priv);
						random.nextBytes(pub);
						publicKeys.put(Arrays.toString(priv), pub);
						return new KeyPair(new AgreementPublicKey(pub),
								new AgreementPrivateKey(priv));
					}
				});
				allowing(scheduler).scheduleWithFixedDelay(
						with(any(Runnable.class)), with(any(Executor.class)),
						with(any(long.class)), with(any(long.class)),
						with(any(TimeUnit.class)));
				will(returnValue(null));
				allowing(eventBus).broadcast(with(any(Event.class)));
				allowing(properties).getRemoteProperties(TorConstants.ID);
				will(new CustomAction("returns the peer's support") {
					@Override
					public Object invoke(Invocation i) {
						begin();
						depth--;
						TransportProperties p = new TransportProperties();
						p.put(TorConstants.PROP_ONION_AUTH_SUPPORTED, "1");
						Map<ContactId, TransportProperties> m = new HashMap<>();
						m.put(contact.getId(), p);
						return m;
					}
				});
			}});
		}

		Transaction begin() {
			if (depth > 0) throw new IllegalStateException("nested transaction");
			depth++;
			return new Transaction(null, false);
		}

		void start() throws Exception {
			manager = build.start(this);
			Transaction txn = begin();
			try {
				manager.open(txn);
			} finally {
				depth--;
			}
			commit(txn);
			manager.attach(tor);
		}

		void restart() throws Exception {
			manager.detach();
			tor.stop();
			tor = new Tor();
			start();
		}

		void upgrade(Build next) throws Exception {
			build = next;
			restart();
		}

		void deliver(BdfList body) throws Exception {
			Message m = new Message(new MessageId(getRandomId()), group.getId(),
					now.get(), new byte[1]);
			bodies.put(m.getId(), body);
			Transaction txn = begin();
			try {
				manager.incoming(txn, m);
			} finally {
				depth--;
			}
			commit(txn);
		}

		int flush() throws Exception {
			List<BdfList> batch = new ArrayList<>(outbox);
			outbox.clear();
			int delivered = 0;
			for (BdfList body : batch) {
				int t = type(body);
				if (holding.contains(t)) {
					held.add(body);
					continue;
				}
				Integer drops = dropping.get(t);
				if (drops != null && drops > 0) {
					dropping.put(t, drops - 1);
					continue;
				}
				other.deliver(body);
				delivered++;
			}
			return delivered;
		}

		void hold(int type) {
			holding.add(type);
		}

		void release() {
			holding.clear();
			outbox.addAll(0, held);
			held.clear();
		}

		void drop(int type) {
			dropping.merge(type, 1, Integer::sum);
		}

		OnionAuthRecord record() throws DbException {
			return store.load(null, contact.getId());
		}

		State state() throws DbException {
			return record().state;
		}

		int sent(int type) {
			return sent.getOrDefault(type, 0);
		}

		void atGeneration(long gen) throws DbException {
			if (gen == 0) return;
			OnionAuthStore.ServiceRecord svc =
					new OnionAuthStore.ServiceRecord();
			svc.gen = gen;
			store.saveService(null, svc);
		}

		long generation() throws DbException {
			return store.loadService(null).gen;
		}

		void remove(Contact c) throws Exception {
			Transaction txn = begin();
			try {
				manager.remove(txn, c);
			} finally {
				depth--;
			}
			commit(txn);
		}

		Contact addAuthorizedContact(int id) throws Exception {
			Contact x = getContact(new ContactId(id), getAuthor(),
					new AuthorId(getRandomId()), true);
			OnionAuthRecord r = new OnionAuthRecord(x.getId());
			r.state = AUTH_REQUIRED;
			r.localGen = generation();
			r.peerGen = 1;
			r.dialPrivateKey = new byte[32];
			random.nextBytes(r.dialPrivateKey);
			r.dialPublicKey = new byte[32];
			random.nextBytes(r.dialPublicKey);
			r.peerPublicKey = new byte[32];
			random.nextBytes(r.peerPublicKey);
			r.peerOnion = mint();
			r.readyReceived = true;
			r.probeSucceeded = true;
			r.peerProbeSucceeded = true;
			r.commitSent = true;
			r.peerCommitReceived = true;
			r.rotateAcked = true;
			r.lastSeenMs = now.get();
			store.save(null, r);
			restart();
			return x;
		}

		boolean publishes(byte[] clientKey) {
			for (String onion : tor.published) {
				List<byte[]> keys = services.get(onion);
				if (keys == null) continue;
				for (byte[] k : keys) {
					if (Arrays.equals(k, clientKey)) return true;
				}
			}
			return false;
		}
	}

	private static int type(BdfList body) throws Exception {
		return OnionAuthRecords.parse(body).type;
	}

	private static void commit(Transaction txn) {
		List<CommitAction> actions = txn.getActions();
		if (actions == null) return;
		for (CommitAction action : actions) {
			action.accept(new CommitAction.Visitor() {
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

	private String mint() {
		int n = minted++;
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < 28; i++) s.append(ALPHABET.charAt(n % 32));
		for (int i = 0; i < 28; i++) s.append(ALPHABET.charAt((n / 32) % 32));
		return s.toString();
	}

	private void pair(Build dialing, Build answering) throws Exception {
		pairAtGenerations(dialing, 0, answering, 0);
	}

	private void pairAtGenerations(Build dialing, long dialingGen,
			Build answering, long answeringGen) throws Exception {
		a = new Side(1, dialing);
		b = new Side(7, answering);
		a.other = b;
		b.other = a;
		dialer = a;
		unsettled = false;
		a.atGeneration(dialingGen);
		b.atGeneration(answeringGen);
		a.start();
		b.start();
	}

	private boolean reachable(Side from, Side to, String onion) {
		if (!to.tor.published.contains(onion)) return false;
		List<byte[]> keys = services.get(onion);
		byte[] credential = from.tor.credentials.get(onion);
		if (keys == null || credential == null) return false;
		byte[] pub = publicKeys.get(Arrays.toString(credential));
		for (byte[] k : keys) if (Arrays.equals(k, pub)) return true;
		return false;
	}

	private boolean session() throws Exception {
		return connect(dialer);
	}

	private boolean connect(Side d) throws Exception {
		if (!network) return false;
		Side t = d.other;
		String onion = d.manager.dialOnion(d.contact.getId());
		boolean authorized = onion != null;
		if (!authorized) onion = t.openOnion;
		else if (!reachable(d, t, onion)) return false;
		return connectTo(d, onion, authorized);
	}

	private boolean connectOpen(Side d) throws Exception {
		if (!network) return false;
		return connectTo(d, d.other.openOnion, false);
	}

	private boolean connectTo(Side d, String onion, boolean authorized)
			throws Exception {
		Side t = d.other;
		d.manager.dialed(d.contact.getId(), onion);
		boolean accepted = t.manager.accepts(t.contact.getId(), authorized);
		if (!authorized && accepted && t == guarded) breach = true;
		if (!accepted) return false;
		if (authorized) t.manager.inboundAuthorized(t.contact.getId());
		for (int round = 0; round < ROUNDS_PER_SESSION; round++) {
			if (a.flush() + b.flush() == 0) return true;
		}
		unsettled = true;
		return true;
	}

	private void openDials(Side s) throws Exception {
		while (s.pendingOpenDials > 0) {
			s.pendingOpenDials--;
			if (connectOpen(s)) s.openDials++;
		}
	}

	private boolean pump() throws Exception {
		boolean connected = false;
		for (int i = 0; i < SESSIONS_PER_PUMP; i++) {
			connected = session();
			if (!connected) return false;
		}
		return connected;
	}

	private void repairDials(Side s) throws Exception {
		while (s.pendingRepairs > 0) {
			s.pendingRepairs--;
			if (s.manager.dialOnion(s.contact.getId()) == null) continue;
			if (connect(s)) s.repairDials++;
		}
	}

	private int elapse(long ms, boolean online) throws Exception {
		return elapse(ms, online, online);
	}

	private int elapseWithoutPolling(long ms) throws Exception {
		return elapse(ms, true, false);
	}

	private int elapse(long ms, boolean online, boolean polling)
			throws Exception {
		int connected = 0;
		long end = now.get() + ms;
		network = online;
		try {
			while (now.get() < end) {
				now.set(Math.min(end, now.get() + TICK_MS));
				a.manager.tick();
				b.manager.tick();
				repairDials(a);
				repairDials(b);
				openDials(a);
				openDials(b);
				if (polling && session()) connected++;
			}
		} finally {
			network = true;
		}
		return connected;
	}

	private void restartBoth() throws Exception {
		a.restart();
		b.restart();
	}

	private void assertLockedInAndReachable() throws Exception {
		assertEquals(AUTH_REQUIRED, a.state());
		assertEquals(AUTH_REQUIRED, b.state());
		assertTrue("the dialer cannot reach its peer", session());
		assertNotNull(dialer.record().peerOnion);
		assertEquals(dialer.record().peerOnion,
				dialer.manager.dialOnion(dialer.contact.getId()));
		assertFalse(a.manager.accepts(a.contact.getId(), false));
		assertFalse(b.manager.accepts(b.contact.getId(), false));
		assertFalse(unsettled);
	}

	private void negotiateWithTheDialerCommitOvertakingItsProbeSuccess() throws Exception {
		dialer.hold(TYPE_PROBE_SUCCESS);
		pump();
		dialer.release();
		pump();
	}

	private void negotiateWithThePeerCommitOvertakingItsProbeSuccess() throws Exception {
		dialer.other.hold(TYPE_PROBE_SUCCESS);
		pump();
		dialer.other.release();
		pump();
	}

	private static OnionAuthDebugHooks holding(
			java.util.concurrent.atomic.AtomicBoolean hold) {
		return new OnionAuthDebugHooks() {
			@Override
			public boolean skipsNewNegotiations(Transaction txn) {
				return false;
			}

			@Override
			public boolean holdsConfirmations(Transaction txn) {
				return hold.get();
			}
		};
	}

	@Test
	public void testAPairWhoseConfirmationsWereHeldLocksInOnceTheyAreReleased()
			throws Exception {
		for (boolean dialerHolds : new boolean[] {true, false}) {
			java.util.concurrent.atomic.AtomicBoolean hold =
					new java.util.concurrent.atomic.AtomicBoolean(true);
			if (dialerHolds) pair(fixedWith(holding(hold)), fixed);
			else pair(fixed, fixedWith(holding(hold)));
			Side holder = dialerHolds ? a : b;
			pump();
			assertEquals(0, holder.sent(TYPE_COMMIT));
			assertNotEquals(AUTH_REQUIRED, holder.other.state());
			elapse(3 * DAY_MS, true);
			assertEquals(0, holder.sent(TYPE_COMMIT));
			assertNotEquals(AUTH_REQUIRED, holder.other.state());
			hold.set(false);
			elapse(3 * DAY_MS, true);
			assertLockedInAndReachable();
			restartBoth();
			assertTrue(pump());
			assertLockedInAndReachable();
		}
	}

	@Test
	public void testTwoPeersWithoutFaultsLockInAndStayReachable()
			throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		assertLockedInAndReachable();
		elapse(NEGOTIATION_TIMEOUT_MS + DAY_MS, true);
		restartBoth();
		assertTrue(pump());
		assertLockedInAndReachable();
	}

	@Test
	public void testTwoShippedPeersLoseEachOtherAWeekAfterTheNonDialerMissesTheCommit()
			throws Exception {
		pair(shipped, shipped);
		negotiateWithTheDialerCommitOvertakingItsProbeSuccess();
		assertEquals(AUTH_REQUIRED, a.state());
		assertEquals(AUTH_CONFIRMED, b.state());
		assertTrue(b.record().commitSent);
		assertFalse(b.record().peerCommitReceived);
		int connected = elapse(NEGOTIATION_TIMEOUT_MS - HOUR_MS, true);
		assertEquals((NEGOTIATION_TIMEOUT_MS - HOUR_MS) / TICK_MS, connected);
		elapse(2 * HOUR_MS, true);
		assertNotEquals(AUTH_CONFIRMED, b.state());
		assertFalse(session());
		restartBoth();
		assertFalse(session());
		assertEquals(0, elapse(DAY_MS, true));
		assertEquals(AUTH_REQUIRED, a.state());
		assertNotEquals(AUTH_REQUIRED, b.state());
	}

	@Test
	public void testTwoShippedPeersLoseEachOtherAtOnceWhenTheDialerMissesTheCommit()
			throws Exception {
		pair(shipped, shipped);
		negotiateWithThePeerCommitOvertakingItsProbeSuccess();
		assertEquals(AUTH_CONFIRMED, a.state());
		assertTrue(a.record().commitSent);
		assertEquals(AUTH_REQUIRED, b.state());
		assertFalse(session());
		restartBoth();
		assertEquals(0, elapse(NEGOTIATION_TIMEOUT_MS + DAY_MS, true));
		assertEquals(AUTH_REQUIRED, b.state());
	}

	@Test
	public void testTwoShippedPeersBounceCommitsAfterARestartRepairsAMissedCommit()
			throws Exception {
		pair(shipped, shipped);
		negotiateWithTheDialerCommitOvertakingItsProbeSuccess();
		assertEquals(AUTH_CONFIRMED, b.state());
		b.restart();
		assertTrue(session());
		assertEquals(AUTH_REQUIRED, b.state());
		assertTrue(unsettled);
	}

	private void assertSurvivesTheTimeoutAndARestart() throws Exception {
		assertSurvivesTheTimeoutAndARestart(MAX_COMMITS_PER_SIDE);
	}

	private void assertSurvivesTheTimeoutAndARestart(int maxCommits)
			throws Exception {
		assertLockedInAndReachable();
		int connected = elapse(NEGOTIATION_TIMEOUT_MS + DAY_MS, true);
		assertEquals((NEGOTIATION_TIMEOUT_MS + DAY_MS) / TICK_MS, connected);
		assertLockedInAndReachable();
		restartBoth();
		assertTrue(pump());
		assertLockedInAndReachable();
		assertTrue("commits sent " + a.sent(TYPE_COMMIT),
				a.sent(TYPE_COMMIT) <= maxCommits);
		assertTrue("commits sent " + b.sent(TYPE_COMMIT),
				b.sent(TYPE_COMMIT) <= maxCommits);
	}

	@Test
	public void testADialerCommitThatOvertakesItsProbeSuccessStillLocksBothSidesIn()
			throws Exception {
		pair(fixed, fixed);
		negotiateWithTheDialerCommitOvertakingItsProbeSuccess();
		assertSurvivesTheTimeoutAndARestart();
	}

	@Test
	public void testAPeerCommitThatOvertakesItsProbeSuccessStillLocksBothSidesIn()
			throws Exception {
		pair(fixed, fixed);
		negotiateWithThePeerCommitOvertakingItsProbeSuccess();
		assertSurvivesTheTimeoutAndARestart();
	}

	@Test
	public void testANonDialerThatMissedTheCommitIsRepairedAndKeepsItsKeys()
			throws Exception {
		pair(fixed, fixed);
		a.drop(TYPE_COMMIT);
		pump();
		assertEquals(AUTH_REQUIRED, a.state());
		assertEquals(AUTH_CONFIRMED, b.state());
		assertTrue(b.record().commitSent);
		assertTrue(session());
		elapse(2 * HOUR_MS, true);
		assertSurvivesTheTimeoutAndARestart();
	}

	@Test
	public void testADialerThatMissedTheCommitStaysReachableAndIsRepaired()
			throws Exception {
		pair(fixed, fixed);
		b.drop(TYPE_COMMIT);
		pump();
		assertEquals(AUTH_CONFIRMED, a.state());
		assertTrue(a.record().commitSent);
		assertEquals(AUTH_REQUIRED, b.state());
		assertTrue(session());
		elapse(2 * HOUR_MS, true);
		assertSurvivesTheTimeoutAndARestart();
	}

	@Test
	public void testAMissedCommitCrossingTheTimeoutOfflineIsRepairedAfterARestart()
			throws Exception {
		pair(fixed, fixed);
		a.drop(TYPE_COMMIT);
		pump();
		assertEquals(AUTH_CONFIRMED, b.state());
		elapse(NEGOTIATION_TIMEOUT_MS + DAY_MS, false);
		assertEquals(AUTH_CONFIRMED, b.state());
		assertTrue(b.record().commitSent);
		assertNotNull(b.record().dialPrivateKey);
		assertEquals(OnionClientAuthManagerImpl.MAX_REPAIRS,
				b.sent(TYPE_PROBE_SUCCESS) - 1);
		restartBoth();
		assertTrue(pump());
		assertSurvivesTheTimeoutAndARestart(
				OnionClientAuthManagerImpl.MAX_REPAIRS + 3);
	}

	@Test
	public void testAFixedDialerRepairsAShippedPeerThatRejectedItsCommit()
			throws Exception {
		pair(fixed, shipped);
		negotiateWithTheDialerCommitOvertakingItsProbeSuccess();
		assertEquals(AUTH_REQUIRED, a.state());
		assertEquals(AUTH_CONFIRMED, b.state());
		elapse(2 * HOUR_MS, true);
		assertSurvivesTheTimeoutAndARestart();
	}

	@Test
	public void testAFixedDialerThatMissedTheCommitReachesAShippedPeer()
			throws Exception {
		pair(fixed, shipped);
		b.drop(TYPE_COMMIT);
		pump();
		assertEquals(AUTH_CONFIRMED, a.state());
		assertEquals(AUTH_REQUIRED, b.state());
		assertTrue(session());
		elapse(2 * HOUR_MS, true);
		assertSurvivesTheTimeoutAndARestart();
	}

	@Test
	public void testAFixedPeerThatMissedTheCommitIsRepairedByAShippedDialer()
			throws Exception {
		pair(shipped, fixed);
		a.drop(TYPE_COMMIT);
		pump();
		assertEquals(AUTH_REQUIRED, a.state());
		assertEquals(AUTH_CONFIRMED, b.state());
		elapse(2 * HOUR_MS, true);
		assertSurvivesTheTimeoutAndARestart();
	}

	@Test
	public void testAFixedLockedInPeerRepairsAShippedDialerThatRejectedItsCommit()
			throws Exception {
		pair(shipped, fixed);
		negotiateWithThePeerCommitOvertakingItsProbeSuccess();
		assertEquals(AUTH_CONFIRMED, a.state());
		assertTrue(a.record().commitSent);
		assertEquals(AUTH_REQUIRED, b.state());
		assertFalse(session());
		assertFalse(b.manager.accepts(b.contact.getId(), false));
		elapse(2 * HOUR_MS, true);
		assertEquals(1, b.repairCalls);
		assertEquals(1, b.repairDials);
		assertEquals(AUTH_REQUIRED, a.state());
		assertSurvivesTheTimeoutAndARestart();
		assertEquals(1, b.repairCalls);
	}

	@Test
	public void testAFixedNonDialerThatMissedTheCommitRepairsWithItsOwnDial()
			throws Exception {
		pair(fixed, fixed);
		a.drop(TYPE_COMMIT);
		pump();
		assertEquals(AUTH_CONFIRMED, b.state());
		assertEquals(0, elapseWithoutPolling(2 * HOUR_MS));
		assertTrue(b.repairDials >= 1);
		assertEquals(AUTH_REQUIRED, a.state());
		assertEquals(AUTH_REQUIRED, b.state());
		assertSurvivesTheTimeoutAndARestart();
	}

	@Test
	public void testAFixedNonDialerThatMissedTheCommitRepairsAShippedDialerWithItsOwnDial()
			throws Exception {
		pair(shipped, fixed);
		a.drop(TYPE_COMMIT);
		pump();
		assertEquals(AUTH_REQUIRED, a.state());
		assertEquals(AUTH_CONFIRMED, b.state());
		assertEquals(0, elapseWithoutPolling(2 * HOUR_MS));
		assertEquals(1, b.repairDials);
		assertEquals(AUTH_REQUIRED, b.state());
		assertFalse(unsettled);
		assertSurvivesTheTimeoutAndARestart();
	}

	@Test
	public void testRepairDialsAreBoundedWhileThePeerIsUnreachable()
			throws Exception {
		pair(fixed, fixed);
		a.drop(TYPE_COMMIT);
		pump();
		assertEquals(AUTH_REQUIRED, a.state());
		assertEquals(AUTH_CONFIRMED, b.state());
		assertEquals(0, elapse(30 * DAY_MS, false));
		int silence = a.record().silenceRepairs;
		assertTrue(silence >= 1);
		assertTrue(silence <= 12);
		assertEquals(0, b.record().silenceRepairs);
		assertEquals(1 + silence, a.repairCalls);
		assertEquals(OnionClientAuthManagerImpl.MAX_REPAIRS, b.repairCalls);
		assertEquals(0, a.repairDials + b.repairDials);
		assertTrue(pump());
		assertLockedInAndReachable();
		assertEquals(1 + silence, a.repairCalls);
		assertTrue(b.repairCalls <= 1 + OnionClientAuthManagerImpl.MAX_REPAIRS);
	}

	@Test
	public void testARestartRepairBetweenFixedPeersDoesNotBounceCommits()
			throws Exception {
		pair(fixed, fixed);
		a.drop(TYPE_COMMIT);
		pump();
		assertEquals(AUTH_CONFIRMED, b.state());
		b.restart();
		assertTrue(session());
		assertFalse(unsettled);
		assertLockedInAndReachable();
	}

	@Test
	public void testARestartRepairWithAShippedPeerDoesNotBounceCommits()
			throws Exception {
		pair(fixed, shipped);
		negotiateWithTheDialerCommitOvertakingItsProbeSuccess();
		assertEquals(AUTH_CONFIRMED, b.state());
		b.restart();
		assertTrue(session());
		assertFalse(unsettled);
		assertLockedInAndReachable();
	}

	@Test
	public void testAFixedPeerRestartingWhileAShippedPeerIsLockedInDoesNotBounceCommits()
			throws Exception {
		pair(shipped, fixed);
		a.drop(TYPE_COMMIT);
		pump();
		assertEquals(AUTH_REQUIRED, a.state());
		assertEquals(AUTH_CONFIRMED, b.state());
		b.restart();
		assertTrue(session());
		assertFalse(unsettled);
		elapse(2 * HOUR_MS, true);
		assertFalse(unsettled);
		assertLockedInAndReachable();
	}

	private void assertActivatesAtGenerations(Build dialing, long dialingGen,
			Build answering, long answeringGen) throws Exception {
		pairAtGenerations(dialing, dialingGen, answering, answeringGen);
		assertTrue(pump());
		assertLockedInAndReachable();
		assertEquals(dialingGen, a.generation());
		assertEquals(answeringGen, b.generation());
		restartBoth();
		assertTrue(pump());
		assertLockedInAndReachable();
	}

	@Test
	public void testTwoShippedPeersAtDifferentGenerationsNeverActivate()
			throws Exception {
		pairAtGenerations(shipped, 3, shipped, 1);
		assertTrue(pump());
		assertEquals(DAY_MS / TICK_MS, elapse(DAY_MS, true));
		assertEquals(AUTH_NEGOTIATING, a.state());
		assertEquals(AUTH_NEGOTIATING, b.state());
		assertFalse(a.record().readyReceived);
		assertFalse(b.record().readyReceived);
	}

	@Test
	public void testFixedPeersAtDifferentGenerationsActivate()
			throws Exception {
		assertActivatesAtGenerations(fixed, 3, fixed, 1);
		assertActivatesAtGenerations(fixed, 1, fixed, 3);
	}

	@Test
	public void testAFixedDialerAndAShippedPeerAtDifferentGenerationsActivate()
			throws Exception {
		assertActivatesAtGenerations(fixed, 3, shipped, 1);
		assertActivatesAtGenerations(fixed, 1, shipped, 3);
	}

	@Test
	public void testAShippedDialerAndAFixedPeerAtDifferentGenerationsActivate()
			throws Exception {
		assertActivatesAtGenerations(shipped, 3, fixed, 1);
		assertActivatesAtGenerations(shipped, 1, fixed, 3);
	}

	private Side breakPair(boolean clearedSideDials) throws Exception {
		pair(shipped, shipped);
		if (clearedSideDials) {
			negotiateWithThePeerCommitOvertakingItsProbeSuccess();
		} else {
			negotiateWithTheDialerCommitOvertakingItsProbeSuccess();
		}
		Side locked = clearedSideDials ? b : a;
		Side cleared = locked.other;
		assertEquals(AUTH_REQUIRED, locked.state());
		assertTrue(cleared.record().commitSent);
		elapse(NEGOTIATION_TIMEOUT_MS + HOUR_MS, true);
		assertEquals(State.LEGACY, cleared.state());
		assertEquals(AUTH_REQUIRED, locked.state());
		assertFalse(session());
		guarded = locked;
		breach = false;
		return locked;
	}

	private void assertRecoversWithoutOpeningTheLockedInSide(Side locked)
			throws Exception {
		assertRecoversWithoutOpeningTheLockedInSide(locked,
				2 * MAX_COMMITS_PER_SIDE);
	}

	private void assertRecoversWithoutOpeningTheLockedInSide(Side locked,
			int maxCommits) throws Exception {
		elapse(3 * DAY_MS, true);
		assertLockedInAndReachable();
		assertFalse(locked.record().rekeying);
		assertTrue(locked.openDials >= 1);
		assertFalse(breach);
		assertSurvivesTheTimeoutAndARestart(maxCommits);
		assertFalse(breach);
	}

	@Test
	public void testAnUpdatedLockedInDialerRestoresAShippedPeerThatLostItsKeys()
			throws Exception {
		Side locked = breakPair(false);
		locked.upgrade(fixed);
		assertRecoversWithoutOpeningTheLockedInSide(locked);
	}

	@Test
	public void testAnUpdatedLockedInPeerRestoresAShippedDialerThatLostItsKeys()
			throws Exception {
		Side locked = breakPair(true);
		locked.upgrade(fixed);
		assertRecoversWithoutOpeningTheLockedInSide(locked);
	}

	@Test
	public void testTwoUpdatedPeersRecoverWhenTheNonDialerLostItsKeys()
			throws Exception {
		Side locked = breakPair(false);
		locked.other.upgrade(fixed);
		locked.upgrade(fixed);
		assertRecoversWithoutOpeningTheLockedInSide(locked);
	}

	@Test
	public void testTwoUpdatedPeersRecoverWhenTheDialerLostItsKeys()
			throws Exception {
		Side locked = breakPair(true);
		locked.other.upgrade(fixed);
		locked.upgrade(fixed);
		assertRecoversWithoutOpeningTheLockedInSide(locked);
	}

	@Test
	public void testAShippedLockedInSideStaysLostUntilItIsUpdated()
			throws Exception {
		for (boolean clearedSideDials : new boolean[] {false, true}) {
			Side locked = breakPair(clearedSideDials);
			locked.other.upgrade(fixed);
			assertEquals(0, elapse(3 * DAY_MS, true));
			assertEquals(AUTH_REQUIRED, locked.state());
			assertNotEquals(AUTH_REQUIRED, locked.other.state());
			assertEquals(0, locked.other.openCalls);
			assertFalse(breach);
			locked.upgrade(fixed);
			assertRecoversWithoutOpeningTheLockedInSide(locked);
		}
	}

	@Test
	public void testARekeyInterruptedByRestartsAtEveryStepCompletes()
			throws Exception {
		for (boolean clearedSideDials : new boolean[] {false, true}) {
			Side locked = breakPair(clearedSideDials);
			Side cleared = locked.other;
			locked.upgrade(fixed);
			for (int type : new int[] {OnionAuthRecords.TYPE_READY,
					TYPE_PROBE_SUCCESS, TYPE_COMMIT}) {
				locked.hold(type);
				cleared.hold(type);
				elapse(DAY_MS + 2 * HOUR_MS, true);
				assertTrue(locked.record().rekeying);
				assertEquals(AUTH_REQUIRED, locked.state());
				restartBoth();
				elapse(HOUR_MS, true);
				restartBoth();
				assertTrue(locked.record().rekeying);
				assertFalse(breach);
				locked.release();
				cleared.release();
			}
			assertRecoversWithoutOpeningTheLockedInSide(locked,
					OnionClientAuthManagerImpl.MAX_REPAIRS
							+ 2 * MAX_COMMITS_PER_SIDE);
		}
	}

	@Test
	public void testHealthyPairsNeverDialTheOpenAddress() throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		elapse(NEGOTIATION_TIMEOUT_MS + DAY_MS, true);
		restartBoth();
		elapse(DAY_MS, true);
		assertLockedInAndReachable();
		assertEquals(0, a.openCalls);
		assertEquals(0, b.openCalls);
		assertTrue(a.repairCalls <= 1);
		assertTrue(b.repairCalls <= 1);
		assertEquals(0, a.record().silenceRepairs);
		assertEquals(0, b.record().silenceRepairs);
	}

	@Test
	public void testARotationTheDialerMissedKeepsTheOldAddressUntilTheDialerLearnsTheNewOne()
			throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		assertLockedInAndReachable();
		String old = b.store.loadService(null).onion;
		b.manager.rotate();
		String next = b.store.loadService(null).onion;
		assertNotEquals(old, next);
		assertEquals(0, elapse(31 * DAY_MS, false));
		assertTrue("the old address was retired without an acknowledgement",
				b.tor.published.contains(old));
		assertTrue(b.tor.published.contains(next));
		assertEquals(old, a.record().peerOnion);
		assertTrue(session());
		assertEquals(next, a.record().peerOnion);
		assertFalse(b.tor.published.contains(old));
		assertNull(b.store.loadService(null).oldOnion);
		elapse(8 * DAY_MS, true);
		assertEquals(b.store.loadService(null).onion, a.record().peerOnion);
		assertLockedInAndReachable();
		restartBoth();
		assertTrue(pump());
		assertLockedInAndReachable();
		assertFalse(breach);
	}

	private void assertPairFindsEachOtherAfterBeingApart(boolean aIsDesignated,
			long days) throws Exception {
		aDesignated = aIsDesignated;
		pair(fixed, fixed);
		Side designated = aIsDesignated ? a : b;
		Side other = designated.other;
		assertTrue(ZtpPoller.isDesignatedDialer(designated.contact));
		assertFalse(ZtpPoller.isDesignatedDialer(other.contact));
		dialer = designated;
		assertTrue(pump());
		assertLockedInAndReachable();
		String designatedOld = designated.store.loadService(null).onion;
		String otherOld = other.store.loadService(null).onion;
		a.manager.rotate();
		b.manager.rotate();
		assertEquals(0, elapse(days * DAY_MS, false));
		assertTrue("the designated side stopped waiting",
				designated.tor.published.contains(designatedOld));
		assertFalse("the other side kept waiting past ninety days",
				other.tor.published.contains(otherOld));
		assertTrue(connect(other));
		elapse(8 * DAY_MS, true);
		assertLockedInAndReachable();
		assertNull(a.store.loadService(null).oldOnion);
		assertNull(b.store.loadService(null).oldOnion);
		restartBoth();
		assertTrue(pump());
		assertLockedInAndReachable();
		assertFalse(breach);
	}

	@Test
	public void testPeersApartForOneHundredDaysFindEachOtherAgainWhenTheFirstIsTheDialer()
			throws Exception {
		assertPairFindsEachOtherAfterBeingApart(true, 100);
	}

	@Test
	public void testPeersApartForOneHundredDaysFindEachOtherAgainWhenTheSecondIsTheDialer()
			throws Exception {
		assertPairFindsEachOtherAfterBeingApart(false, 100);
	}

	@Test
	public void testTheDialerKeepsItsOldAddressForFourHundredDays()
			throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		assertLockedInAndReachable();
		String old = a.store.loadService(null).onion;
		a.manager.rotate();
		assertEquals(0, elapse(400 * DAY_MS, false));
		assertTrue(ZtpPoller.isDesignatedDialer(a.contact));
		assertTrue(a.tor.published.contains(old));
		assertEquals(old, a.store.loadService(null).oldOnion);
	}

	@Test
	public void testTwoLockedInPeersThatBothRotateWhileApartFindEachOtherAgain()
			throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		assertLockedInAndReachable();
		a.manager.rotate();
		b.manager.rotate();
		assertEquals(0, elapse(31 * DAY_MS, false));
		elapse(8 * DAY_MS, true);
		assertLockedInAndReachable();
		assertNull(a.store.loadService(null).oldOnion);
		assertNull(b.store.loadService(null).oldOnion);
		restartBoth();
		assertTrue(pump());
		assertLockedInAndReachable();
	}

	@Test
	public void testTwoLockedInPeersThatEachRemoveAContactWhileApartFindEachOtherAgain()
			throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		assertLockedInAndReachable();
		Contact x = a.addAuthorizedContact(3);
		Contact y = b.addAuthorizedContact(9);
		byte[] xKey = a.store.load(null, x.getId()).peerPublicKey;
		byte[] yKey = b.store.load(null, y.getId()).peerPublicKey;
		assertTrue(a.publishes(xKey));
		assertTrue(b.publishes(yKey));
		assertTrue(pump());
		assertLockedInAndReachable();
		String aOld = a.store.loadService(null).onion;
		String bOld = b.store.loadService(null).onion;
		a.remove(x);
		b.remove(y);
		assertNotEquals(aOld, a.store.loadService(null).onion);
		assertNotEquals(bOld, b.store.loadService(null).onion);
		assertFalse(a.publishes(xKey));
		assertFalse(b.publishes(yKey));
		assertEquals(0, elapse(60 * DAY_MS, false));
		assertFalse(a.publishes(xKey));
		assertFalse(b.publishes(yKey));
		elapse(8 * DAY_MS, true);
		assertLockedInAndReachable();
		assertFalse(a.tor.published.contains(aOld));
		assertFalse(b.tor.published.contains(bOld));
		restartBoth();
		assertTrue(pump());
		assertLockedInAndReachable();
		assertFalse(a.publishes(xKey));
		assertFalse(b.publishes(yKey));
		assertFalse(breach);
	}

	@Test
	public void testTwoLockedInPeersApartForLongerThanTheirRotationPeriodFindEachOtherAgain()
			throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		assertLockedInAndReachable();
		assertEquals(0, elapse(70 * DAY_MS, false));
		assertEquals(1, a.generation());
		assertEquals(1, b.generation());
		elapse(8 * DAY_MS, true);
		assertLockedInAndReachable();
		restartBoth();
		assertTrue(pump());
		assertLockedInAndReachable();
	}

	@Test
	public void testOpenDialsToASilentPeerAreRateLimited() throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		int offers = a.sent(OnionAuthRecords.TYPE_OFFER);
		assertEquals(0, elapse(30 * DAY_MS, false));
		assertTrue(a.openCalls >= 1);
		assertTrue(a.openCalls <= 12);
		assertTrue(b.openCalls <= 12);
		assertEquals(offers + 1, a.sent(OnionAuthRecords.TYPE_OFFER));
		assertTrue(pump());
		elapse(DAY_MS, true);
		assertLockedInAndReachable();
		int calls = a.openCalls;
		elapse(3 * DAY_MS, true);
		assertEquals(calls, a.openCalls);
		assertFalse(a.record().rekeying);
	}

	private void deliverALateOfferFromAnAbandonedRound(Side receiver)
			throws Exception {
		OnionAuthStore.ServiceRecord svc =
				receiver.other.store.loadService(null);
		byte[] abandoned = new byte[32];
		random.nextBytes(abandoned);
		receiver.deliver(OnionAuthRecords.offer(svc.gen, svc.onion,
				abandoned));
	}

	@Test
	public void testALateOfferFromAnAbandonedRoundLeavesAHealthyPairReachable()
			throws Exception {
		for (Build peer : Arrays.asList(fixed, shipped)) {
			for (boolean dialerReceives : new boolean[] {true, false}) {
				if (dialerReceives) pair(fixed, peer);
				else pair(peer, fixed);
				Side receiver = dialerReceives ? a : b;
				assertTrue(pump());
				assertLockedInAndReachable();
				byte[] committed = receiver.record().peerPublicKey;
				guarded = receiver;
				breach = false;
				deliverALateOfferFromAnAbandonedRound(receiver);
				assertEquals(AUTH_REQUIRED, receiver.state());
				assertFalse(receiver.manager.accepts(
						receiver.contact.getId(), false));
				elapse(3 * DAY_MS, true);
				assertLockedInAndReachable();
				assertFalse(receiver.record().rekeying);
				assertArrayEquals(committed, receiver.record().peerPublicKey);
				assertFalse(breach);
				restartBoth();
				assertTrue(pump());
				assertLockedInAndReachable();
			}
		}
	}

	@Test
	public void testACommitSentDialerReachesAPeerThatRestoredAnOldBackup()
			throws Exception {
		for (Build peer : Arrays.asList(fixed, shipped)) {
			pair(fixed, peer);
			b.drop(TYPE_COMMIT);
			pump();
			assertEquals(AUTH_CONFIRMED, a.state());
			assertTrue(a.record().commitSent);
			b.settings.forget(OnionAuthStore.NAMESPACE);
			b.restart();
			assertNotEquals(AUTH_REQUIRED, b.state());
			assertFalse(session());
			elapse(4 * DAY_MS, true);
			assertLockedInAndReachable();
			assertFalse(a.record().rekeying);
			assertTrue(a.openDials >= 1);
			restartBoth();
			assertTrue(pump());
			assertLockedInAndReachable();
		}
	}

	private void allowAnyContact(Side s) throws Exception {
		context.checking(new Expectations() {{
			allowing(s.db).getContact(with(any(Transaction.class)),
					with(any(ContactId.class)));
			will(new CustomAction("returns another contact") {
				@Override
				public Object invoke(Invocation i) {
					return getContact((ContactId) i.getParameter(1),
							getAuthor(), new AuthorId(getRandomId()), true);
				}
			});
		}});
	}

	@Test
	public void testAReturnToTheCommittedRoundIsNoEvidenceOfTheNewAddress()
			throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		assertLockedInAndReachable();
		Side r = b;
		Side p = a;
		String old = r.store.loadService(null).onion;
		r.hold(OnionAuthRecords.TYPE_ROTATE);
		r.manager.rotate();
		assertNotEquals(old, r.store.loadService(null).onion);
		now.addAndGet(HOUR_MS);
		deliverALateOfferFromAnAbandonedRound(r);
		assertTrue(r.record().rekeying);
		OnionAuthStore.ServiceRecord psvc = p.store.loadService(null);
		r.deliver(OnionAuthRecords.offer(psvc.gen, psvc.onion,
				p.record().dialPublicKey));
		assertFalse(r.record().rekeying);
		assertEquals(AUTH_REQUIRED, r.state());
		assertFalse(r.record().rotateAcked);
		r.manager.tick();
		assertEquals(old, p.record().peerOnion);
		assertTrue("the old address was retired while the peer knew only it",
				r.tor.published.contains(old));
		r.release();
		elapse(3 * DAY_MS, true);
		assertLockedInAndReachable();
		assertFalse(r.tor.published.contains(old));
	}

	@Test
	public void testARemovalWhoseDeleteFailsTakesTheKeyOutAtTheNextCheck()
			throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		assertLockedInAndReachable();
		allowAnyContact(b);
		Contact x = b.addAuthorizedContact(3);
		byte[] xKey = b.store.load(null, x.getId()).peerPublicKey;
		assertTrue(pump());
		assertTrue(b.publishes(xKey));
		b.tor.failRemoves = 1;
		b.remove(x);
		elapse(TICK_MS, false);
		assertFalse("the removed key is still published after a check",
				b.publishes(xKey));
		assertFalse(b.store.loadService(null).revocationPending);
		elapse(2 * DAY_MS, true);
		assertLockedInAndReachable();
		assertFalse(b.publishes(xKey));
	}

	@Test
	public void testARemovalDuringAnOverlapWhoseDeletesFailTakesTheKeyOutAtTheNextCheck()
			throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		assertLockedInAndReachable();
		allowAnyContact(b);
		Contact x = b.addAuthorizedContact(3);
		byte[] xKey = b.store.load(null, x.getId()).peerPublicKey;
		assertTrue(pump());
		b.manager.rotate();
		assertNotNull(b.store.loadService(null).oldOnion);
		b.tor.failRemoves = 2;
		b.remove(x);
		elapse(TICK_MS, false);
		assertFalse("the removed key is still published after a check",
				b.publishes(xKey));
		elapse(2 * DAY_MS, true);
		assertLockedInAndReachable();
		assertFalse(b.publishes(xKey));
	}

	@Test
	public void testATorRestartDuringAnOverlapPublishesTheOldAddressAgain()
			throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		assertLockedInAndReachable();
		String old = b.store.loadService(null).onion;
		b.manager.rotate();
		b.restart();
		assertTrue("the old address was not published after a Tor restart",
				b.tor.published.contains(old));
		assertEquals(old, a.record().peerOnion);
		assertTrue(session());
		assertLockedInAndReachable();
	}

	@Test
	public void testAPublishThatFailedAtATorStartIsRetriedByTheNextCheck()
			throws Exception {
		pair(fixed, fixed);
		assertTrue(pump());
		assertLockedInAndReachable();
		String current = b.store.loadService(null).onion;
		b.manager.detach();
		b.tor.stop();
		b.tor = new Tor();
		b.tor.failPublishes = 1;
		b.start();
		assertFalse(b.tor.published.contains(current));
		elapse(TICK_MS, false);
		assertTrue("the address was not published again after a failed start",
				b.tor.published.contains(current));
		assertTrue(session());
		assertLockedInAndReachable();
	}

	@Test
	public void testAnOlderRotatingSideStrandsItsPeerUntilItIsUpdated()
			throws Exception {
		pair(fixed, shipped);
		assertTrue(pump());
		assertLockedInAndReachable();
		b.manager.rotate();
		assertEquals(0, elapse(31 * DAY_MS, false));
		assertEquals("a peer that rotated on an earlier version kept the"
				+ " address", 0, elapse(8 * DAY_MS, true));
		b.upgrade(fixed);
		elapse(3 * DAY_MS, true);
		assertLockedInAndReachable();
	}

	@Test
	public void testAnOlderRemovingSideStrandsItsPeerUntilItIsUpdated()
			throws Exception {
		pair(fixed, shipped);
		assertTrue(pump());
		assertLockedInAndReachable();
		allowAnyContact(b);
		Contact y = b.addAuthorizedContact(9);
		assertTrue(pump());
		assertLockedInAndReachable();
		b.remove(y);
		assertEquals(0, elapse(2 * DAY_MS, false));
		assertEquals("a peer that removed a contact on an earlier version"
				+ " kept the address", 0, elapse(8 * DAY_MS, true));
		b.upgrade(fixed);
		elapse(3 * DAY_MS, true);
		assertLockedInAndReachable();
	}

	@Test
	public void testTwoOlderPeersSplitByARemovalRecoverOnceTheRemoverIsUpdated()
			throws Exception {
		pair(shipped, shipped);
		assertTrue(pump());
		assertLockedInAndReachable();
		allowAnyContact(b);
		Contact y = b.addAuthorizedContact(9);
		assertTrue(pump());
		assertLockedInAndReachable();
		b.remove(y);
		assertEquals(0, elapse(8 * DAY_MS, true));
		b.upgrade(fixed);
		elapse(3 * DAY_MS, true);
		assertEquals(AUTH_REQUIRED, a.state());
		assertEquals(AUTH_REQUIRED, b.state());
		assertTrue(session());
	}
}
