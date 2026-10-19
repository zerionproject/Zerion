package org.zerionproject.core.properties;

import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.CommitAction;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.EventAction;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.NullableDbCallable;
import org.zerionproject.core.api.db.TaskAction;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.MessageStatus;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.plugin.tor.B4OnionRotation.PeerRotationState;
import org.zerionproject.core.plugin.tor.B4OnionRotation.RotationPhase;
import org.zerionproject.core.test.PermissiveOnionClientAuth;
import org.zerionproject.tor.TorWrapper.HiddenServiceProperties;
import org.zerionproject.transport.OverlayTransport;
import org.zerionproject.transport.ZtpPoller;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.plugin.B4Constants.ROTATION_MAX_DAYS;
import static org.zerionproject.core.api.plugin.B4Constants.ROTATION_MIN_DAYS;
import static org.zerionproject.core.api.plugin.B4Constants.WIRE_KEY_ONION3_NEXT;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_LOCAL;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_TRANSPORT_ID;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_VERSION;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;

public class B4TwoNodeSimulationTest {

	private boolean legacyA = false;

	private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60 * MINUTE;
	private static final long DAY = 24 * HOUR;
	private static final long UPLOAD_AFTER_MS = MINUTE;
	private static final long REACHABLE_AFTER_MS = 20 * MINUTE;

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
	private boolean network = true;
	private int minted = 0;
	private long nextHourly = 0;
	private long drawDays = 0;
	private Node a;
	private Node b;

