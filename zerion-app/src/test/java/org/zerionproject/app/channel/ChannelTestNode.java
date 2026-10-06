package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.annotation.Nullable;

final class ChannelTestNode {

	static final long HOUR = 3_600_000L;
	static final String PUBLISHER_ONION =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";

	final CryptoComponent crypto;
	final MutableClock clock;
	final ChannelCodec codec;
	final ChannelSignatures signatures;
	final ChannelChainVerifier chain;
	final ChannelPullCodec pullCodec;
	final ChannelContentKey contentKey;
	final MemorySettings settings;
	final File root;
	final ChannelStore store;
	final ChannelBlobStore blobStore;
	final ChannelReactionStore reactionStore;
	final ChannelSubscriberStore subscriberStore;
	final ChannelCommentStore commentStore;
	final ChannelApplicationStore applicationStore;
	final ChannelPostTombstoneStore postTombstones;
	final ChannelManagerImpl manager;
	final List<Long> scheduledDelays =
			Collections.synchronizedList(new ArrayList<>());

	ChannelTestNode(CryptoComponent crypto, MutableClock clock,
			ChannelTransport transport) {
		this(crypto, clock, transport, (BlobStoreFactory) null);
	}

	ChannelTestNode(CryptoComponent crypto, MutableClock clock,
			ChannelTransport transport, @Nullable BlobStoreFactory blobs) {
		this(crypto, clock, transport, blobs, inert(IdentityManager.class));
	}

	ChannelTestNode(CryptoComponent crypto, MutableClock clock,
			ChannelTransport transport, @Nullable BlobStoreFactory blobs,
			IdentityManager identityManager) {
		this(crypto, clock, transport, blobs, identityManager,
				new MemorySettings());
	}

	ChannelTestNode(CryptoComponent crypto, MutableClock clock,
			ChannelTransport transport, MemorySettings settings) {
		this(crypto, clock, transport, null, inert(IdentityManager.class),
				settings, null);
	}

	ChannelTestNode(CryptoComponent crypto, MutableClock clock,
			ChannelTransport transport, ChannelTestNode dead) {
		this(crypto, clock, transport, null, inert(IdentityManager.class),
				dead.settings, dead.root);
	}

	ChannelTestNode(CryptoComponent crypto, MutableClock clock,
			ChannelTransport transport, @Nullable BlobStoreFactory blobs,
			IdentityManager identityManager, MemorySettings settings) {
		this(crypto, clock, transport, blobs, identityManager, settings,
				null);
	}

