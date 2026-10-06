package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfWriter;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelCommentHandlerStateTest {

	private static final long HOUR = 3_600_000L;
	private static final int POSTS = 20;
	private static final String COMMENTS_NS = "zerion-channels-comments";
	private static final int BUDGET_COMMENTS = 256;
	private static final int ANONYMOUS_PER_POST = 32;
	private static final int ANONYMOUS_PER_AUTHOR = 8;
	private static final long BUDGET_BYTES = 1536L * 1024L;
	private static final int MAX_NAME_CHARS = 64;

	private final Random random = new Random(1109);
	private CryptoComponent crypto;
	private ChannelCodec codec;
	private ChannelSignatures signatures;
	private ChannelPullCodec pullCodec;
	private MemorySettings settings;
	private ChannelStore store;
	private ChannelCommentStore commentStore;
	private ChannelManagerImpl manager;
	private KeyPair publisher;
	private byte[] channelId;
	private final List<KeyPair> authors = new ArrayList<>();
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
		commentStore = new ChannelCommentStore(settings,
				bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
		ChannelDiscussionStore discussions =
				new ChannelDiscussionStore(settings);
		ChannelPostValidator validator =
				new ChannelPostValidator(codec, signatures, chain);
		ChannelPullProtocol protocol = new ChannelPullProtocol(codec,
				pullCodec, new ChannelHmacChallenge(crypto),
				new ChannelContentKey(crypto), validator, signatures, crypto);
		manager = new ChannelManagerImpl(crypto, inert(EventBus.class),
				clock, codec, signatures, chain, store,
				new ChannelContentKey(crypto), validator, protocol,
				inert(ChannelTransport.class),
				new ChannelBlobStore(config(), settings, crypto),
				new ChannelReactionStore(settings, bdf.getBdfReaderFactory(),
						bdf.getBdfWriterFactory()),
				new ChannelSubscriberStore(settings, bdf.getBdfReaderFactory(),
						bdf.getBdfWriterFactory()),
				commentStore, discussions,
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
		discussions.setEnabled(channelId, true);
		List<ChannelPost> posts = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < POSTS; seq++) {
			ChannelPost p = post(seq, prev);
			posts.add(p);
			prev = chain.hashOf(p);
		}
		store.writePosts(channelId, posts);
		for (int i = 0; i < 12; i++) {
			authors.add(crypto.generateHybridSignatureKeyPair());
		}
	}

	@Test
	public void oneAbsentPostCommentCreatesNoState() throws Exception {
		Map<String, Map<String, String>> before = settings.copy();
		assertFalse(send(authors.get(0), POSTS + 5L, "x", "Ann"));
		assertEquals(before, settings.copy());
	}

	@Test
	public void manyAdversarialAbsentPostCommentsCreateNoState()
			throws Exception {
		Map<String, Map<String, String>> before = settings.copy();
		long[] odd = {-1L, Long.MIN_VALUE, Long.MAX_VALUE, POSTS,
				Integer.MAX_VALUE};
		for (int i = 0; i < 200; i++) {
			long seq = i < odd.length ? odd[i]
					: POSTS + 1L + (random.nextInt() & 0x3fffffff);
			assertFalse(send(authors.get(i % authors.size()), seq,
					"z" + i, "Z"));
		}
		assertEquals(before, settings.copy());
		assertEquals(0, settings.writes(COMMENTS_NS));
	}

	@Test
	public void anOversizedAuthorNameIsRefusedAndStoresNothing()
			throws Exception {
		Map<String, Map<String, String>> before = settings.copy();
		assertFalse(send(authors.get(0), 1, "hello",
				repeat('n', MAX_NAME_CHARS + 1)));
		assertFalse(send(authors.get(1), 1, "hello",
				repeat('n', 100_000)));
		assertEquals(before, settings.copy());
		assertTrue("a name at the limit is fine",
				send(authors.get(2), 1, "hello", repeat('n', MAX_NAME_CHARS)));
	}

	@Test
	public void oneStoredCommentCannotMakeThePullUndecodable()
			throws Exception {
		List<ChannelComment> legacy = new ArrayList<>();
		legacy.add(comment(authors.get(0), 1, "fine", "Ann"));
		legacy.add(comment(authors.get(1), 2, "poison",
				repeat('p', 5_000)));
		legacy.add(comment(authors.get(2), 3, "also fine", "Bo"));
		storeLegacyRow(legacy);
		byte[] response = pull();
		assertTrue("a pull is served", response.length > 0);
		List<ChannelComment> served =
				pullCodec.decodePullResponse(response, channelId).comments;
		List<String> bodies = new ArrayList<>();
		for (ChannelComment c : served) bodies.add(c.getBody());
		assertEquals(Arrays.asList("fine", "also fine"), bodies);
	}

	@Test
	public void aSubscriberStoresNoCommentForAPostItDoesNotHave()
			throws Exception {
		Map<String, Map<String, String>> before = settings.copy();
		List<ChannelComment> incoming = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			incoming.add(comment(authors.get(i % authors.size()),
					POSTS + 1L + i, "c" + i, "A"));
		}
		importComments(incoming);
		assertEquals(before, settings.copy());
	}

	@Test
	public void aSubscriberHoldsThePublishersSetInOneWrite()
			throws Exception {
		List<ChannelComment> first = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			first.add(comment(authors.get(i % authors.size()), i % POSTS,
					"first " + i, "A"));
		}
		int writes = settings.writes(COMMENTS_NS);
		importComments(first);
		assertEquals("one write for the batch", writes + 1,
				settings.writes(COMMENTS_NS));
		assertEquals(40, commentStore.getComments(channelId).size());
		List<ChannelComment> second = new ArrayList<>(first.subList(20, 40));
		second.add(comment(authors.get(0), 3, "newer", "A"));
		importComments(second);
		List<ChannelComment> held = commentStore.getComments(channelId);
		assertEquals("comments the publisher no longer holds are gone",
				21, held.size());
		assertEquals("newer", held.get(held.size() - 1).getBody());
	}

	@Test
	public void maximumSizeCommentsStayWithinTheByteBudget()
			throws Exception {
		String body = repeat('中',
				ChannelConstants.MAX_COMMENT_BODY_CHARS - 1);
		String name = repeat('中', MAX_NAME_CHARS);
		int accepted = 0;
		for (int i = 0; i < 300; i++) {
			if (send(authors.get(i % authors.size()), i % POSTS,
					body + i % 10, name)) {
				accepted++;
			}
		}
		assertEquals("every valid comment is accepted", 300, accepted);
		List<ChannelComment> held = commentStore.getComments(channelId);
		assertTrue("held " + storedBytes(held) + " bytes",
				storedBytes(held) <= BUDGET_BYTES);
		assertTrue(held.size() <= BUDGET_COMMENTS);
		byte[] response = pull();
		assertTrue("pull response of " + response.length + " bytes",
				response.length < 3 * 1024 * 1024);
	}

	@Test
	public void manySmallCommentsStayWithinTheCountBudgetAndTheNewestStay()
			throws Exception {
		List<KeyPair> many = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			many.add(crypto.generateHybridSignatureKeyPair());
		}
		for (int i = 0; i < 400; i++) {
			assertTrue(send(many.get(i % many.size()), i % POSTS,
					"s" + i, "S"));
		}
		List<ChannelComment> held = commentStore.getComments(channelId);
		assertTrue("held " + held.size(), held.size() <= BUDGET_COMMENTS);
		assertEquals("the newest comment is held", "s399",
				held.get(held.size() - 1).getBody());
		assertTrue(storedBytes(held) <= BUDGET_BYTES);
	}

	@Test
	public void oneAuthorsCommentsRollWithinThePerAuthorBudget()
			throws Exception {
		for (int i = 0; i < 100; i++) {
			assertTrue(send(authors.get(0), i % POSTS, "mine " + i, "Me"));
		}
		List<ChannelComment> held = commentStore.getComments(channelId);
		assertEquals(ANONYMOUS_PER_AUTHOR, held.size());
		assertEquals("mine 92", held.get(0).getBody());
		assertEquals("mine 99", held.get(held.size() - 1).getBody());
	}

	@Test
	public void oneThreadRollsWithinThePerPostBudgetAndOthersAreKept()
			throws Exception {
		assertTrue(send(authors.get(0), 7, "other thread", "O"));
		List<KeyPair> many = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			many.add(crypto.generateHybridSignatureKeyPair());
		}
		for (int i = 0; i < 150; i++) {
			assertTrue(send(many.get(i % many.size()), 3, "t" + i, "T"));
		}
		int onThree = 0;
		boolean otherKept = false;
		for (ChannelComment c : commentStore.getComments(channelId)) {
			if (c.getParentPostSeqNum() == 3) onThree++;
			if (c.getBody().equals("other thread")) otherKept = true;
		}
		assertEquals(ANONYMOUS_PER_POST, onThree);
		assertTrue("a flood on one thread leaves the others alone",
				otherKept);
	}

	@Test
	public void aChannelFilledByAnAttackerStillAcceptsNewComments()
			throws Exception {
		storeLegacyRow(fakeComments(4096, 5_000));
		assertTrue("a legitimate comment is accepted",
				send(authors.get(0), 2, "still possible", "Me"));
		boolean held = false;
		for (ChannelComment c : commentStore.getComments(channelId)) {
			if (c.getBody().equals("still possible")) held = true;
		}
		assertTrue(held);
		boolean served = false;
		for (ChannelComment c : pullCodec.decodePullResponse(pull(),
				channelId).comments) {
			if (c.getBody().equals("still possible")) served = true;
		}
		assertTrue(served);
	}

	@Test
	public void aLegacyOversizedChannelIsBoundedOnFirstReadAndServedSo()
			throws Exception {
		storeLegacyRow(fakeComments(4096, 0));
		String legacyRow = settings.getSettings(COMMENTS_NS)
				.get(ChannelStore.hex(channelId));
		byte[] response = pull();
		assertTrue("a pull is served", response.length > 0);
		assertTrue("a pull response of " + response.length + " bytes",
				response.length < 3 * 1024 * 1024);
		List<ChannelComment> served =
				pullCodec.decodePullResponse(response, channelId).comments;
		assertTrue(served.size() <= BUDGET_COMMENTS);
		String row = settings.getSettings(COMMENTS_NS)
				.get(ChannelStore.hex(channelId));
		assertTrue("the bounded state is stored",
				row.length() * 4L < legacyRow.length());
		int writes = settings.writes(COMMENTS_NS);
		commentStore.getComments(channelId);
		assertEquals("reading bounded state writes nothing", writes,
				settings.writes(COMMENTS_NS));
	}

	@Test
	public void aRestartKeepsTheSameComments() throws Exception {
		for (int i = 0; i < 30; i++) {
			assertTrue(send(authors.get(i % authors.size()), i % POSTS,
					"r" + i, "R"));
		}
		List<ChannelComment> before = commentStore.getComments(channelId);
		ChannelCodecTestComponent bdf = DaggerChannelCodecTestComponent.create();
		ChannelCommentStore reopened = new ChannelCommentStore(settings,
				bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
		List<ChannelComment> after = reopened.getComments(channelId);
		assertEquals(before.size(), after.size());
		for (int i = 0; i < before.size(); i++) {
			assertEquals(before.get(i).getCommentId(),
					after.get(i).getCommentId());
			assertEquals(before.get(i).getBody(), after.get(i).getBody());
		}
	}

	@Test
	public void aFullChannelPullStaysFarBelowTheResponseLimit()
			throws Exception {
		String body = repeat('中', ChannelConstants.MAX_COMMENT_BODY_CHARS);
		for (int i = 0; i < 300; i++) {
			send(authors.get(i % authors.size()), i % POSTS, body, "N");
		}
		byte[] response = pull();
		assertTrue("a pull response of " + response.length + " bytes",
				response.length < 4 * 1024 * 1024);
	}

	@Test
	public void legitimateCommentsAreServedToSubscribers() throws Exception {
		assertTrue(send(authors.get(0), 4, "first", "Ann"));
		assertTrue(send(authors.get(1), 4, "second", "Bo"));
		List<ChannelComment> served =
				pullCodec.decodePullResponse(pull(), channelId).comments;
		assertEquals(2, served.size());
		assertEquals("first", served.get(0).getBody());
		assertEquals("second", served.get(1).getBody());
	}

	private List<ChannelComment> fakeComments(int n, int nameChars) {
		List<ChannelComment> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			byte[] ed = new byte[32];
			ed[0] = (byte) i;
			ed[1] = (byte) (i >> 8);
			out.add(new ChannelComment(i % POSTS, 1_000_000L + i,
					"spam " + i, nameChars == 0 ? "S" : repeat('s', nameChars),
					ed, new byte[1952], HOUR * i, new byte[3373]));
		}
		return out;
	}

	private static String repeat(char c, int n) {
		char[] a = new char[n];
		Arrays.fill(a, c);
		return new String(a);
	}

	private static long storedBytes(List<ChannelComment> cs) {
		long total = 0;
		for (ChannelComment c : cs) {
			total += 24L + c.getBody().getBytes(StandardCharsets.UTF_8).length
					+ c.getAuthorDisplayName()
					.getBytes(StandardCharsets.UTF_8).length
					+ c.getAuthorEd25519PubKey().length
					+ c.getAuthorMlDsaPubKey().length
					+ c.getSignature().length;
		}
		return total;
	}

	private ChannelComment comment(KeyPair author, long seq, String body,
			String name) throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) author.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) author.getPublic();
		long ts = clock.currentTimeMillis() / HOUR * HOUR;
		long id = random.nextLong();
		byte[] sig = signatures.signUserComment(
				codec.commentSignedInput(channelId, seq, id, body, name, ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		return new ChannelComment(seq, id, body, name,
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), ts, sig);
	}

	private boolean send(KeyPair author, long seq, String body, String name)
			throws Exception {
		clock.advance(HOUR);
		ChannelComment c = comment(author, seq, body, name);
		byte[] request = pullCodec.encodeCommentRequest(channelId, seq,
				c.getCommentId(), body, name, c.getTimestampHourMs(),
				c.getAuthorEd25519PubKey(), c.getAuthorMlDsaPubKey(),
				c.getSignature(), null, null);
		Method m = ChannelManagerImpl.class.getDeclaredMethod(
				"handlePublisherRequest", byte[].class, byte[].class);
		m.setAccessible(true);
		byte[] ack = (byte[]) m.invoke(manager, channelId, request);
		return ack != null && ack.length > 0
				&& pullCodec.decodeCommentAck(ack);
	}

	private void importComments(List<ChannelComment> incoming)
			throws Exception {
		Method m = ChannelManagerImpl.class.getDeclaredMethod(
				"applyIncomingComments", byte[].class, List.class);
		m.setAccessible(true);
		try {
			m.invoke(manager, channelId, incoming);
		} catch (InvocationTargetException e) {
			throw (Exception) e.getCause();
		}
	}

	private byte[] pull() throws Exception {
		byte[] request = pullCodec.encodePullRequest(channelId, -1L, null,
				null);
		Method m = ChannelManagerImpl.class.getDeclaredMethod(
				"handlePublisherRequest", byte[].class, byte[].class);
		m.setAccessible(true);
		return (byte[]) m.invoke(manager, channelId, request);
	}

	private void storeLegacyRow(List<ChannelComment> legacy)
			throws Exception {
		BdfList list = new BdfList();
		for (ChannelComment c : legacy) {
			BdfDictionary d = new BdfDictionary();
			d.put("seq", c.getParentPostSeqNum());
			d.put("id", c.getCommentId());
			d.put("body", c.getBody());
			d.put("name", c.getAuthorDisplayName());
			d.put("ed", c.getAuthorEd25519PubKey());
			d.put("ml", c.getAuthorMlDsaPubKey());
			d.put("ts", c.getTimestampHourMs());
			d.put("sig", c.getSignature());
			list.add(d);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = DaggerChannelCodecTestComponent.create()
				.getBdfWriterFactory().createWriter(out);
		w.writeList(list);
		w.flush();
		Settings s = new Settings();
		s.put(ChannelStore.hex(channelId), Base64.getEncoder()
				.withoutPadding().encodeToString(out.toByteArray()));
		settings.mergeSettings(s, COMMENTS_NS);
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
				"zt-comments-" + System.nanoTime());
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
