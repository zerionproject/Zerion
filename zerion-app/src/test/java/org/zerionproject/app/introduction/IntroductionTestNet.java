package org.zerionproject.app.introduction;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorFactory;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.zerionproject.app.api.identity.AuthorManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.db.Metadata.REMOVE;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getTransportPropertiesMap;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.api.introduction.IntroductionManager.CLIENT_ID;
import static org.zerionproject.app.api.introduction.IntroductionManager.MAJOR_VERSION;
import static org.zerionproject.app.test.BriarTestUtils.getRealLocalAuthor;

final class IntroductionTestNet {

	interface AcceptRewrite {
		Map<TransportId, TransportProperties> rewrite(Party to,
				Map<TransportId, TransportProperties> properties);
	}

	interface DeliveryHook {
		void beforeDelivery(@Nullable Party to, MessageType type)
				throws Exception;
	}

	interface Handler {
		@Nullable
		Object handle(String name, Method method, Object[] args)
				throws Throwable;
	}

	static final AcceptRewrite FORWARD = (to, properties) -> properties;

	static final class Call {
		final String method;
		@Nullable
		final String label;
		final List<byte[]> keys;
		final List<byte[]> inputs;
		@Nullable
		final byte[] result;
		@Nullable
		final SecretKey derived;
		@Nullable
		final Boolean verified;

		Call(String method, @Nullable String label, List<byte[]> keys,
				List<byte[]> inputs, @Nullable byte[] result,
				@Nullable SecretKey derived, @Nullable Boolean verified) {
			this.method = method;
			this.label = label;
			this.keys = keys;
			this.inputs = inputs;
			this.result = result;
			this.derived = derived;
			this.verified = verified;
		}
	}

	static final class Transition {
		final IntroduceeSession input;
		final List<byte[]> inputSecrets;
		final IntroduceeSession output;

		Transition(IntroduceeSession input, List<byte[]> inputSecrets,
				IntroduceeSession output) {
			this.input = input;
			this.inputSecrets = inputSecrets;
			this.output = output;
		}
	}

	static final class Recorder {
		final List<Call> calls = new ArrayList<>();
		final List<Object> returned = new ArrayList<>();

		List<Call> derivations(String label) {
			List<Call> found = new ArrayList<>();
			for (Call c : calls) {
				if (c.derived != null && label.equals(c.label)) found.add(c);
			}
			return found;
		}

		List<Call> producing(byte[] value) {
			List<Call> found = new ArrayList<>();
			for (Call c : calls) {
				if (c.result != null && Arrays.equals(c.result, value)) {
					found.add(c);
				}
			}
			return found;
		}

		boolean usedAsKey(byte[] value) {
			for (Call c : calls) {
				for (byte[] key : c.keys) {
					if (Arrays.equals(key, value)) return true;
				}
			}
			return false;
		}

		boolean usedAsInput(byte[] value) {
			for (Call c : calls) {
				for (byte[] input : c.inputs) {
					if (Arrays.equals(input, value)) return true;
				}
			}
			return false;
		}

		boolean usesLabel(String label) {
			for (Call c : calls) if (label.equals(c.label)) return true;
			return false;
		}

		List<Call> calls(String method, String label) {
			List<Call> found = new ArrayList<>();
			for (Call c : calls) {
				if (method.equals(c.method) && label.equals(c.label)) {
					found.add(c);
				}
			}
			return found;
		}
	}

	static final class Sent {
		@Nullable
		final Party from;
		@Nullable
		final Party to;
		final MessageType type;
		final Message message;
		final BdfList body;

		Sent(@Nullable Party from, @Nullable Party to, MessageType type,
				Message message, BdfList body) {
			this.from = from;
			this.to = to;
			this.type = type;
			this.message = message;
			this.body = body;
		}
	}

