package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupMemberListSnapshotEvent;
import org.zerionproject.app.api.messaging.event.GroupMembershipChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupMembershipChangedEvent.ChangeKind;
import org.zerionproject.app.api.messaging.event.GroupPostReceivedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrInviteOfferReceivedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrInviteResponseReceivedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrMemberInviteRecordEvent;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.NullableDbCallable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

final class InviteTestNet {

	private static final Map<String, BdfList> ENCODED =
			new ConcurrentHashMap<>();
	private static final AtomicInteger NEXT_CONTACT = new AtomicInteger(1);

	long now = 1_700_000_000_000L;
	final List<Node> nodes = new ArrayList<>();
	final List<Packet> delivered = new ArrayList<>();
	private final List<Packet> held = new ArrayList<>();

	static final class Packet {

		final Node from;
		final Node to;
		final BdfList body;
		final byte[] bytes;
		final long timestamp;

		private Packet(Node from, Node to, BdfList body, byte[] bytes,
				long timestamp) {
			this.from = from;
			this.to = to;
			this.body = body;
			this.bytes = bytes;
			this.timestamp = timestamp;
		}

		int type() {
			try {
				return body.getLong(0).intValue();
			} catch (FormatException e) {
				throw new AssertionError(e);
			}
		}
	}

	private interface Handler {
		@Nullable
		Object handle(String name, Object[] args) throws Exception;
	}

	final class Node {

		final String name;
		final byte[] pub;
		final byte[] mlDsa;
		final LocalAuthor local;
		final GroupTrManagerImpl manager;
		final List<Object> broadcasts = new ArrayList<>();
		final Map<String, Settings> settings = new HashMap<>();
		private final List<Event> pendingEvents = new ArrayList<>();
		private final List<Packet> outbox = new ArrayList<>();
		private final Map<ContactId, Contact> contacts = new LinkedHashMap<>();
		private final Map<ContactId, Group> contactGroups = new HashMap<>();
		private final Map<ContactId, Integer> versions = new HashMap<>();
		@Nullable
		Long clockOverride = null;

		private Node(String name, byte b) {
			this.name = name;
			pub = new byte[32];
			Arrays.fill(pub, b);
			mlDsa = new byte[PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES];
			Arrays.fill(mlDsa, (byte) (b + 100));
			local = new LocalAuthor(new AuthorId(getRandomId()), 1, name,
					new SignaturePublicKey(pub),
					new SignaturePrivateKey(new byte[32]));
			manager = new GroupTrManagerImpl(
					fake(DatabaseComponent.class, this::db),
					Runnable::run,
					fake(SettingsManager.class, this::settingsCall),
					fake(ClientHelper.class, this::client),
					fake(CryptoComponent.class, this::crypto),
					fake(IdentityManager.class, this::identity),
					fake(ContactManager.class, this::contactsCall),
					fake(MessagingManager.class, this::messaging),
					fake(EventBus.class, (n, a) -> {
						if (n.equals("broadcast")) {
							broadcasts.add(a[0]);
							pendingEvents.add((Event) a[0]);
						}
						return null;
					}),
					fake(Clock.class, (n, a) -> clockOverride != null
							? clockOverride : now));
		}

		long time() {
			return clockOverride != null ? clockOverride : now;
		}

		ContactId contactIdOf(Node other) {
			for (Map.Entry<ContactId, Contact> e : contacts.entrySet()) {
				if (Arrays.equals(e.getValue().getAuthor().getPublicKey()
						.getEncoded(), other.pub)) {
					return e.getKey();
				}
			}
			throw new AssertionError(name + " has no contact " + other.name);
		}

		boolean hasContact(Node other) {
			for (Contact c : contacts.values()) {
				if (Arrays.equals(c.getAuthor().getPublicKey().getEncoded(),
						other.pub)) {
					return true;
				}
			}
			return false;
		}

		void setVersion(Node other, int minor) {
			versions.put(contactIdOf(other), minor);
		}

		void forgetContact(Node other) {
			contacts.remove(contactIdOf(other));
		}

		Settings namespace(String ns) {
			Settings copy = new Settings();
			Settings s = settings.get(ns);
			if (s != null) {
				for (Map.Entry<String, String> e : s.entrySet()) {
					if (!e.getValue().isEmpty()) copy.put(e.getKey(), e.getValue());
				}
			}
			return copy;
		}