	private String mint() {
		int n = minted++;
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < 28; i++) s.append(ALPHABET.charAt(n % 32));
		for (int i = 0; i < 27; i++) s.append(ALPHABET.charAt((n / 32) % 32));
		return s.append('d').toString();
	}

	private static HiddenServiceProperties hiddenService(String onion,
			String privateKey) {
		try {
			Constructor<HiddenServiceProperties> c =
					HiddenServiceProperties.class.getDeclaredConstructor(
							String.class, String.class);
			c.setAccessible(true);
			return c.newInstance(onion, privateKey);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
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

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type,
			java.lang.reflect.InvocationHandler handler) {
		return (T) Proxy.newProxyInstance(
				B4TwoNodeSimulationTest.class.getClassLoader(),
				new Class<?>[] {type}, handler);
	}

	private final class Link implements OverlayTransport {

		private final Node node;
		final List<String> dialled = new ArrayList<>();
		final List<Long> reached = new ArrayList<>();

		private Link(Node node) {
			this.node = node;
		}

		@Override
		public TransportId getTransportId() {
			return TorConstants.ID;
		}

		@Override
		public String getAddressPropertyKey() {
			return TorConstants.PROP_ONION_V3;
		}

		@Override
		public DialResult dial(int contactId, String peerAddress,
				boolean fast) {
			return dial(contactId, peerAddress, fast, false);
		}

		@Override
		public DialResult dial(int contactId, String peerAddress,
				boolean fast, boolean refusalExpected) {
			if (!network) return DialResult.NOT_CONNECTED;
			dialled.add(peerAddress);
			Node peer = node.peer;
			Long reachable = peer.reachableAt.get(peerAddress);
			if (!peer.live.contains(peerAddress) || reachable == null
					|| now.get() < reachable) {
				return DialResult.NOT_CONNECTED;
			}
			reached.add(now.get());
			session(node, peer, peerAddress);
			return new DialResult(true, 15_000L);
		}

		@Override
		public void setNetworkEnabled(boolean enabled) {
		}
	}

	private final class Node implements B4OnionRotation.B4TorAdapter {

		final String name;
		final Contact contact;
		final SecretKey key = getSecretKey();
		final Map<String, Settings> settings = new HashMap<>();
		final Set<GroupId> groups = new HashSet<>();
		final Map<GroupId, LinkedHashMap<MessageId, BdfDictionary>> metadata =
				new HashMap<>();
		final Map<MessageId, BdfList> bodies = new HashMap<>();
		final Map<GroupId, BdfDictionary> groupMetadata = new HashMap<>();
		final Map<GroupId, ContactId> groupContacts = new HashMap<>();
		final Set<MessageId> deleted = new HashSet<>();
		final Set<MessageId> seen = new HashSet<>();
		final Map<Metadata, BdfDictionary> parsed = new IdentityHashMap<>();
		final Set<String> live = new LinkedHashSet<>();
		final Map<String, Long> reachableAt = new HashMap<>();
		final Map<String, Long> uploadAt = new LinkedHashMap<>();
		final Group localGroup = new Group(new GroupId(getRandomId()),
				TransportPropertyManager.CLIENT_ID,
				TransportPropertyManager.MAJOR_VERSION, new byte[0]);
		final Group contactGroup = new Group(new GroupId(getRandomId()),
				TransportPropertyManager.CLIENT_ID,
				TransportPropertyManager.MAJOR_VERSION, new byte[0]);
		final String startupOnion = mint();
		final Link link = new Link(this);
		final DatabaseComponent db;
		final TransportPropertyManagerImpl properties;
		final B4OnionRotation rotation;
		final ZtpPoller poller;
		int failPublishes = 0;
		Node peer;
		@Nullable
		Runnable repoll;

		Node(String name, Contact contact) throws Exception {
			this.name = name;
			this.contact = contact;
			db = proxy(DatabaseComponent.class, (p, m, args) -> database(m,
					args));
			ClientHelper clientHelper = proxy(ClientHelper.class,
					(p, m, args) -> clientHelper(m, args));
			MetadataParser parser = proxy(MetadataParser.class,
					(p, m, args) -> parsed.get((Metadata) args[0]));
			ContactGroupFactory groupFactory = proxy(
					ContactGroupFactory.class, (p, m, args) ->
							m.getName().equals("createLocalGroup")
									? localGroup : contactGroup);
			ClientVersioningManager versioning = proxy(
					ClientVersioningManager.class, (p, m, args) -> null);
			AccountManager accountManager = proxy(AccountManager.class,
					(p, m, args) -> m.getName().equals("getDatabaseKey")
							? key : null);
			SettingsManager settingsManager = new SettingsManager() {
				@Override
				public Settings getSettings(String namespace) {
					Settings s = new Settings();
					Settings kept = settings.get(namespace);
					if (kept != null) s.putAll(kept);
					return s;
				}

				@Override
				public Settings getSettings(Transaction txn,
						String namespace) {
					return getSettings(namespace);
				}

				@Override
				public void mergeSettings(Settings s, String namespace) {
					settings.computeIfAbsent(namespace, n -> new Settings())
							.putAll(s);
				}

				@Override
				public void mergeSettings(Transaction txn, Settings s,
						String namespace) {
					mergeSettings(s, namespace);
				}
			};
			ScheduledExecutorService never = proxy(
					ScheduledExecutorService.class, (p, m, args) -> null);
			rotation = legacyA && name.equals("a")
					? new Legacy(db, settingsManager, accountManager, clock)
					: new Drawn(db, settingsManager, accountManager, clock,
					never);
			properties = new TransportPropertyManagerImpl(db, clientHelper,
					versioning, parser, groupFactory, clock, rotation,
					PermissiveOnionClientAuth::new);
			Transaction txn = new Transaction(null, false);
			properties.onDatabaseOpened(txn);
			commit(txn);
			rotation.bindAdapter(this);
			live.add(startupOnion);
			reachableAt.put(startupOnion, 0L);
			TransportProperties p = new TransportProperties();
			p.put(TorConstants.PROP_ONION_V3, startupOnion);
			properties.mergeLocalProperties(TorConstants.ID, p);
			ContactManager contacts = proxy(ContactManager.class,
					(px, m, args) -> m.getName().equals("getContacts")
							? Collections.singletonList(contact) : contact);
			TaskScheduler scheduler = new TaskScheduler() {
				@Override
				public Cancellable schedule(Runnable task, Executor executor,
						long delay, TimeUnit unit) {
					repoll = task;
					return () -> {
					};
				}

				@Override
				public Cancellable scheduleWithFixedDelay(Runnable task,
						Executor executor, long delay, long interval,
						TimeUnit unit) {
					throw new UnsupportedOperationException();
				}
			};
			EventBus events = new EventBus() {
				@Override
				public void addListener(EventListener l) {
				}

				@Override
				public void removeListener(EventListener l) {
				}

				@Override
				public void broadcast(Event e) {
				}
			};
			poller = new ZtpPoller(Runnable::run, scheduler, contacts,
					properties, events, link, () -> rotation);
			LongSupplier simClock = now::get;
			for (String field : new String[] {"clock", "wallClock"}) {
				Field f = ZtpPoller.class.getDeclaredField(field);
				f.setAccessible(true);
				f.set(poller, simClock);
			}
		}

		@Nullable
		private Object database(Method m, Object[] args) throws Exception {
			switch (m.getName()) {
				case "transaction": {
					Transaction txn = new Transaction(null, false);
					((DbRunnable<?>) args[1]).run(txn);
					commit(txn);
					return null;
				}
				case "transactionWithResult": {
					Transaction txn = new Transaction(null, false);
					Object r = ((DbCallable<?, ?>) args[1]).call(txn);
					commit(txn);
					return r;
				}
				case "transactionWithNullableResult": {
					Transaction txn = new Transaction(null, false);
					Object r = ((NullableDbCallable<?, ?>) args[1]).call(txn);
					commit(txn);
					return r;
				}
				case "getContacts":
					return Collections.singletonList(contact);
				case "getContact":
					return contact;
				case "containsGroup":
					return groups.contains((GroupId) args[1]);
				case "addGroup":
					groups.add(((Group) args[1]).getId());
					return null;
				case "setGroupVisibility":
					return null;
				case "removeGroup":
					groups.remove(((Group) args[1]).getId());
					return null;
				case "deleteMessage":
				case "deleteMessageMetadata":
				case "removeMessage":
					forget((MessageId) args[1]);
					return null;
				case "getMessageStatus":
					return new MessageStatus((MessageId) args[2],
							(ContactId) args[1], true,
							seen.contains((MessageId) args[2]));
				default:
					throw new UnsupportedOperationException(m.getName());
			}
		}

		private void forget(MessageId id) {
			deleted.add(id);
			for (Map<MessageId, BdfDictionary> g : metadata.values()) {
				g.remove(id);
			}
		}

		@Nullable
		private Object clientHelper(Method m, Object[] args)
				throws Exception {
			switch (m.getName()) {
				case "createMessage": {
					BdfList body = normalise((BdfList) args[2]);
					Message msg = new Message(new MessageId(getRandomId()),
							(GroupId) args[0], (long) args[1], new byte[1]);
					bodies.put(msg.getId(), body);
					return msg;
				}
				case "addLocalMessage": {
					Message msg = (Message) args[1];
					metadata.computeIfAbsent(msg.getGroupId(),
							g -> new LinkedHashMap<>()).put(msg.getId(),
							(BdfDictionary) args[2]);
					return null;
				}
				case "getMessageMetadataAsDictionary":
					return new LinkedHashMap<>(metadata.getOrDefault(
							(GroupId) args[1], new LinkedHashMap<>()));
				case "getMessageAsList":
					return bodies.get((MessageId) args[1]);
				case "toList":
					return bodies.get(((Message) args[0]).getId());
				case "getGroupMetadataAsDictionary":
					return new BdfDictionary(groupMetadata.getOrDefault(
							(GroupId) args[1], new BdfDictionary()));
				case "mergeGroupMetadata":
					groupMetadata.computeIfAbsent((GroupId) args[1],
							g -> new BdfDictionary()).putAll(
							(BdfDictionary) args[2]);
					return null;
				case "parseAndValidateTransportProperties": {
					TransportProperties p = new TransportProperties();
					for (Map.Entry<String, Object> e :
							((BdfDictionary) args[0]).entrySet()) {
						p.put(e.getKey(), (String) e.getValue());
					}
					return p;
				}
				case "getContactId": {
					ContactId id = groupContacts.get((GroupId) args[1]);
					if (id == null) throw new DbException();
					return id;
				}
				case "setContactId":
					groupContacts.put((GroupId) args[1], (ContactId) args[2]);
					return null;
				default:
					throw new UnsupportedOperationException(m.getName());
			}
		}

		@SuppressWarnings("unchecked")
		private BdfList normalise(BdfList body) {
			BdfList out = new BdfList();
			for (Object o : body) {
				if (o instanceof Map && !(o instanceof BdfDictionary)) {
					out.add(new BdfDictionary((Map<String, Object>) o));
				} else {
					out.add(o);
				}
			}
			return out;
		}

		@Override
		public HiddenServiceProperties publishHiddenService(
				@Nullable String privKey) throws IOException {
			if (failPublishes > 0) {
				failPublishes--;
				throw new IOException("publish refused");
			}
			String onion = privKey == null ? mint() : privKey.substring(4);
			live.add(onion);
			if (!reachableAt.containsKey(onion)) {
				reachableAt.put(onion, now.get() + REACHABLE_AFTER_MS);
				uploadAt.put(onion, now.get() + UPLOAD_AFTER_MS);
			}
			return hiddenService(onion, "KEY:" + onion);
		}

		@Override
		public boolean isPublished(String onion) {
			return live.contains(onion);
		}

		@Override
		public String getStartupOnion() {
			return startupOnion;
		}

		@Override
		public void removeHiddenService(String onion) {
			live.remove(onion);
		}

		@Override
		public void updateTorCurrentPrivKey(String newPrivKey) {
		}

		@Override
		public void mergeTorLocalProperties(TransportProperties p) {
			try {
				properties.mergeLocalProperties(TorConstants.ID,
						new TransportProperties(p));
			} catch (DbException e) {
				throw new AssertionError(e);
			}
		}

		@Nullable
		MessageId latestLocalUpdate() {
			MessageId latest = null;
			long version = -1;
			Map<MessageId, BdfDictionary> group =
					metadata.getOrDefault(contactGroup.getId(),
							new LinkedHashMap<>());
			for (Map.Entry<MessageId, BdfDictionary> e : group.entrySet()) {
				BdfDictionary meta = e.getValue();
				if (!Boolean.TRUE.equals(meta.get(MSG_KEY_LOCAL))) continue;
				long v = ((Number) meta.get(MSG_KEY_VERSION)).longValue();
				if (v > version) {
					version = v;
					latest = e.getKey();
				}
			}
			return latest;
		}

		@Nullable
		String pending() throws DbException {
			return rotation.getPendingOnionForContact(contact.getId());
		}

		String dialTarget() throws DbException {
			return properties.getRemoteProperties(contact.getId(),
					TorConstants.ID).get(TorConstants.PROP_ONION_V3);
		}

		String current() throws DbException {
			return properties.getLocalProperties(TorConstants.ID)
					.get(TorConstants.PROP_ONION_V3);
		}

		@Nullable
		String announced() throws DbException {
			return properties.getLocalProperties(TorConstants.ID)
					.get(WIRE_KEY_ONION3_NEXT);
		}

		void evaluateHourly() throws Exception {
			Method m = B4OnionRotation.class.getDeclaredMethod("evaluateAll");
			m.setAccessible(true);
			try {
				m.invoke(rotation);
			} catch (InvocationTargetException e) {
				throw new AssertionError(e.getCause());
			}
		}

		void uploadDescriptors() {
			List<String> due = new ArrayList<>();
			for (Map.Entry<String, Long> e : uploadAt.entrySet()) {
				if (now.get() >= e.getValue()) due.add(e.getKey());
			}
			for (String onion : due) {
				uploadAt.remove(onion);
				if (live.contains(onion)) rotation.onDescriptorUploaded(onion);
			}
		}

		void poll() {
			Runnable r = repoll;
			repoll = null;
			if (r != null) r.run();
		}

		int reachedSince(long since) {
			int n = 0;
			for (long t : link.reached) if (t >= since) n++;
			return n;
		}
	}

	private void session(Node dialer, Node peer, String address) {
		try {
			peer.rotation.recordContact(peer.contact.getId(), now.get());
			dialer.rotation.recordContact(dialer.contact.getId(), now.get());
			peer.rotation.onPeerSyncSessionEstablished(peer.contact.getId(),
					address);
			dialer.rotation.onPeerSyncSessionEstablished(
					dialer.contact.getId(), null);
			for (int i = 0; i < 4; i++) {
				deliver(dialer, peer);
				deliver(peer, dialer);
			}
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private void deliver(Node from, Node to) throws Exception {
		MessageId id = from.latestLocalUpdate();
		if (id == null || from.seen.contains(id)) return;
		BdfList body = from.bodies.get(id);
		long version = ((Number) from.metadata.get(from.contactGroup.getId())
				.get(id).get(MSG_KEY_VERSION)).longValue();
		Message m = new Message(new MessageId(getRandomId()),
				to.contactGroup.getId(), now.get(), new byte[1]);
		to.bodies.put(m.getId(), body);
		Metadata meta = new Metadata();
		BdfDictionary remote = BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, TorConstants.ID.getString()),
				new BdfEntry(MSG_KEY_VERSION, version),
				new BdfEntry(MSG_KEY_LOCAL, false));
		to.parsed.put(meta, remote);
		Transaction txn = new Transaction(null, false);
		to.properties.incomingMessage(txn, m, meta);
		if (!to.deleted.contains(m.getId())) {
			to.metadata.computeIfAbsent(to.contactGroup.getId(),
					g -> new LinkedHashMap<>()).put(m.getId(), remote);
		}
		commit(txn);
		from.seen.add(id);
	}

	private void pair() throws Exception {
		pair(true);
	}

	private void pair(boolean aDials) throws Exception {
		byte[] low = new byte[AuthorId.LENGTH];
		byte[] high = new byte[AuthorId.LENGTH];
		Arrays.fill(high, (byte) 0x70);
		Author lowAuthor = new Author(new AuthorId(low), Author.FORMAT_VERSION,
				"a", getAuthor().getPublicKey());
		Author highAuthor = new Author(new AuthorId(high),
				Author.FORMAT_VERSION, "b", getAuthor().getPublicKey());
		Author aTheirs = aDials ? highAuthor : lowAuthor;
		Author bTheirs = aDials ? lowAuthor : highAuthor;
		a = new Node("a", getContact(new ContactId(1), aTheirs,
				new AuthorId(aDials ? low : high), true));
		b = new Node("b", getContact(new ContactId(1), bTheirs,
				new AuthorId(aDials ? high : low), true));
		a.peer = b;
		b.peer = a;
		assertEquals(aDials, ZtpPoller.isDesignatedDialer(a.contact));
		assertEquals(!aDials, ZtpPoller.isDesignatedDialer(b.contact));
		deliver(a, b);
		deliver(b, a);
		nextHourly = now.get() + HOUR;
		a.poller.start();
		b.poller.start();
	}

	private void elapse(long ms) throws Exception {
		elapse(ms, MINUTE);
	}

	private void elapse(long ms, long step) throws Exception {
		long end = now.get() + ms;
		while (now.get() < end) {
			now.set(Math.min(end, now.get() + step));
			for (Node n : new Node[] {a, b}) n.uploadDescriptors();
			for (Node n : new Node[] {a, b}) n.poll();
			if (now.get() >= nextHourly) {
				nextHourly = now.get() + HOUR;
				for (Node n : new Node[] {a, b}) n.evaluateHourly();
			}
		}
	}

	private void assertReachEachOtherOnTheirCurrentAddresses()
			throws Exception {
		long since = now.get();
		elapse(2 * HOUR);
		assertTrue("the designated dialer cannot reach its peer",
				a.reachedSince(since) > 0);
		assertEquals(b.current(), a.dialTarget());
		assertEquals(a.current(), b.dialTarget());
	}

	@Test
	public void aRotationOfTheSideThatIsDialledCompletesThroughEveryStep()
			throws Exception {
		pair();
		elapse(HOUR);
		String old = b.current();
		assertEquals(old, a.dialTarget());
		b.rotation.forceRotate();
		String next = b.rotation.getAliceNextOnion();
		assertNotNull(next);
		elapse(2 * MINUTE);
		assertEquals("the new onion was announced", next, b.announced());
		elapse(2 * MINUTE);
		assertEquals("the peer holds the announced onion", next, a.pending());
		long beforeReachable = now.get();
		elapse(REACHABLE_AFTER_MS - 5 * MINUTE);
		int earlyFailures = 0;
		for (String d : a.link.dialled) if (d.equals(next)) earlyFailures++;
		assertTrue("the new onion was not tried early", earlyFailures >= 3);
		assertTrue("the old onion stopped working while the new one was"
				+ " unreachable", a.reachedSince(beforeReachable) > 0);
		assertEquals(PeerRotationState.PRE_ANNOUNCED,
				b.rotation.diagnostics(b.contact.getId()).getPeerState());
		elapse(HOUR);
		assertEquals("the peer never reached the new onion",
				PeerRotationState.MIGRATED,
				b.rotation.diagnostics(b.contact.getId()).getPeerState());
		assertEquals(RotationPhase.ANNOUNCING, b.rotation.getPhase());
		assertTrue("the old onion was retired within the grace",
				b.live.contains(old));
		elapse(DAY);
		assertEquals(RotationPhase.IDLE, b.rotation.getPhase());
		assertFalse(b.live.contains(old));
		assertEquals(next, b.current());
		assertNull(b.announced());
		assertNull("the peer still holds a pending onion", a.pending());
		assertEquals(next, a.dialTarget());
		assertReachEachOtherOnTheirCurrentAddresses();
	}

	private void bothRotateWhileApart(boolean aDials, long days)
			throws Exception {
		drawDays = ROTATION_MAX_DAYS;
		pair(aDials);
		elapse(HOUR);
		Node dialer = aDials ? a : b;
		Node dialled = aDials ? b : a;
		String dialerOld = dialer.current();
		String dialledOld = dialled.current();
		network = false;
		a.rotation.forceRotate();
		b.rotation.forceRotate();
		elapse(days * DAY, 30 * MINUTE);
		assertTrue("the designated dialer retired its old onion while apart",
				dialer.live.contains(dialerOld));
		if (days >= 90) {
			assertFalse("the other side kept waiting past ninety days",
					dialled.live.contains(dialledOld));
		} else {
			assertTrue(dialled.live.contains(dialledOld));
		}
		network = true;
		elapse(3 * DAY);
		assertEquals(RotationPhase.IDLE, a.rotation.getPhase());
		assertEquals(RotationPhase.IDLE, b.rotation.getPhase());
		assertNotEquals(dialerOld, dialer.current());
		assertNotEquals(dialledOld, dialled.current());
		assertFalse(dialer.live.contains(dialerOld));
		assertFalse(dialled.live.contains(dialledOld));
		assertNull(a.pending());
		assertNull(b.pending());
		assertReachEachOtherOnTheirCurrentAddresses();
	}

	@Test
	public void twoSidesThatBothRotateWhileApartFindEachOtherAgain()
			throws Exception {
		bothRotateWhileApart(true, 80);
	}

	@Test
	public void twoSidesApartForOneHundredDaysFindEachOtherAgainWhenTheFirstDials()
			throws Exception {
		bothRotateWhileApart(true, 100);
	}

	@Test
	public void twoSidesApartForOneHundredDaysFindEachOtherAgainWhenTheSecondDials()
			throws Exception {
		bothRotateWhileApart(false, 100);
	}

	@Test
	public void theSideThatPassedOverASilentContactStartsNoNewRotationUntilItComesBack()
			throws Exception {
		drawDays = ROTATION_MIN_DAYS;
		pair(true);
		elapse(HOUR);
		String bOld = b.current();
		network = false;
		a.rotation.forceRotate();
		b.rotation.forceRotate();
		elapse(91 * DAY, 6 * HOUR);
		assertFalse(b.live.contains(bOld));
		assertEquals(RotationPhase.IDLE, b.rotation.getPhase());
		String promoted = b.current();
		elapse(8 * DAY, 6 * HOUR);
		assertEquals("a new rotation ran before the contact came back",
				promoted, b.current());
		assertEquals(RotationPhase.IDLE, b.rotation.getPhase());
		assertNull(b.pending());
		drawDays = ROTATION_MAX_DAYS;
		network = true;
		elapse(3 * DAY);
		assertEquals("the interval was drawn before the contact came back",
				promoted, b.current());
		assertEquals(RotationPhase.IDLE, b.rotation.getPhase());
		assertNull(b.pending());
		assertReachEachOtherOnTheirCurrentAddresses();
	}

	@Test
	public void theDesignatedDialerKeepsItsOldOnionForFourHundredDays()
			throws Exception {
		pair(true);
		elapse(HOUR);
		String aOld = a.current();
		network = false;
		a.rotation.forceRotate();
		b.rotation.forceRotate();
		elapse(400 * DAY, 6 * HOUR);
		assertTrue(a.live.contains(aOld));
		assertEquals(RotationPhase.ANNOUNCING, a.rotation.getPhase());
	}

	@Test
	public void anAbandonedRotationLeavesNoDeadAddressWithThePeer()
			throws Exception {
		pair();
		elapse(HOUR);
		b.rotation.forceRotate();
		String abandoned = b.rotation.getAliceNextOnion();
		elapse(5 * MINUTE);
		assertEquals(abandoned, a.pending());
		b.failPublishes = 1000;
		try {
			b.rotation.revokeAfterContactRemoval();
		} catch (DbException expected) {
		}
		assertFalse(b.live.contains(abandoned));
		elapse(2 * HOUR);
		assertNull("the peer still holds the abandoned onion", a.pending());
		assertEquals(b.current(), a.dialTarget());
		long since = now.get();
		elapse(HOUR);
		for (String d : a.link.dialled.subList(
				Math.max(0, a.link.dialled.size() - 20),
				a.link.dialled.size())) {
			assertNotEquals("the abandoned onion is still dialled",
					abandoned, d);
		}
		assertTrue(a.reachedSince(since) > 0);
		b.failPublishes = 0;
		elapse(3 * HOUR);
		String replacement = b.rotation.getAliceNextOnion();
		assertNotNull("the owed rotation never ran", replacement);
		assertEquals(replacement, a.pending());
	}

	private final class Drawn extends B4OnionRotation {

		Drawn(DatabaseComponent db, SettingsManager s, AccountManager a,
				Clock c, ScheduledExecutorService scheduler) {
			super(db, s, a, c, scheduler);
		}

		@Override
		protected long drawNextRotationDays() {
			return drawDays == 0 ? super.drawNextRotationDays() : drawDays;
		}
	}

	private final class Legacy extends B4OnionRotation {

		@Nullable
		String held;
		long heldAt;

		Legacy(DatabaseComponent db, SettingsManager s, AccountManager a,
				Clock c) {
			super(db, s, a, c);
		}

		@Override
		public void onAnnounceReceived(Transaction txn, ContactId from,
				String pendingOnion, long announcedAtMs) throws DbException {
			if (pendingOnion.equals(held)) return;
			if (held != null && now.get() - heldAt < HOUR) return;
			held = pendingOnion;
			heldAt = now.get();
			super.onAnnounceReceived(txn, from, pendingOnion, announcedAtMs);
		}

		@Override
		public void onPeerNextWithdrawn(Transaction txn, ContactId cid) {
		}

		@Override
		public void onPeerRotationComplete(Transaction txn, ContactId cid,
				String current) throws DbException {
			super.onPeerRotationComplete(txn, cid, current);
			if (current.equals(held)) held = null;
		}
	}

	private void deliverId(Node from, Node to, MessageId id, long version)
			throws Exception {
		BdfList body = from.bodies.get(id);
		Message m = new Message(new MessageId(getRandomId()),
				to.contactGroup.getId(), now.get(), new byte[1]);
		to.bodies.put(m.getId(), body);
		Metadata meta = new Metadata();
		BdfDictionary remote = BdfDictionary.of(
				new BdfEntry(MSG_KEY_TRANSPORT_ID, TorConstants.ID.getString()),
				new BdfEntry(MSG_KEY_VERSION, version),
				new BdfEntry(MSG_KEY_LOCAL, false));
		to.parsed.put(meta, remote);
		Transaction txn = new Transaction(null, false);
		to.properties.incomingMessage(txn, m, meta);
		if (!to.deleted.contains(m.getId())) {
			to.metadata.computeIfAbsent(to.contactGroup.getId(),
					g -> new LinkedHashMap<>()).put(m.getId(), remote);
		}
		commit(txn);
	}

	private long versionOf(Node n, MessageId id) {
		return ((Number) n.metadata.get(n.contactGroup.getId()).get(id)
				.get(MSG_KEY_VERSION)).longValue();
	}

	@Test
	public void anOlderDiallerThatIgnoredASecondAnnouncementGetsItAgain()
			throws Exception {
		legacyA = true;
		pair();
		elapse(HOUR);
		String old = b.current();
		b.rotation.forceRotate();
		String first = b.rotation.getAliceNextOnion();
		elapse(5 * MINUTE);
		assertEquals(first, a.pending());
		b.rotation.revokeAfterContactRemoval();
		String second = b.rotation.getAliceNextOnion();
		assertNotEquals(first, second);
		elapse(10 * MINUTE);
		assertEquals(second, b.announced());
		assertEquals(first, a.pending());
		elapse(3 * DAY, 30 * MINUTE);
		assertEquals("an older dialler never learned the announced address",
				RotationPhase.IDLE, b.rotation.getPhase());
		assertFalse(b.live.contains(old));
		assertEquals(second, b.current());
		assertNull(a.pending());
		assertReachEachOtherOnTheirCurrentAddresses();
	}

	@Test
	public void anAddressThatIsNotPublishedIsNotAnnounced() throws Exception {
		pair();
		elapse(HOUR);
		b.uploadAt.clear();
		b.rotation.forceRotate();
		String next = b.rotation.getAliceNextOnion();
		assertNull(b.announced());
		b.live.remove(next);
		b.failPublishes = 100000;
		elapse(2 * HOUR);
		assertFalse(b.live.contains(next));
		assertNull("an address that is not published was announced",
				b.announced());
		assertNull(a.pending());
		b.failPublishes = 0;
		elapse(2 * HOUR);
		assertTrue(b.live.contains(next));
		assertEquals(next, b.announced());
	}

	@Test
	public void aStaleUpdateArrivingLateDoesNotReplaceANewerAnnouncement()
			throws Exception {
		pair();
		elapse(HOUR);
		network = false;
		b.rotation.forceRotate();
		elapse(5 * MINUTE);
		String first = b.rotation.getAliceNextOnion();
		assertEquals(first, b.announced());
		MessageId withFirst = b.latestLocalUpdate();
		long vFirst = versionOf(b, withFirst);
		try {
			b.rotation.revokeAfterContactRemoval();
		} catch (DbException ignored) {
		}
		MessageId withdrawal = b.latestLocalUpdate();
		long vWithdrawal = versionOf(b, withdrawal);
		elapse(5 * MINUTE);
		String second = b.rotation.getAliceNextOnion();
		assertEquals(second, b.announced());
		MessageId withSecond = b.latestLocalUpdate();
		long vSecond = versionOf(b, withSecond);
		assertTrue(vFirst < vWithdrawal && vWithdrawal < vSecond);
		deliverId(b, a, withSecond, vSecond);
		assertEquals(second, a.pending());
		deliverId(b, a, withdrawal, vWithdrawal);
		assertEquals(second, a.pending());
		deliverId(b, a, withFirst, vFirst);
		assertEquals(second, a.pending());
	}
}