	final class Party {
		final String name;
		final LocalAuthor author;
		final byte[] mlDsaPublic;
		final GroupId group;
		final Map<TransportId, TransportProperties> properties =
				getTransportPropertiesMap(2);
		final Recorder recorder = new Recorder();
		final List<byte[]> contactRoots = new ArrayList<>();
		final List<byte[]> rotationRoots = new ArrayList<>();
		final List<SecretKey> contactRootKeys = new ArrayList<>();
		final List<SecretKey> rotationRootKeys = new ArrayList<>();
		final List<Transition> transitions = new ArrayList<>();
		final List<Map<TransportId, TransportProperties>> remoteProperties =
				new ArrayList<>();
		int activations = 0;

		private final byte[] mlDsaPrivate;
		private final Group groupObject;
		private final ContactId introducerContact;
		private final IntroductionCryptoImpl crypto;
		private final Metadata stored = new Metadata();
		private IntroduceeProtocolEngine engine;

		private Party(String name, int contactId, int keyScheduleVersion) {
			this.name = name;
			author = getRealLocalAuthor(authorFactory);
			KeyPair hybrid = cryptoComponent.generateHybridSignatureKeyPair();
			mlDsaPublic = ((HybridSignaturePublicKey) hybrid.getPublic())
					.getMlDsaPublicKey();
			mlDsaPrivate = ((HybridSignaturePrivateKey) hybrid.getPrivate())
					.getMlDsaPrivateKey();
			groupObject = getGroup(CLIENT_ID, MAJOR_VERSION);
			group = groupObject.getId();
			introducerContact = new ContactId(contactId);
			crypto = new IntroductionCryptoImpl(recording(recorder),
					clientHelper, recordingMlKem(recorder));
			engine = engine(keyScheduleVersion);
		}

		void setKeyScheduleVersion(int keyScheduleVersion) {
			engine = engine(keyScheduleVersion);
		}

		@Nullable
		IntroduceeSession session() throws Exception {
			if (stored.isEmpty()) return null;
			return sessionParser.parseIntroduceeSession(group,
					metadataParser.parse(stored));
		}

		BdfDictionary storedSession() throws Exception {
			return metadataParser.parse(stored);
		}

		void accept() throws Exception {
			Transaction txn = new Transaction(null, false);
			IntroduceeSession in = session();
			List<byte[]> secrets = secrets(in);
			store(checked(in, secrets, engine.onAcceptAction(txn, in)));
		}

		void decline() throws Exception {
			Transaction txn = new Transaction(null, false);
			IntroduceeSession in = session();
			List<byte[]> secrets = secrets(in);
			store(checked(in, secrets,
					engine.onDeclineAction(txn, in, false)));
		}

		private IntroduceeSession checked(@Nullable IntroduceeSession in,
				List<byte[]> inputSecrets, IntroduceeSession out) {
			List<byte[]> kept = secrets(out);
			for (byte[] secret : inputSecrets) {
				boolean stillHeld = false;
				for (byte[] k : kept) if (k == secret) stillHeld = true;
				if (!stillHeld && !allZero(secret)) {
					throw new AssertionError("dropped and not wiped");
				}
			}
			if (in != null) transitions.add(new Transition(in, inputSecrets, out));
			return out;
		}

		List<Sent> sent(MessageType type) {
			List<Sent> found = new ArrayList<>();
			for (Sent s : wire) {
				if (s.from == this && s.type == type) found.add(s);
			}
			return found;
		}

		List<Sent> received(MessageType type) {
			List<Sent> found = new ArrayList<>();
			for (Sent s : wire) {
				if (s.to == this && s.type == type) found.add(s);
			}
			return found;
		}

		private void store(IntroduceeSession s) throws Exception {
			merge(stored, sessionEncoder.encodeIntroduceeSession(s));
		}