		void receive(Packet p) {
			ContactId from = contactIdOf(p.from);
			Event e = toEvent(from, p);
			if (e != null) manager.eventOccurred(e);
		}

		List<Packet> sent() {
			List<Packet> all = new ArrayList<>();
			for (Packet p : delivered) if (p.from == this) all.add(p);
			for (Packet p : held) if (p.from == this) all.add(p);
			all.addAll(outbox);
			return all;
		}

		@Nullable
		private Object db(String n, Object[] a) throws Exception {
			switch (n) {
				case "transaction":
					((DbRunnable<?>) a[1]).run(new Transaction(null,
							Boolean.TRUE.equals(a[0])));
					return null;
				case "transactionWithResult":
					return ((DbCallable<?, ?>) a[1]).call(new Transaction(null,
							Boolean.TRUE.equals(a[0])));
				case "transactionWithNullableResult":
					return ((NullableDbCallable<?, ?>) a[1]).call(
							new Transaction(null, Boolean.TRUE.equals(a[0])));
				case "removeMessage":
				case "setCleanupDeadline":
				case "setCleanupTimerDuration":
					return null;
				case "startCleanupTimer":
					return -1L;
				default:
					throw new UnsupportedOperationException(n);
			}
		}

		@Nullable
		private Object settingsCall(String n, Object[] a) {
			switch (n) {
				case "getSettings": {
					String ns = (String) a[a.length - 1];
					Settings copy = new Settings();
					Settings s = settings.get(ns);
					if (s != null) copy.putAll(s);
					return copy;
				}
				case "mergeSettings": {
					String ns = (String) a[a.length - 1];
					settings.computeIfAbsent(ns, k -> new Settings())
							.putAll((Settings) a[a.length - 2]);
					return null;
				}
				case "deleteSettings": {
					String ns = (String) a[a.length - 2];
					Settings s = settings.get(ns);
					if (s != null) {
						for (Object k : (Collection<?>) a[a.length - 1]) {
							s.remove((String) k);
						}
					}
					return null;
				}
				case "deleteNamespaces":
					for (Object ns : (Collection<?>) a[a.length - 1]) {
						settings.remove((String) ns);
					}
					return null;
				default:
					throw new UnsupportedOperationException(n);
			}
		}

		@Nullable
		private Object client(String n, Object[] a) throws Exception {
			switch (n) {
				case "toByteArray":
					return encode((BdfList) a[0]);
				case "toList":
					if (a[0] instanceof byte[]) return decode((byte[]) a[0]);
					if (a[0] instanceof Message) {
						return decode(((Message) a[0]).getBody());
					}
					break;
				case "createMessage":
					return new Message(new MessageId(getRandomId()),
							(GroupId) a[0], (Long) a[1], (byte[]) a[2]);
				case "addLocalMessage": {
					Message m = (Message) a[a.length == 5 ? 1 : 0];
					Node to = nodeFor(contactOf(m.getGroupId()));
					outbox.add(new Packet(this, to, decode(m.getBody()),
							m.getBody(), m.getTimestamp()));
					return null;
				}
				case "getMessageIds":
					return new ArrayList<MessageId>();
				case "mergeMessageMetadata":
					return null;
				case "getMessageMetadataAsDictionary":
					return new HashMap<MessageId, BdfDictionary>();
				default:
					break;
			}
			throw new UnsupportedOperationException(n);
		}

		@Nullable
		private Object crypto(String n, Object[] a) throws Exception {
			switch (n) {
				case "hash":
					return hash((String) a[0], (byte[][]) a[1]);
				case "hybridSign":
					return signature((String) a[0], (byte[]) a[1], pub, mlDsa);
				case "verifyHybridSignature": {
					HybridSignaturePublicKey k = (HybridSignaturePublicKey) a[3];
					return Arrays.equals((byte[]) a[0], signature(
							(String) a[1], (byte[]) a[2],
							k.getEd25519PublicKey(), k.getMlDsaPublicKey()));
				}
				default:
					throw new UnsupportedOperationException(n);
			}
		}