	ChannelTestNode(CryptoComponent crypto, MutableClock clock,
			ChannelTransport transport, @Nullable BlobStoreFactory blobs,
			IdentityManager identityManager, MemorySettings settings,
			@Nullable File root) {
		this.crypto = crypto;
		this.clock = clock;
		this.settings = settings;
		ChannelCodecTestComponent bdf =
				DaggerChannelCodecTestComponent.create();
		codec = new ChannelCodec(crypto);
		signatures = new ChannelSignatures(crypto);
		chain = new ChannelChainVerifier(codec);
		pullCodec = new ChannelPullCodec(bdf.getBdfReaderFactory(),
				bdf.getBdfWriterFactory());
		contentKey = new ChannelContentKey(crypto);
		this.root = root != null ? root : new File(
				System.getProperty("java.io.tmpdir"),
				"zt-node-" + System.nanoTime());
		DatabaseConfig config = config(this.root);
		store = new ChannelStore(settings, bdf.getBdfReaderFactory(),
				bdf.getBdfWriterFactory());
		blobStore = blobs == null
				? new ChannelBlobStore(config, settings, crypto)
				: blobs.create(config, settings, crypto);
		reactionStore = new ChannelReactionStore(settings,
				bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
		subscriberStore = new ChannelSubscriberStore(settings,
				bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
		commentStore = new ChannelCommentStore(settings,
				bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
		applicationStore = new ChannelApplicationStore(settings,
				bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
		postTombstones = new ChannelPostTombstoneStore(settings);
		ChannelPostValidator validator =
				new ChannelPostValidator(codec, signatures, chain);
		ChannelPullProtocol protocol = new ChannelPullProtocol(codec,
				pullCodec, new ChannelHmacChallenge(crypto), contentKey,
				validator, signatures, crypto);
		manager = new ChannelManagerImpl(crypto, inert(EventBus.class),
				clock, codec, signatures, chain, store, contentKey,
				validator, protocol, transport, blobStore, reactionStore,
				subscriberStore, commentStore,
				new ChannelDiscussionStore(settings), applicationStore,
				new ChannelMyApplicationsStore(settings,
						bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory()),
				new ChannelTombstoneStore(settings), postTombstones,
				new ChannelSelfAnnounceStore(settings),
				identityManager, recordingScheduler(),
				Runnable::run);
		manager.readerFactory = bdf.getBdfReaderFactory();
		manager.writerFactory = bdf.getBdfWriterFactory();
	}

	interface BlobStoreFactory {
		ChannelBlobStore create(DatabaseConfig config,
				SettingsManager settings, CryptoComponent crypto);
	}

	byte[] handle(byte[] channelId, byte[] request) throws Exception {
		return (byte[]) call("handlePublisherRequest",
				new Class<?>[] {byte[].class, byte[].class}, channelId,
				request);
	}

	void refreshAndReschedule() throws Exception {
		call("refreshAndReschedule", new Class<?>[0]);
	}

	Object call(String name, Class<?>[] types, Object... args)
			throws Exception {
		Method m = ChannelManagerImpl.class.getDeclaredMethod(name, types);
		m.setAccessible(true);
		try {
			return m.invoke(manager, args);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception) throw (Exception) cause;
			throw new AssertionError(cause);
		}
	}

	void seedPublisher(TestChannel c, boolean publicChannel,
			@Nullable byte[] capability, @Nullable byte[] kContent,
			boolean requiresApproval, List<ChannelPost> posts)
			throws Exception {
		long highest = posts.isEmpty() ? -1L
				: posts.get(posts.size() - 1).getSeqNum();
		store.putChannel(new ChannelState(c.channelId, c.salt, c.ed(),
				c.ml(), "name", "description", null, HOUR, publicChannel,
				capability, PUBLISHER_ONION, 1L, true, highest,
				kContent == null ? null : contentKey.hashContentKey(kContent),
				kContent, Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, requiresApproval,
				Collections.<ChannelDelegationCert>emptyList()));
		store.putPublisherPrivKey(c.channelId, c.priv().getEncoded());
		store.writePosts(c.channelId, posts);
	}

	void seedSubscriber(TestChannel c, boolean publicChannel,
			@Nullable byte[] capability, @Nullable byte[] kContent,
			List<ChannelPost> posts) throws Exception {
		long highest = posts.isEmpty() ? -1L
				: posts.get(posts.size() - 1).getSeqNum();
		store.putChannel(new ChannelState(c.channelId,
				new byte[ChannelConstants.CHANNEL_SALT_BYTES], c.ed(), c.ml(),
				"", "", null, HOUR, publicChannel, capability, PUBLISHER_ONION,
				-1L, false, highest,
				kContent == null ? null : contentKey.hashContentKey(kContent),
				kContent, Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, false,
				Collections.<ChannelDelegationCert>emptyList()));
		store.writePosts(c.channelId, posts);
	}

	ChannelPost post(TestChannel c, long seq, byte[] prev, String body,
			List<ChannelPost.ChannelAttachment> attachments)
			throws Exception {
		long ts = HOUR * (seq + 2);
		byte[] input = codec.postSignedInput(c.channelId, seq, prev, ts,
				body, codec.attachmentsHash(attachments), 0L);
		byte[] sig = signatures.signPost(input, c.priv());
		return new ChannelPost(c.channelId, seq, prev, ts, body,
				attachments, 0L, sig, false);
	}

	List<ChannelPost> posts(TestChannel c, int n) throws Exception {
		List<ChannelPost> out = new ArrayList<>(n);
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < n; seq++) {
			ChannelPost p = post(c, seq, prev, "post " + seq,
					Collections.<ChannelPost.ChannelAttachment>emptyList());
			out.add(p);
			prev = chain.hashOf(p);
		}
		return out;
	}

	ChannelReaction reaction(TestChannel c, KeyPair signer, long seq,
			String emoji) throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) signer.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) signer.getPublic();
		long ts = clock.currentTimeMillis() / HOUR * HOUR;
		byte[] sig = signatures.signUserReaction(
				codec.reactionSignedInput(c.channelId, seq, emoji, ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		return new ChannelReaction(seq, emoji, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), ts, sig);
	}