		private void receive(Message m, BdfList body, MessageType type)
				throws Exception {
			Transaction txn = new Transaction(null, false);
			IntroduceeSession s = session();
			IntroduceeSession in = s;
			List<byte[]> inputSecrets = secrets(s);
			if (s == null) {
				Author remote =
						messageParser.parseRequestMessage(m, body).getAuthor();
				s = IntroduceeSession.getInitial(group,
						crypto.getSessionId(introducer, author, remote),
						introducer,
						crypto.isAlice(author.getId(), remote.getId()),
						remote);
			}
			switch (type) {
				case REQUEST:
					s = engine.onRequestMessage(txn, s,
							messageParser.parseRequestMessage(m, body));
					break;
				case ACCEPT:
					s = engine.onAcceptMessage(txn, s,
							messageParser.parseAcceptMessage(m, body));
					break;
				case DECLINE:
					s = engine.onDeclineMessage(txn, s,
							messageParser.parseDeclineMessage(m, body));
					break;
				case AUTH:
					s = engine.onAuthMessage(txn, s,
							messageParser.parseAuthMessage(m, body));
					break;
				case ACTIVATE:
					s = engine.onActivateMessage(txn, s,
							messageParser.parseActivateMessage(m, body));
					break;
				case ABORT:
					s = engine.onAbortMessage(txn, s,
							messageParser.parseAbortMessage(m, body));
					break;
				default:
					throw new AssertionError();
			}
			store(checked(in, inputSecrets, s));
		}

		private IntroduceeProtocolEngine engine(int keyScheduleVersion) {
			Party self = this;
			ContactManager contactManager = stub(ContactManager.class,
					(name, method, args) -> {
						if (name.equals("getContact")) {
							return new Contact(introducerContact, introducer,
									author.getId(), null, null, true);
						}
						if (name.equals("addContact")) {
							contactRootKeys.add((SecretKey) args[3]);
							contactRoots.add(
									((SecretKey) args[3]).getBytes().clone());
							return new ContactId(1000 + contactRoots.size());
						}
						throw new UnsupportedOperationException(name);
					});
			IdentityManager identityManager = stub(IdentityManager.class,
					(name, method, args) -> {
						if (name.equals("getLocalAuthor")) return author;
						if (name.equals("getLocalMlDsaSigPublicKey")) {
							return mlDsaPublic.clone();
						}
						if (name.equals("getLocalMlDsaSigPrivateKey")) {
							return mlDsaPrivate.clone();
						}
						throw new UnsupportedOperationException(name);
					});
			KeyManager keyManager = stub(KeyManager.class,
					(name, method, args) -> {
						if (name.equals("addRotationKeys")) {
							rotationRootKeys.add((SecretKey) args[2]);
							rotationRoots.add(((SecretKey) args[2]).getBytes()
									.clone());
							return Collections.singletonMap(
									new TransportId("org.zerionproject.net"),
									new KeySetId(rotationRoots.size()));
						}
						if (name.equals("activateKeys")) {
							activations++;
							return null;
						}
						throw new UnsupportedOperationException(name);
					});
			Handler transportProperties = (name, method, args) -> {
				if (name.equals("getLocalProperties")) {
					return new HashMap<>(properties);
				}
				if (name.equals("addRemoteProperties")) {
					remoteProperties.add(properties(args[2]));
					return null;
				}
				throw new UnsupportedOperationException(name);
			};
			TransportPropertyManager transportPropertyManager =
					stub(TransportPropertyManager.class, transportProperties);
			return new IntroduceeProtocolEngine(
					stub(DatabaseComponent.class, IntroductionTestNet::refuse),
					sending(self, introducerContact), contactManager,
					stub(ContactGroupFactory.class,
							IntroductionTestNet::refuse),
					stub(MessageTracker.class, IntroductionTestNet::refuse),
					identityManager, authorManager(), messageParser,
					messageEncoder, crypto, keyManager,
					transportPropertyManager, versioning(), autoDelete(),
					conversations(), clock, id -> {
			}, keyScheduleVersion);
		}
	}

	final List<Sent> wire = new ArrayList<>();

	private final ClientHelper clientHelper;
	private final CryptoComponent cryptoComponent;
	private final MlKemProvider mlKemProvider;
	private final AuthorFactory authorFactory;
	private final MetadataEncoder metadataEncoder;
	private final MetadataParser metadataParser;
	private final MessageEncoder messageEncoder;
	private final MessageParser messageParser;
	private final SessionEncoder sessionEncoder;
	private final SessionParser sessionParser;
	private final IntroductionValidator validator;
	private final IntroductionCryptoImpl plainCrypto;
	private final LocalAuthor introducer;
	private final Group introducerGroup = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Party first, second;
	private final IntroducerProtocolEngine introducerEngine;
	private final Metadata introducerStored = new Metadata();
	private final Deque<Sent> queue = new ArrayDeque<>();
	private final Clock clock;
	private long now = System.currentTimeMillis();
	private AcceptRewrite rewrite = FORWARD;
	private DeliveryHook hook = (to, type) -> {
	};