		@Nullable
		private Object identity(String n, Object[] a) {
			switch (n) {
				case "getLocalAuthor":
					return local;
				case "getLocalMlDsaSigPublicKey":
					return mlDsa;
				case "getLocalMlDsaSigPrivateKey":
					return new byte[PostQuantumConstants
							.ML_DSA_65_PRIVATE_KEY_BYTES];
				default:
					throw new UnsupportedOperationException(n);
			}
		}

		@Nullable
		private Object contactsCall(String n, Object[] a) throws Exception {
			switch (n) {
				case "getContact": {
					Contact c = contacts.get((ContactId) a[a.length - 1]);
					if (c == null) throw new NoSuchContactException();
					return c;
				}
				case "getContacts":
					return new ArrayList<>(contacts.values());
				default:
					throw new UnsupportedOperationException(n);
			}
		}

		@Nullable
		private Object messaging(String n, Object[] a) {
			if (n.equals("getContactGroup")) {
				return contactGroups.computeIfAbsent(((Contact) a[0]).getId(),
						k -> new Group(new GroupId(getRandomId()),
								new ClientId("test"), 0, new byte[1]));
			}
			if (n.equals("getContactClientMinorVersion")) {
				Integer v = versions.get((ContactId) a[1]);
				return v == null ? MessagingManager.MINOR_VERSION : v;
			}
			throw new UnsupportedOperationException(n);
		}

		private ContactId contactOf(GroupId g) {
			for (Map.Entry<ContactId, Group> e : contactGroups.entrySet()) {
				if (e.getValue().getId().equals(g)) return e.getKey();
			}
			throw new AssertionError("unknown contact group");
		}

		private Node nodeFor(ContactId c) {
			Contact contact = contacts.get(c);
			if (contact == null) throw new AssertionError("no contact " + c);
			byte[] p = contact.getAuthor().getPublicKey().getEncoded();
			for (Node node : nodes) {
				if (Arrays.equals(node.pub, p)) return node;
			}
			throw new AssertionError("no node for contact");
		}
	}

	Node node(String name, int b) {
		Node n = new Node(name, (byte) b);
		nodes.add(n);
		return n;
	}

	Packet packet(Node from, Node to, BdfList body) {
		return new Packet(from, to, body, encode(body), now);
	}

	void connect(Node a, Node b) {
		add(a, b);
		add(b, a);
	}

	private void add(Node to, Node other) {
		ContactId id = new ContactId(NEXT_CONTACT.getAndIncrement());
		Author author = new Author(new AuthorId(getRandomId()), 1, other.name,
				new SignaturePublicKey(other.pub));
		to.contacts.put(id, new Contact(id, author, to.local.getId(), null,
				null, true, true, false, other.mlDsa));
	}

	void pump() {
		pump(p -> false);
	}

	void pump(Predicate<Packet> hold) {
		for (int round = 0; round < 1000; round++) {
			boolean progress = false;
			for (Node n : nodes) {
				while (!n.pendingEvents.isEmpty()) {
					Event e = n.pendingEvents.remove(0);
					n.manager.eventOccurred(e);
					progress = true;
				}
			}
			List<Packet> batch = new ArrayList<>();
			for (Node n : nodes) {
				batch.addAll(n.outbox);
				n.outbox.clear();
			}
			for (Packet p : batch) {
				if (hold.test(p)) {
					held.add(p);
					continue;
				}
				delivered.add(p);
				p.to.receive(p);
				progress = true;
			}
			if (!progress) return;
		}
		throw new AssertionError("the network did not settle");
	}

	void release() {
		List<Packet> again = new ArrayList<>(held);
		held.clear();
		for (Node n : nodes) {
			if (!again.isEmpty()) {
				n.outbox.addAll(0, filter(again, n));
			}
		}
		pump();
	}

	private static List<Packet> filter(List<Packet> packets, Node from) {
		List<Packet> out = new ArrayList<>();
		for (Packet p : packets) if (p.from == from) out.add(p);
		return out;
	}

	List<Packet> heldPackets() {
		return Collections.unmodifiableList(held);
	}