	static boolean ackOk(byte[] ack) throws IOException {
		if (ack.length == 0) return false;
		BdfReader r = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(ack));
		BdfDictionary d = r.readDictionary();
		return d.getBoolean("ok", false);
	}

	static ChannelTransport servedBy(ChannelTestNode publisher,
			byte[] channelId) {
		return new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] id,
					@Nullable String onionPrivateKey,
					ChannelRequestHandler handler) throws IOException {
				throw new IOException();
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				try {
					return publisher.handle(channelId, request);
				} catch (Exception e) {
					throw new IOException(e);
				}
			}

			@Override
			public boolean isReachable(String onion) {
				return true;
			}
		};
	}

	static ChannelTransport noTransport() {
		return inert(ChannelTransport.class);
	}

	static IdentityManager identity(KeyPair hybrid) {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) hybrid.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) hybrid.getPublic();
		org.zerionproject.core.api.identity.LocalAuthor me =
				new org.zerionproject.core.api.identity.LocalAuthor(
						new org.zerionproject.core.api.identity.AuthorId(
								new byte[32]),
						org.zerionproject.core.api.identity.Author
								.FORMAT_VERSION, "Member",
						pub.getEd25519Component(),
						priv.getEd25519Component());
		return (IdentityManager) Proxy.newProxyInstance(
				IdentityManager.class.getClassLoader(),
				new Class<?>[] {IdentityManager.class},
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "getLocalAuthor":
							return me;
						case "getLocalMlDsaSigPublicKey":
							return pub.getMlDsaPublicKey();
						case "getLocalMlDsaSigPrivateKey":
							return priv.getMlDsaPrivateKey();
						default:
							Class<?> r = method.getReturnType();
							if (r == boolean.class) return false;
							if (r == int.class) return 0;
							if (r == long.class) return 0L;
							return null;
					}
				});
	}

	static long constant(String name, long fallback) {
		try {
			return ChannelConstants.class.getField(name).getLong(null);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			return fallback;
		}
	}

	@Nullable
	Object callIfPresent(String name, Class<?>[] types, Object... args)
			throws Exception {
		try {
			return call(name, types, args);
		} catch (NoSuchMethodException e) {
			return null;
		}
	}

	private TaskScheduler recordingScheduler() {
		return (TaskScheduler) Proxy.newProxyInstance(
				TaskScheduler.class.getClassLoader(),
				new Class<?>[] {TaskScheduler.class},
				(proxy, method, args) -> {
					if (method.getName().equals("schedule")
							&& args != null && args.length == 4) {
						scheduledDelays.add((Long) args[2]);
					}
					return null;
				});
	}

	static DatabaseConfig config(File root) {
		return new DatabaseConfig() {
			@Override
			public File getDatabaseDirectory() {
				return new File(root, "db");
			}

			@Override
			public File getDatabaseKeyDirectory() {
				return new File(root, "key");
			}

			@Override
			public org.zerionproject.core.api.crypto.KeyStrengthener
			getKeyStrengthener() {
				return null;
			}
		};
	}

	void deleteFiles() {
		delete(root);
	}

	private static void delete(File f) {
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) delete(c);
		}
		f.delete();
	}

	@SuppressWarnings("unchecked")
	static <T> T inert(Class<T> type) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type}, (proxy, method, args) -> {
					Class<?> r = method.getReturnType();
					if (r == boolean.class) return false;
					if (r == int.class) return 0;
					if (r == long.class) return 0L;
					return null;
				});
	}

	static final class TestChannel {

		final KeyPair keys;
		final byte[] salt = new byte[32];
		final byte[] channelId;

		TestChannel(CryptoComponent crypto, Random random) {
			keys = crypto.generateHybridSignatureKeyPair();
			random.nextBytes(salt);
			channelId = crypto.hash("org.zerionproject/CHANNEL_ID",
					keys.getPublic().getEncoded(), salt);
		}

		byte[] ed() {
			return ((HybridSignaturePublicKey) keys.getPublic())
					.getEd25519PublicKey();
		}

		byte[] ml() {
			return ((HybridSignaturePublicKey) keys.getPublic())
					.getMlDsaPublicKey();
		}

		HybridSignaturePrivateKey priv() {
			return (HybridSignaturePrivateKey) keys.getPrivate();
		}
	}

	static final class MutableClock implements Clock {

		private long now = 1_800_000_000_000L;

		@Override
		public synchronized long currentTimeMillis() {
			return now;
		}

		@Override
		public void sleep(long milliseconds) {
		}

		synchronized void advance(long ms) {
			now += ms;
		}
	}

	static final class SimulatedCrash extends RuntimeException {
	}

	static final class MemorySettings implements SettingsManager {

		private final Map<String, Settings> byNamespace = new HashMap<>();
		private final Map<String, Integer> writes = new HashMap<>();
		private long bytesWritten;
		private long bytesRead;
		private int writeCalls;
		private int batchWrites;
		private int crashAfterWrite = -1;

		synchronized MemorySettings snapshot() {
			MemorySettings copy = new MemorySettings();
			for (Map.Entry<String, Settings> e : byNamespace.entrySet()) {
				Settings s = new Settings();
				s.putAll(e.getValue());
				copy.byNamespace.put(e.getKey(), s);
			}
			return copy;
		}

		synchronized void restoreFrom(MemorySettings other) {
			byNamespace.clear();
			for (Map.Entry<String, Settings> e
					: other.snapshot().byNamespace.entrySet()) {
				byNamespace.put(e.getKey(), e.getValue());
			}
		}

		synchronized int writeCalls() {
			return writeCalls;
		}

		synchronized int batchWrites() {
			return batchWrites;
		}

		synchronized void crashAfterWrite(int n) {
			crashAfterWrite = n < 0 ? -1 : writeCalls + n;
		}

		private void wrote() {
			writeCalls++;
			if (crashAfterWrite >= 0 && writeCalls >= crashAfterWrite) {
				crashAfterWrite = -1;
				throw new SimulatedCrash();
			}
		}

		@Nullable
		public synchronized String getSetting(String namespace, String key) {
			Settings stored = byNamespace.get(namespace);
			if (stored == null) return null;
			String v = stored.get(key);
			if (v != null) bytesRead += v.length();
			return v;
		}

		@Override
		public synchronized void mergeSettings(
				Map<String, Settings> byNamespaceToMerge) {
			for (Map.Entry<String, Settings> e
					: byNamespaceToMerge.entrySet()) {
				Settings merged = new Settings();
				Settings stored = byNamespace.get(e.getKey());
				if (stored != null) merged.putAll(stored);
				merged.putAll(e.getValue());
				for (String v : e.getValue().values()) {
					bytesWritten += v.length();
				}
				byNamespace.put(e.getKey(), merged);
				writes.merge(e.getKey(), 1, Integer::sum);
			}
			batchWrites++;
			wrote();
		}

		@Override
		public synchronized Settings getSettings(String namespace) {
			Settings s = new Settings();
			Settings stored = byNamespace.get(namespace);
			if (stored != null) {
				s.putAll(stored);
				for (String v : stored.values()) bytesRead += v.length();
			}
			return s;
		}

		synchronized long bytesWritten() {
			return bytesWritten;
		}

		synchronized long bytesRead() {
			return bytesRead;
		}

		synchronized List<String[]> rows() {
			List<String[]> out = new ArrayList<>();
			for (Map.Entry<String, Settings> e : byNamespace.entrySet()) {
				for (String k : e.getValue().keySet()) {
					out.add(new String[] {e.getKey(), k,
							e.getValue().get(k)});
				}
			}
			return out;
		}

		@Override
		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		@Override
		public synchronized void mergeSettings(Settings s, String namespace) {
			Settings merged = new Settings();
			Settings stored = byNamespace.get(namespace);
			if (stored != null) merged.putAll(stored);
			merged.putAll(s);
			for (String v : s.values()) bytesWritten += v.length();
			byNamespace.put(namespace, merged);
			writes.merge(namespace, 1, Integer::sum);
			wrote();
		}

		@Override
		public synchronized void deleteSettings(String namespace,
				java.util.Collection<String> keys) {
			Settings stored = byNamespace.get(namespace);
			if (stored != null) {
				for (String k : keys) stored.remove(k);
				if (stored.isEmpty()) byNamespace.remove(namespace);
			}
			wrote();
		}

		@Override
		public synchronized void deleteNamespaces(
				java.util.Collection<String> namespaces) {
			for (String n : namespaces) byNamespace.remove(n);
			wrote();
		}

		@Override
		public void mergeSettings(Transaction txn, Settings s,
				String namespace) {
			mergeSettings(s, namespace);
		}

		synchronized int writes(String namespace) {
			return writes.getOrDefault(namespace, 0);
		}
	}
}
