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
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelReactionHandlerStateTest {

	private static final long HOUR = 3_600_000L;
	private static final int POSTS = 20;
	private static final String REACTIONS_NS = "zerion-channels-reactions";
	private static final int BUDGET_REACTIONS = 256;
	private static final int ANONYMOUS_REACTIONS = 128;
	private static final long BUDGET_BYTES = 1536L * 1024L;

	private final Random random = new Random(503);
	private CryptoComponent crypto;
	private ChannelCodec codec;
	private ChannelSignatures signatures;
	private ChannelPullCodec pullCodec;
	private MemorySettings settings;
	private ChannelStore store;
	private ChannelReactionStore reactionStore;
	private ChannelManagerImpl manager;
	private KeyPair publisher;
	private byte[] channelId;
	private final List<KeyPair> signers = new ArrayList<>();
	private final MutableClock clock = new MutableClock();

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		ChannelCodecTestComponent bdf = DaggerChannelCodecTestComponent.create();
		codec = new ChannelCodec(crypto);
		signatures = new ChannelSignatures(crypto);
		ChannelChainVerifier chain = new ChannelChainVerifier(codec);
		pullCodec = new ChannelPullCodec(bdf.getBdfReaderFactory(),
				bdf.getBdfWriterFactory());
		settings = new MemorySettings();
		store = new ChannelStore(settings, bdf.getBdfReaderFactory(),
				bdf.getBdfWriterFactory());
		reactionStore = new ChannelReactionStore(settings,
				bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
		ChannelPostValidator validator =
				new ChannelPostValidator(codec, signatures, chain);
		ChannelPullProtocol protocol = new ChannelPullProtocol(codec,
				pullCodec, new ChannelHmacChallenge(crypto),
				new ChannelContentKey(crypto), validator, signatures, crypto);
		manager = new ChannelManagerImpl(crypto, inert(EventBus.class),
				clock, codec, signatures, chain, store,
				new ChannelContentKey(crypto), validator, protocol,
				inert(ChannelTransport.class),
				new ChannelBlobStore(config(), settings, crypto), reactionStore,
				new ChannelSubscriberStore(settings, bdf.getBdfReaderFactory(),
						bdf.getBdfWriterFactory()),
				new ChannelCommentStore(settings, bdf.getBdfReaderFactory(),
						bdf.getBdfWriterFactory()),
				new ChannelDiscussionStore(settings),
				new ChannelApplicationStore(settings, bdf.getBdfReaderFactory(),
						bdf.getBdfWriterFactory()),
				new ChannelMyApplicationsStore(settings,
						bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory()),
				new ChannelTombstoneStore(settings),
				new ChannelPostTombstoneStore(settings),
				new ChannelSelfAnnounceStore(settings),
				inert(IdentityManager.class), inert(TaskScheduler.class),
				Runnable::run);
		manager.readerFactory = bdf.getBdfReaderFactory();
		manager.writerFactory = bdf.getBdfWriterFactory();
		publisher = crypto.generateHybridSignatureKeyPair();
		byte[] salt = new byte[32];
		random.nextBytes(salt);
		channelId = crypto.hash("org.zerionproject/CHANNEL_ID",
				publisher.getPublic().getEncoded(), salt);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) publisher.getPublic();
		store.putChannel(new ChannelState(channelId, salt,
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), "name",
				"description", null, HOUR, true, null, "", 1L, true,
				POSTS - 1L, null, null,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 10L, null,
				ChannelState.NO_PINNED_POST, false,
				Collections.<ChannelDelegationCert>emptyList()));
		List<ChannelPost> posts = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < POSTS; seq++) {
			ChannelPost p = post(seq, prev);
			posts.add(p);
			prev = chain.hashOf(p);
		}
		store.writePosts(channelId, posts);
		for (int i = 0; i < 16; i++) {
			signers.add(crypto.generateHybridSignatureKeyPair());
		}
	}

	@Test
	public void oneAbsentPostReactionCreatesNoState() throws Exception {
		Map<String, Map<String, String>> before = settings.copy();
		assertFalse(send(signers.get(0), POSTS + 1000L, "x"));
		assertEquals(before, settings.copy());
	}

	@Test
	public void aHundredDistinctAbsentPostReactionsCreateNoState()
			throws Exception {
		Map<String, Map<String, String>> before = settings.copy();
		for (int i = 0; i < 100; i++) {
			assertFalse(send(signers.get(0), POSTS + 1L + i, "y"));
		}
		assertEquals(before, settings.copy());
	}

	@Test
	public void aLargeAdversarialSequenceOfAbsentPostsCreatesNoState()
			throws Exception {
		Map<String, Map<String, String>> before = settings.copy();
		long[] odd = {-1L, Long.MIN_VALUE, Long.MAX_VALUE, POSTS,
				Integer.MAX_VALUE};
		for (int i = 0; i < 400; i++) {
			long seq = i < odd.length ? odd[i]
					: POSTS + 1L + (random.nextInt() & 0x3fffffff);
			assertFalse(send(signers.get(i % signers.size()), seq,
					"z" + (i % 5)));
		}
		assertEquals(before, settings.copy());
		assertEquals(0, settings.writes(REACTIONS_NS));
	}

	@Test
	public void legitimateReactionsStillWorkAndCanBeReplaced()
			throws Exception {
		assertTrue(send(signers.get(0), 3, "❤"));
		assertTrue(send(signers.get(1), 3, "+1"));
		assertEquals(2, reactionStore.getReactions(channelId).size());
		assertTrue("a signer replaces its own reaction",
				send(signers.get(0), 3, "+1"));
		List<ChannelReaction> after = reactionStore.getReactions(channelId);
		assertEquals(2, after.size());
	}

	@Test
	public void legitimateStateStaysAtTheBudgetAndStillTakesReactionsIn()
			throws Exception {
		int accepted = 0;
		for (int seq = 0; seq < POSTS; seq++) {
			for (KeyPair s : signers) {
				if (send(s, seq, "❤")) accepted++;
			}
		}
		List<ChannelReaction> stored = reactionStore.getReactions(channelId);
		assertEquals("every valid reaction is taken in",
				POSTS * signers.size(), accepted);
		assertEquals(ANONYMOUS_REACTIONS, stored.size());
		long bytes = storedBytes(stored);
		assertTrue("stored " + bytes + " bytes of reaction material",
				bytes <= BUDGET_BYTES);
		KeyPair late = crypto.generateHybridSignatureKeyPair();
		assertTrue("at the budget a new reaction is still taken in",
				send(late, 0, "❤"));
		List<ChannelReaction> after = reactionStore.getReactions(channelId);
		assertTrue(after.size() <= BUDGET_REACTIONS);
		assertTrue(storedBytes(after) <= BUDGET_BYTES);
		assertTrue("and held", holds(after, late, 0));
	}

	@Test
	public void aSubscriberStoresNoReactionForAPostItDoesNotHave()
			throws Exception {
		Map<String, Map<String, String>> before = settings.copy();
		List<ChannelReaction> incoming = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			incoming.add(reaction(signers.get(i % signers.size()),
					POSTS + 1L + i, "a"));
		}
		importReactions(incoming);
		assertEquals(before, settings.copy());
	}

	@Test
	public void aSubscriberImportsABatchInOneWriteWithinTheBudget()
			throws Exception {
		List<ChannelReaction> incoming = new ArrayList<>();
		for (int seq = 0; seq < POSTS; seq++) {
			for (KeyPair s : signers) incoming.add(reaction(s, seq, "b"));
		}
		int writesBefore = settings.writes(REACTIONS_NS);
		importReactions(incoming);
		assertEquals("one write for the whole batch", writesBefore + 1,
				settings.writes(REACTIONS_NS));
		List<ChannelReaction> stored = reactionStore.getReactions(channelId);
		assertTrue(stored.size() > 0);
		assertTrue(stored.size() <= BUDGET_REACTIONS);
		assertTrue(storedBytes(stored) <= BUDGET_BYTES);
	}

	@Test
	public void aChannelStuffedBeforeTheCeilingsIsBoundedAndServedWithinThem()
			throws Exception {
		List<ChannelReaction> legacy = new ArrayList<>();
		for (int i = 0; i < 4096; i++) {
			byte[] ed = new byte[32];
			ed[0] = (byte) i;
			ed[1] = (byte) (i >> 8);
			legacy.add(new ChannelReaction(i % POSTS, "x", ed,
					new byte[1952], HOUR * i, new byte[3373]));
		}
		storeLegacyRow(legacy);
		String legacyRow = settings.getSettings(REACTIONS_NS)
				.get(ChannelStore.hex(channelId));

		byte[] response = pull();
		assertTrue("a pull is served", response.length > 0);
		assertTrue("a pull response of " + response.length + " bytes",
				response.length < 2 * 1024 * 1024);
		List<ChannelReaction> served = pullCodec.decodePullResponse(
				response, channelId).reactions;
		assertTrue(served.size() <= BUDGET_REACTIONS);
		assertTrue(storedBytes(served) <= BUDGET_BYTES);

		String row = settings.getSettings(REACTIONS_NS)
				.get(ChannelStore.hex(channelId));
		assertTrue("the trimmed state is stored: " + row.length() + " of "
				+ legacyRow.length() + " characters",
				row.length() * 8L < legacyRow.length());
		List<ChannelReaction> stored = reactionStore.getReactions(channelId);
		assertEquals(served.size(), stored.size());
		int writes = settings.writes(REACTIONS_NS);
		reactionStore.getReactions(channelId);
		assertEquals("reading bounded state writes nothing", writes,
				settings.writes(REACTIONS_NS));
	}

	private byte[] pull() throws Exception {
		byte[] request = pullCodec.encodePullRequest(channelId, -1L, null,
				null);
		Method m = ChannelManagerImpl.class.getDeclaredMethod(
				"handlePublisherRequest", byte[].class, byte[].class);
		m.setAccessible(true);
		return (byte[]) m.invoke(manager, channelId, request);
	}

	private void storeLegacyRow(List<ChannelReaction> legacy)
			throws Exception {
		org.zerionproject.core.api.data.BdfList list =
				new org.zerionproject.core.api.data.BdfList();
		for (ChannelReaction r : legacy) {
			org.zerionproject.core.api.data.BdfDictionary d =
					new org.zerionproject.core.api.data.BdfDictionary();
			d.put("seq", r.getPostSeqNum());
			d.put("emoji", r.getEmoji());
			d.put("ed", r.getSignerEd25519PubKey());
			d.put("ml", r.getSignerMlDsaPubKey());
			d.put("ts", r.getTimestampHourMs());
			d.put("sig", r.getSignature());
			list.add(d);
		}
		java.io.ByteArrayOutputStream out =
				new java.io.ByteArrayOutputStream();
		org.zerionproject.core.api.data.BdfWriter w =
				DaggerChannelCodecTestComponent.create().getBdfWriterFactory()
						.createWriter(out);
		w.writeList(list);
		w.flush();
		Settings s = new Settings();
		s.put(ChannelStore.hex(channelId), java.util.Base64.getEncoder()
				.withoutPadding().encodeToString(out.toByteArray()));
		settings.mergeSettings(s, REACTIONS_NS);
	}

	@Test
	public void anAttackerCannotExhaustReactionsForGood() throws Exception {
		List<KeyPair> flood = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			flood.add(crypto.generateHybridSignatureKeyPair());
		}
		for (int seq = 0; seq < POSTS; seq++) {
			for (KeyPair k : flood) assertTrue(send(k, seq, "x"));
		}
		assertEquals(ANONYMOUS_REACTIONS,
				reactionStore.getReactions(channelId).size());
		for (int i = 0; i < signers.size(); i++) {
			assertTrue("a legitimate reaction after the flood is taken in",
					send(signers.get(i), i % POSTS, "❤"));
		}
		List<ChannelReaction> held = reactionStore.getReactions(channelId);
		for (int i = 0; i < signers.size(); i++) {
			assertTrue("and held", holds(held, signers.get(i), i % POSTS));
		}
		assertTrue(held.size() <= BUDGET_REACTIONS);
	}

	@Test
	public void theWriteAllowanceBoundsDiskWorkAndRefills()
			throws Exception {
		clock.stopped = true;
		int accepted = 0;
		boolean refused = false;
		outer:
		for (int seq = 0; seq < POSTS; seq++) {
			for (KeyPair k : signers) {
				if (send(k, seq, "❤")) {
					accepted++;
				} else {
					refused = true;
					break outer;
				}
			}
		}
		assertTrue("a burst of writes is allowed, " + accepted, accepted > 30);
		assertTrue("then the allowance is spent", refused);
		String row = settings.getSettings(REACTIONS_NS)
				.get(ChannelStore.hex(channelId));
		KeyPair k = crypto.generateHybridSignatureKeyPair();
		assertFalse(send(k, 1, "❤"));
		assertEquals("a refusal writes nothing", row,
				settings.getSettings(REACTIONS_NS)
						.get(ChannelStore.hex(channelId)));
		clock.now += HOUR;
		assertTrue("the allowance refills", send(k, 1, "❤"));
	}

	@Test
	public void aSubscriberHoldsExactlyThePublishersCurrentSet()
			throws Exception {
		List<ChannelReaction> first = new ArrayList<>();
		for (int i = 0; i < signers.size(); i++) {
			first.add(reaction(signers.get(i), i % POSTS, "a"));
		}
		importReactions(first);
		assertEquals(first.size(),
				reactionStore.getReactions(channelId).size());
		List<ChannelReaction> second = new ArrayList<>(first.subList(4, 10));
		second.add(reaction(signers.get(0), 7, "b"));
		importReactions(second);
		List<ChannelReaction> held = reactionStore.getReactions(channelId);
		assertEquals("what the publisher let go is gone", 7, held.size());
		assertTrue(holds(held, signers.get(0), 7));
		assertFalse(holds(held, signers.get(1), 1));
		int writes = settings.writes(REACTIONS_NS);
		importReactions(second);
		assertEquals("the same set again writes nothing", writes,
				settings.writes(REACTIONS_NS));
	}

	private static boolean holds(List<ChannelReaction> rs, KeyPair signer,
			long seq) {
		byte[] ed = ((HybridSignaturePublicKey) signer.getPublic())
				.getEd25519PublicKey();
		for (ChannelReaction r : rs) {
			if (r.getPostSeqNum() == seq
					&& java.util.Arrays.equals(r.getSignerEd25519PubKey(), ed)) {
				return true;
			}
		}
		return false;
	}

	private static long storedBytes(List<ChannelReaction> rs) {
		long total = 0;
		for (ChannelReaction r : rs) {
			total += 16L + r.getEmoji().getBytes(
					java.nio.charset.StandardCharsets.UTF_8).length
					+ r.getSignerEd25519PubKey().length
					+ r.getSignerMlDsaPubKey().length
					+ r.getSignature().length;
		}
		return total;
	}

	private ChannelReaction reaction(KeyPair signer, long seq, String emoji)
			throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) signer.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) signer.getPublic();
		long ts = clock.now / HOUR * HOUR;
		byte[] sig = signatures.signUserReaction(
				codec.reactionSignedInput(channelId, seq, emoji, ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		return new ChannelReaction(seq, emoji, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), ts, sig);
	}

	private boolean send(KeyPair signer, long seq, String emoji)
			throws Exception {
		if (!clock.stopped) clock.now += HOUR;
		ChannelReaction r = reaction(signer, seq, emoji);
		byte[] request = pullCodec.encodeReactionRequest(channelId, seq,
				emoji, r.getTimestampHourMs(), r.getSignerEd25519PubKey(),
				r.getSignerMlDsaPubKey(), r.getSignature(), null, null);
		Method m = ChannelManagerImpl.class.getDeclaredMethod(
				"handlePublisherRequest", byte[].class, byte[].class);
		m.setAccessible(true);
		byte[] ack = (byte[]) m.invoke(manager, channelId, request);
		return ack != null && ack.length > 0
				&& pullCodec.decodeReactionAck(ack);
	}

	private void importReactions(List<ChannelReaction> incoming)
			throws Exception {
		Method m = ChannelManagerImpl.class.getDeclaredMethod(
				"applyIncomingReactions", byte[].class, List.class);
		m.setAccessible(true);
		try {
			m.invoke(manager, channelId, incoming);
		} catch (InvocationTargetException e) {
			throw (Exception) e.getCause();
		}
	}

	private ChannelPost post(long seq, byte[] prev) throws Exception {
		long ts = HOUR * (seq + 2);
		List<ChannelPost.ChannelAttachment> none = Collections.emptyList();
		String body = "post " + seq;
		byte[] input = codec.postSignedInput(channelId, seq, prev, ts, body,
				codec.attachmentsHash(none), 0L);
		byte[] sig = signatures.signPost(input,
				(HybridSignaturePrivateKey) publisher.getPrivate());
		return new ChannelPost(channelId, seq, prev, ts, body, none, 0L, sig,
				false);
	}

	private static DatabaseConfig config() {
		File root = new File(System.getProperty("java.io.tmpdir"),
				"zt-f05-" + System.nanoTime());
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

	@SuppressWarnings("unchecked")
	private static <T> T inert(Class<T> type) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type}, (proxy, method, args) -> {
					Class<?> r = method.getReturnType();
					if (r == boolean.class) return false;
					if (r == int.class) return 0;
					if (r == long.class) return 0L;
					return null;
				});
	}

	private static final class MutableClock implements Clock {
		long now = 1_800_000_000_000L;
		boolean stopped;

		@Override
		public long currentTimeMillis() {
			return now;
		}

		@Override
		public void sleep(long milliseconds) {
		}
	}

	private static final class MemorySettings implements SettingsManager {
		private final Map<String, Settings> byNamespace = new HashMap<>();
		private final Map<String, Integer> writes = new HashMap<>();

		@Override
		public synchronized Settings getSettings(String namespace) {
			Settings s = new Settings();
			Settings stored = byNamespace.get(namespace);
			if (stored != null) s.putAll(stored);
			return s;
		}

		@Override
		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		@Override
		public synchronized void mergeSettings(Settings s, String namespace) {
			Settings merged = getSettings(namespace);
			merged.putAll(s);
			byNamespace.put(namespace, merged);
			writes.merge(namespace, 1, Integer::sum);
		}

		@Override
		public void mergeSettings(Transaction txn, Settings s,
				String namespace) throws DbException {
			mergeSettings(s, namespace);
		}

		synchronized int writes(String namespace) {
			return writes.getOrDefault(namespace, 0);
		}

		synchronized Map<String, Map<String, String>> copy() {
			Map<String, Map<String, String>> out = new HashMap<>();
			for (Map.Entry<String, Settings> e : byNamespace.entrySet()) {
				Map<String, String> m = new HashMap<>();
				for (String k : e.getValue().keySet()) {
					m.put(k, e.getValue().get(k));
				}
				out.put(e.getKey(), m);
			}
			return out;
		}
	}

}