	@Nullable
	private Event toEvent(ContactId from, Packet p) {
		BdfList r = p.body;
		try {
			int type = p.type();
			switch (type) {
				case 32: {
					long ttl = r.size() > 7 ? r.getLong(7) : 0L;
					return new GroupPostReceivedEvent(from,
							new MessageId(getRandomId()), r.getRaw(1),
							r.getLong(2), r.getRaw(3), r.getString(4),
							r.getRaw(5), p.timestamp, ttl, r.getRaw(6));
				}
				case 33: {
					byte[] gid = r.getRaw(1);
					byte[] target = r.getRaw(2);
					long epoch = r.getLong(4);
					long ts = r.getLong(5);
					return new GroupMembershipChangedEvent(from,
							ChangeKind.MEMBER_ADDED, gid, epoch, ts, target,
							r.getString(3), 0L, 0L, r.getRaw(6),
							membershipSignedInput(gid, target, (int) epoch,
									ts));
				}
				case 34: {
					byte[] gid = r.getRaw(1);
					byte[] target = r.getRaw(2);
					long fromEpoch = r.getLong(3);
					long toEpoch = r.getLong(4);
					long ts = r.getLong(5);
					return new GroupMembershipChangedEvent(from,
							ChangeKind.MEMBER_REMOVED, gid, toEpoch, ts, target,
							null, fromEpoch, toEpoch, r.getRaw(6),
							removedSignedInput(gid, target, (int) fromEpoch,
									(int) toEpoch, ts));
				}
				case 36: {
					byte[] gid = r.getRaw(1);
					long epoch = r.getLong(2);
					long ts = r.getLong(3);
					return new GroupMembershipChangedEvent(from,
							ChangeKind.GROUP_DISSOLVED, gid, epoch, ts, null,
							null, 0L, 0L, r.getRaw(4),
							dissolveSignedInput(gid, (int) epoch, ts));
				}
				case 41: {
					byte[] gid = r.getRaw(1);
					long epoch = r.getLong(2);
					long ts = r.getLong(3);
					BdfList members = r.getList(4);
					ByteBuffer canonical = ByteBuffer.allocate(
							members.size() * 37);
					for (int i = 0; i < members.size(); i++) {
						BdfList m = members.getList(i);
						canonical.put(m.getRaw(0));
						canonical.put((byte) m.getLong(4).intValue());
						canonical.putInt(m.getLong(3).intValue());
					}
					byte[] mc = canonical.array();
					return new GroupMemberListSnapshotEvent(from, gid, epoch,
							p.timestamp, mc, r.getRaw(5),
							snapshotSignedInput(gid, (int) epoch, ts, mc),
							p.bytes);
				}
				case 42:
					return new GroupTrInviteOfferReceivedEvent(from,
							new MessageId(getRandomId()), r.getRaw(1),
							r.getString(2), r.getRaw(3), r.getString(4),
							r.getRaw(5), r.getLong(6), r.getRaw(7));
				case 43:
				case 44:
					return new GroupTrInviteResponseReceivedEvent(from,
							r.getRaw(1), r.getLong(2), r.getRaw(3),
							type == 43
									? GroupTrInviteResponseReceivedEvent.Kind.ACCEPT
									: GroupTrInviteResponseReceivedEvent.Kind.DECLINE);
				case 55:
					return new GroupTrInviteResponseReceivedEvent(from,
							r.getRaw(1), r.getLong(2), r.getRaw(4),
							GroupTrInviteResponseReceivedEvent.Kind.ACCEPT,
							r.getString(3));
				case 48:
				case 49:
				case 50:
				case 51:
				case 52:
				case 53:
				case 54:
				case 56:
					return new GroupTrMemberInviteRecordEvent(from,
							new MessageId(getRandomId()), type, r);
				default:
					return null;
			}
		} catch (FormatException e) {
			throw new AssertionError(e);
		}
	}

	static byte[] membershipSignedInput(byte[] groupId, byte[] target,
			int epoch, long timestamp) {
		ByteBuffer b = ByteBuffer.allocate(77);
		b.put(groupId);
		b.put(target);
		b.putInt(epoch);
		b.putLong(timestamp);
		b.put((byte) 0x01);
		return b.array();
	}