	IntroductionTestNet(ClientHelper clientHelper,
			CryptoComponent cryptoComponent, MlKemProvider mlKemProvider,
			AuthorFactory authorFactory, MetadataEncoder metadataEncoder,
			MetadataParser metadataParser, MessageEncoder messageEncoder,
			MessageParser messageParser, int aliceKeyScheduleVersion,
			int bobKeyScheduleVersion) {
		this.clientHelper = clientHelper;
		this.cryptoComponent = cryptoComponent;
		this.mlKemProvider = mlKemProvider;
		this.authorFactory = authorFactory;
		this.metadataEncoder = metadataEncoder;
		this.metadataParser = metadataParser;
		this.messageEncoder = messageEncoder;
		this.messageParser = messageParser;
		sessionEncoder = new SessionEncoderImpl(clientHelper);
		sessionParser = new SessionParserImpl(clientHelper);
		clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return now++;
			}

			@Override
			public void sleep(long milliseconds) {
				throw new UnsupportedOperationException();
			}
		};
		plainCrypto = new IntroductionCryptoImpl(cryptoComponent,
				clientHelper, mlKemProvider);
		validator = new IntroductionValidator(messageEncoder, clientHelper,
				metadataEncoder, clock, plainCrypto::isValidMlKemPublicKey);
		introducer = getRealLocalAuthor(authorFactory);
		first = new Party("first", 1, KeySchedule.CURRENT_VERSION);
		second = new Party("second", 2, KeySchedule.CURRENT_VERSION);
		alice().setKeyScheduleVersion(aliceKeyScheduleVersion);
		bob().setKeyScheduleVersion(bobKeyScheduleVersion);
		introducerEngine = introducerEngine();
	}

	Party alice() {
		return plainCrypto.isAlice(first.author.getId(),
				second.author.getId()) ? first : second;
	}

	Party bob() {
		return alice() == first ? second : first;
	}

	List<Party> parties() {
		return Arrays.asList(first, second);
	}

	Party other(Party p) {
		return p == first ? second : first;
	}

	void setRewrite(AcceptRewrite rewrite) {
		this.rewrite = rewrite;
	}

	void setHook(DeliveryHook hook) {
		this.hook = hook;
	}

	void setTime(long time) {
		now = time;
	}

	static List<byte[]> secrets(@Nullable IntroduceeSession s) {
		List<byte[]> secrets = new ArrayList<>();
		if (s == null) return secrets;
		add(secrets, s.getMasterKey());
		if (s.getLocal().ephemeralPrivateKey != null) {
			add(secrets, s.getLocal().ephemeralPrivateKey.getEncoded());
		}
		add(secrets, s.getLocal().mlKemEphemeralPrivateKey);
		add(secrets, s.getLocal().ownKemSecret);
		add(secrets, s.getLocal().macKey);
		add(secrets, s.getRemote().macKey);
		return secrets;
	}

	private static void add(List<byte[]> secrets, @Nullable byte[] secret) {
		if (secret != null) secrets.add(secret);
	}

	static boolean allZero(byte[] b) {
		for (byte x : b) if (x != 0) return false;
		return true;
	}

	IntroducerSession introducerSession() throws Exception {
		return sessionParser.parseIntroducerSession(
				metadataParser.parse(introducerStored));
	}

	void introduce() throws Exception {
		Party a = alice();
		Party b = bob();
		SessionId sessionId =
				plainCrypto.getSessionId(introducer, a.author, b.author);
		IntroducerSession s = new IntroducerSession(sessionId, a.group,
				a.author, b.group, b.author);
		Transaction txn = new Transaction(null, false);
		storeIntroducer(introducerEngine.onRequestAction(txn, s, null));
		run();
	}

	void run() throws Exception {
		int delivered = 0;
		while (!queue.isEmpty()) {
			if (++delivered > 200) throw new AssertionError();
			Sent s = queue.removeFirst();
			hook.beforeDelivery(s.to, s.type);
			Group g = s.to == null ? introducerGroup : s.to.groupObject;
			validator.validateMessage(s.message, g);
			if (s.to == null) receiveAtIntroducer(s);
			else s.to.receive(s.message, s.body, s.type);
		}
	}

	void introduceAndAccept(Party firstToAccept) throws Exception {
		introduce();
		firstToAccept.accept();
		run();
		other(firstToAccept).accept();
		run();
	}

	private Party party(GroupId g) {
		if (first.group.equals(g)) return first;
		if (second.group.equals(g)) return second;
		throw new AssertionError();
	}

	private void send(@Nullable Party from, Message m) throws Exception {
		BdfList body = clientHelper.toList(m);
		MessageType type = MessageType.fromValue(body.getInt(0));
		Party to = from == null ? party(m.getGroupId()) : null;
		Sent sent = new Sent(from, to, type, m, body);
		wire.add(sent);
		queue.addLast(sent);
	}

	private void receiveAtIntroducer(Sent sent) throws Exception {
		Transaction txn = new Transaction(null, false);
		IntroducerSession s = introducerSession();
		Message m = sent.message;
		switch (sent.type) {
			case ACCEPT:
				s = introducerEngine.onAcceptMessage(txn, s,
						messageParser.parseAcceptMessage(m, sent.body));
				break;
			case DECLINE:
				s = introducerEngine.onDeclineMessage(txn, s,
						messageParser.parseDeclineMessage(m, sent.body));
				break;
			case AUTH:
				s = introducerEngine.onAuthMessage(txn, s,
						messageParser.parseAuthMessage(m, sent.body));
				break;
			case ACTIVATE:
				s = introducerEngine.onActivateMessage(txn, s,
						messageParser.parseActivateMessage(m, sent.body));
				break;
			case ABORT:
				s = introducerEngine.onAbortMessage(txn, s,
						messageParser.parseAbortMessage(m, sent.body));
				break;
			default:
				throw new AssertionError();
		}
		storeIntroducer(s);
	}

	private void storeIntroducer(IntroducerSession s) throws Exception {
		merge(introducerStored, sessionEncoder.encodeIntroducerSession(s));
	}

	private void merge(Metadata stored, BdfDictionary d)
			throws FormatException {
		for (Entry<String, byte[]> e : metadataEncoder.encode(d).entrySet()) {
			if (e.getValue() == REMOVE) stored.remove(e.getKey());
			else stored.put(e.getKey(), e.getValue());
		}
	}

	private IntroducerProtocolEngine introducerEngine() {
		ContactManager contactManager = stub(ContactManager.class,
				(name, method, args) -> {
					if (name.equals("getContact")) {
						Party p = first.author.getId().equals(args[1])
								? first : second;
						return new Contact(p.introducerContact, p.author,
								introducer.getId(), null, null, true);
					}
					throw new UnsupportedOperationException(name);
				});
		IdentityManager identityManager = stub(IdentityManager.class,
				(name, method, args) -> {
					if (name.equals("getLocalAuthor")) return introducer;
					throw new UnsupportedOperationException(name);
				});
		MessageEncoder rewriting = stub(MessageEncoder.class,
				(name, method, args) -> {
					if (name.equals("encodeAcceptMessage")
							&& args.length == 10) {
						args[6] = rewrite.rewrite(party((GroupId) args[0]),
								properties(args[6]));
					}
					return invoke(messageEncoder, method, args);
				});
		return new IntroducerProtocolEngine(
				stub(DatabaseComponent.class, IntroductionTestNet::refuse),
				sending(null, null), contactManager,
				stub(ContactGroupFactory.class, IntroductionTestNet::refuse),
				stub(MessageTracker.class, IntroductionTestNet::refuse),
				identityManager, authorManager(), messageParser, rewriting,
				versioning(), autoDelete(), conversations(), clock);
	}

	private ClientHelper sending(@Nullable Party from,
			@Nullable ContactId contact) {
		return stub(ClientHelper.class, (name, method, args) -> {
			switch (name) {
				case "addLocalMessage":
					send(from, (Message) args[1]);
					return null;
				case "getContactId":
					if (contact != null) return contact;
					return party((GroupId) args[1]).introducerContact;
				case "getMessageIds":
					return Collections.emptyList();
				case "mergeMessageMetadata":
					return null;
				default:
					return invoke(clientHelper, method, args);
			}
		});
	}

	private AuthorManager authorManager() {
		return stub(AuthorManager.class, (name, method, args) -> {
			if (name.equals("getAuthorInfo")) {
				return new AuthorInfo(AuthorInfo.Status.UNKNOWN);
			}
			throw new UnsupportedOperationException(name);
		});
	}

	private ClientVersioningManager versioning() {
		return stub(ClientVersioningManager.class, (name, method, args) -> {
			if (name.equals("getClientMinorVersion")) return 1;
			throw new UnsupportedOperationException(name);
		});
	}

	private AutoDeleteManager autoDelete() {
		return stub(AutoDeleteManager.class, (name, method, args) -> {
			if (name.equals("getAutoDeleteTimer")) {
				return NO_AUTO_DELETE_TIMER;
			}
			if (name.equals("receiveAutoDeleteTimer")) return null;
			throw new UnsupportedOperationException(name);
		});
	}

	private ConversationManager conversations() {
		return stub(ConversationManager.class, (name, method, args) -> {
			if (name.equals("getTimestampForOutgoingMessage")) {
				return clock.currentTimeMillis();
			}
			if (name.equals("trackMessage")) return null;
			if (name.equals("trackOutgoingMessage")) return null;
			throw new UnsupportedOperationException(name);
		});
	}

	private MlKemProvider recordingMlKem(Recorder recorder) {
		return stub(MlKemProvider.class, (name, method, args) -> {
			Object result = invoke(mlKemProvider, method, args);
			if (name.equals("generateKeyPair")) recorder.returned.add(result);
			return result;
		});
	}

	private CryptoComponent recording(Recorder recorder) {
		return stub(CryptoComponent.class, (name, method, args) -> {
			String label = null;
			List<byte[]> keys = new ArrayList<>();
			List<byte[]> inputs = new ArrayList<>();
			for (Object a : args) {
				if (a instanceof String && label == null) {
					label = (String) a;
				} else if (a instanceof SecretKey) {
					keys.add(((SecretKey) a).getBytes().clone());
				} else if (a instanceof byte[]) {
					inputs.add(((byte[]) a).clone());
				} else if (a instanceof byte[][]) {
					for (byte[] b : (byte[][]) a) inputs.add(b.clone());
				}
			}
			Object result = invoke(cryptoComponent, method, args);
			if (result instanceof KeyPair) recorder.returned.add(result);
			byte[] bytes = null;
			SecretKey derived = null;
			if (result instanceof SecretKey) {
				derived = (SecretKey) result;
				bytes = derived.getBytes().clone();
			} else if (result instanceof byte[]) {
				bytes = ((byte[]) result).clone();
			}
			Boolean verified =
					result instanceof Boolean ? (Boolean) result : null;
			recorder.calls.add(new Call(name, label, keys, inputs, bytes,
					derived, verified));
			return result;
		});
	}

	@SuppressWarnings("unchecked")
	private static Map<TransportId, TransportProperties> properties(
			Object o) {
		return (Map<TransportId, TransportProperties>) o;
	}

	@Nullable
	private static Object refuse(String name, Method method,
			Object[] args) {
		throw new UnsupportedOperationException(name);
	}

	@Nullable
	private static Object invoke(Object target, Method method, Object[] args)
			throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type}, (proxy, method, args) -> {
					String name = method.getName();
					if (method.getDeclaringClass() == Object.class) {
						if (name.equals("equals")) return proxy == args[0];
						if (name.equals("hashCode")) {
							return System.identityHashCode(proxy);
						}
						return type.getSimpleName();
					}
					return handler.handle(name, method,
							args == null ? new Object[0] : args);
				});
	}
}