	static byte[] removedSignedInput(byte[] groupId, byte[] target,
			int fromEpoch, int toEpoch, long timestamp) {
		ByteBuffer b = ByteBuffer.allocate(81);
		b.put(groupId);
		b.put(target);
		b.putInt(fromEpoch);
		b.putInt(toEpoch);
		b.putLong(timestamp);
		b.put((byte) 0x02);
		return b.array();
	}

	static byte[] dissolveSignedInput(byte[] groupId, int epoch,
			long timestamp) {
		ByteBuffer b = ByteBuffer.allocate(45);
		b.put(groupId);
		b.putInt(epoch);
		b.putLong(timestamp);
		b.put((byte) 0x04);
		return b.array();
	}

	static byte[] snapshotSignedInput(byte[] groupId, int epoch,
			long timestamp, byte[] memberCanonical) {
		ByteBuffer b = ByteBuffer.allocate(77);
		b.put(groupId);
		b.putInt(epoch);
		b.putLong(timestamp);
		b.put(hash("org.zerionproject/GROUP_MEMBER_LIST",
				new byte[][] {memberCanonical}));
		b.put((byte) 0x07);
		return b.array();
	}

	static byte[] sign(Node signer, String label, byte[] signed) {
		return signature(label, signed, signer.pub, signer.mlDsa);
	}

	static byte[] signature(String label, byte[] signed, byte[] ed25519,
			byte[] mlDsa) {
		byte[] d = hash("test-signature/" + label,
				new byte[][] {signed, ed25519, mlDsa});
		byte[] sig = new byte[PostQuantumConstants.HYBRID_SIGNATURE_BYTES];
		for (int i = 0; i < sig.length; i++) sig[i] = d[i % d.length];
		return sig;
	}

	static byte[] hash(String label, byte[][] inputs) {
		try {
			MessageDigest d = MessageDigest.getInstance("SHA-256");
			d.update(label.getBytes(StandardCharsets.UTF_8));
			for (byte[] in : inputs) d.update(in);
			return d.digest();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	static byte[] encode(BdfList list) {
		StringBuilder sb = new StringBuilder();
		render(list, sb);
		byte[] token = hash("test-bdf", new byte[][] {
				sb.toString().getBytes(StandardCharsets.UTF_8)});
		ENCODED.put(toHexString(token), list);
		return token;
	}

	static BdfList decode(byte[] bytes) throws FormatException {
		BdfList l = ENCODED.get(toHexString(bytes));
		if (l == null) throw new FormatException();
		return l;
	}

	private static void render(@Nullable Object o, StringBuilder sb) {
		if (o == null || o == BdfDictionary.NULL_VALUE) {
			sb.append('N');
		} else if (o instanceof Boolean) {
			sb.append((Boolean) o ? "T" : "F");
		} else if (o instanceof Long || o instanceof Integer
				|| o instanceof Short || o instanceof Byte) {
			sb.append('I').append(((Number) o).longValue()).append(';');
		} else if (o instanceof Number) {
			sb.append('D').append(((Number) o).doubleValue()).append(';');
		} else if (o instanceof String) {
			String s = (String) o;
			sb.append('S').append(s.length()).append(':').append(s);
		} else if (o instanceof byte[]) {
			sb.append('R').append(toHexString((byte[]) o)).append(';');
		} else if (o instanceof List) {
			sb.append('L').append(((List<?>) o).size()).append('[');
			for (Object item : (List<?>) o) render(item, sb);
			sb.append(']');
		} else if (o instanceof Map) {
			Map<String, Object> sorted = new TreeMap<>();
			for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
				sorted.put((String) e.getKey(), e.getValue());
			}
			sb.append('M').append(sorted.size()).append('[');
			for (Map.Entry<String, Object> e : sorted.entrySet()) {
				render(e.getKey(), sb);
				render(e.getValue(), sb);
			}
			sb.append(']');
		} else {
			throw new AssertionError("cannot encode " + o.getClass());
		}
	}

	private static <T> T fake(Class<T> type, Handler h) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type}, (proxy, method, args) -> {
					Object[] a = args == null ? new Object[0] : args;
					switch (method.getName()) {
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == a[0];
						case "toString":
							return type.getSimpleName();
						default:
							return h.handle(method.getName(), a);
					}
				}));
	}
}
