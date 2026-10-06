package org.zerionproject.app.channel;

import android.content.Context;
import android.os.Build;

import org.zerionproject.app.api.channel.ChannelComment;
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
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.crypto.CryptoForTests;
import org.zerionproject.core.data.BdfForTests;
import org.zerionproject.core.db.SqlCipherDatabaseForTests;
import org.zerionproject.core.settings.SettingsManagerForTests;
import org.zerionproject.core.test.TestUtils;
import com.professor.zerion.android.testing.Inert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ChannelReactionStateDeviceTest {

	private static final long HOUR = 3_600_000L;
	private static final int POSTS = 60;
	private static final int SIGNERS = 64;
	private static final String REACTIONS_NS = "zerion-channels-reactions";
	private static final long GROWTH_TIME_LIMIT_MS = 15L * 60L * 1000L;
	private static final int GROWTH_SENDS = 400;
	private static final int BUDGET_REACTIONS = 256;
	private static final int ANONYMOUS_REACTIONS = 128;
	private static final long BUDGET_BYTES = 1536L * 1024L;

	private final SecureRandom random = new SecureRandom();
	private final StringBuilder report = new StringBuilder();
	private final MutableClock clock = new MutableClock();

	private Context context;
	private File root;
	private SecretKey dbKey;
	private DatabaseComponent db;
	private CryptoComponent crypto;
	private BdfReaderFactory readers;
	private BdfWriterFactory writers;
	private ChannelCodec codec;
	private ChannelSignatures signatures;
	private ChannelChainVerifier chain;
	private ChannelPullCodec pullCodec;
	private ChannelReactionStore reactionStore;
	private ChannelStore store;
	private ChannelManagerImpl manager;
	private Method dispatch;
	private KeyPair publisher;
	private byte[] channelId;
	private byte[] salt;
	private List<ChannelPost> posts;
	private List<KeyPair> signers;

	@Before
	public void setUp() throws Exception {
		context = InstrumentationRegistry.getInstrumentation()
				.getTargetContext();
		root = new File(context.getCacheDir(),
				"reactions-" + System.nanoTime());
		byte[] k = new byte[SecretKey.LENGTH];
		random.nextBytes(k);
		dbKey = new SecretKey(k);
		crypto = CryptoForTests.create();
		readers = BdfForTests.readers();
		writers = BdfForTests.writers();
		codec = new ChannelCodec(crypto);
		signatures = new ChannelSignatures(crypto);
		chain = new ChannelChainVerifier(codec);
		pullCodec = new ChannelPullCodec(readers, writers);
		openDatabase();
		DatabaseComponent first = db;
		first.transaction(false, txn -> first.addIdentity(txn,
				TestUtils.getIdentity()));
		publisher = crypto.generateHybridSignatureKeyPair();
		salt = new byte[32];
		random.nextBytes(salt);
		channelId = crypto.hash("org.zerionproject/CHANNEL_ID",
				publisher.getPublic().getEncoded(), salt);
		store.putChannel(state());
		posts = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < POSTS; seq++) {
			ChannelPost p = post(seq, prev);
			posts.add(p);
			prev = chain.hashOf(p);
		}
		store.writePosts(channelId, posts);
		signers = new ArrayList<>();
		for (int i = 0; i < SIGNERS; i++) {
			signers.add(crypto.generateHybridSignatureKeyPair());
		}
		line("device\t" + Build.MANUFACTURER + " " + Build.MODEL
				+ "\tandroid " + Build.VERSION.RELEASE + " sdk "
				+ Build.VERSION.SDK_INT);
		line("posts\t" + POSTS + "\tsigners\t" + SIGNERS);
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteRecursively(root);
	}

	private void openDatabase() throws Exception {
		db = SqlCipherDatabaseForTests.open(root, dbKey);
		SettingsManager sm = SettingsManagerForTests.create(db);
		store = new ChannelStore(sm, readers, writers);
		reactionStore = new ChannelReactionStore(sm, readers, writers);
		ChannelSignatures sigs = signatures;
		ChannelPostValidator validator =
				new ChannelPostValidator(codec, sigs, chain);
		ChannelPullProtocol protocol = new ChannelPullProtocol(codec,
				pullCodec, new ChannelHmacChallenge(crypto),
				new ChannelContentKey(crypto), validator, sigs, crypto);
		DatabaseConfig blobConfig = new DatabaseConfig() {
			@Override
			public File getDatabaseDirectory() {
				return new File(root, "db");
			}

			@Override
			public File getDatabaseKeyDirectory() {
				return new File(root, "key");
			}

			@Nullable
			@Override
			public org.zerionproject.core.api.crypto.KeyStrengthener
			getKeyStrengthener() {
				return null;
			}
		};
		manager = new ChannelManagerImpl(crypto, Inert.of(EventBus.class),
				clock, codec, sigs, chain, store,
				new ChannelContentKey(crypto), validator, protocol,
				Inert.of(ChannelTransport.class),
				new ChannelBlobStore(blobConfig, sm, crypto), reactionStore,
				new ChannelSubscriberStore(sm, readers, writers),
				new ChannelCommentStore(sm, readers, writers),
				new ChannelDiscussionStore(sm),
				new ChannelApplicationStore(sm, readers, writers),
				new ChannelMyApplicationsStore(sm, readers, writers),
				new ChannelTombstoneStore(sm),
				new ChannelPostTombstoneStore(sm),
				new ChannelSelfAnnounceStore(sm),
				Inert.of(IdentityManager.class), Inert.of(TaskScheduler.class),
				Runnable::run);
		manager.readerFactory = readers;
		manager.writerFactory = writers;
		dispatch = ChannelManagerImpl.class.getDeclaredMethod(
				"handlePublisherRequest", byte[].class, byte[].class);
		dispatch.setAccessible(true);
		settings = sm;
	}

	private SettingsManager settings;

	private void restart() throws Exception {
		db.close();
		db = null;
		openDatabase();
	}

	@Test
	public void absentPostsLeaveNoStateAndLegitimateStateStaysBounded()
			throws Exception {
		try {
			measure();
		} catch (Throwable e) {
			line("failed\t" + e.getClass().getSimpleName() + "\t"
					+ e.getMessage());
			throw e;
		} finally {
			writeReport("f05-reaction-state.tsv");
		}
	}

	private void measure() throws Exception {
		restart();
		Map<String, String> before = snapshot();
		long fileBefore = dbFileBytes();
		long walBefore = walFileBytes();

		assertFalse(send(signers.get(0), POSTS + 1000L, "x"));
		assertEquals("one absent-post reaction left state", before,
				snapshot());
		line("absent_1\trefused\tstate_unchanged");

		List<Long> lat = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			long t = System.nanoTime();
			assertFalse(send(signers.get(0), POSTS + 1L + i, "y"));
			lat.add(System.nanoTime() - t);
		}
		assertEquals("100 absent-post reactions left state", before,
				snapshot());
		line("absent_100\trefused\tstate_unchanged\t" + stats(lat));

		lat.clear();
		long[] odd = {-1L, Long.MIN_VALUE, Long.MAX_VALUE, POSTS,
				Integer.MAX_VALUE};
		for (int i = 0; i < 1000; i++) {
			KeyPair s = signers.get(i % 10);
			long seq = i < odd.length ? odd[i]
					: POSTS + 1L + (random.nextInt() & 0x3fffffff);
			long t = System.nanoTime();
			assertFalse(send(s, seq, "z" + (i % 7)));
			lat.add(System.nanoTime() - t);
		}
		assertEquals("1000 adversarial absent-post reactions left state",
				before, snapshot());
		line("absent_1000_adversarial\trefused\tstate_unchanged\t"
				+ stats(lat));

		restart();
		assertEquals("state after restart", before, snapshot());
		line("absent_after_restart\tstate_unchanged\tdb_file_bytes_before\t"
				+ fileBefore + "\tafter\t" + dbFileBytes()
				+ "\twal_bytes_before\t" + walBefore + "\tafter\t"
				+ walFileBytes());

		growLegitimateState();
		floodThenLegitimate();
		writeAllowance();

		int stored = reactionStore.getReactions(channelId).size();
		restart();
		long t = System.nanoTime();
		List<ChannelReaction> afterRestart =
				reactionStore.getReactions(channelId);
		long readNs = System.nanoTime() - t;
		assertEquals("reactions survive a restart", stored,
				afterRestart.size());
		ChannelReaction first = firstOfAKnownSigner(afterRestart);
		KeyPair owner = signerOf(first.getSignerEd25519PubKey());
		long t2 = System.nanoTime();
		boolean replaced = send(owner, first.getPostSeqNum(), "r");
		long replaceNs = System.nanoTime() - t2;
		assertTrue("a signer can still replace its own reaction", replaced);
		assertEquals(stored, reactionStore.getReactions(channelId).size());
		line("restart\treactions\t" + afterRestart.size() + "\tread_ms\t"
				+ ms(readNs) + "\treplace_ok\t" + replaced + "\treplace_ms\t"
				+ ms(replaceNs) + "\trow_bytes\t" + rowBytes()
				+ "\tdb_file_bytes\t" + dbFileBytes());
	}

	private void growLegitimateState() throws Exception {
		long start = System.currentTimeMillis();
		int accepted = 0, sent = 0;
		List<Long> window = new ArrayList<>();
		String stopReason = "sends";
		KeyPair lastSigner = null;
		long lastSeq = -1;
		line("growth\tsent\taccepted\theld\trow_bytes\tdb_file_bytes"
				+ "\tpull_response_bytes\tlatency_window");
		outer:
		for (int seq = 0; seq < POSTS; seq++) {
			for (KeyPair s : signers) {
				if (sent >= GROWTH_SENDS) break outer;
				long t = System.nanoTime();
				boolean ok;
				try {
					ok = send(s, seq, "❤");
				} catch (Throwable e) {
					stopReason = "error " + e.getClass().getSimpleName()
							+ " " + e.getMessage();
					break outer;
				}
				window.add(System.nanoTime() - t);
				sent++;
				if (ok) {
					accepted++;
					lastSigner = s;
					lastSeq = seq;
				}
				if (sent % 50 == 0) {
					line("growth\t" + sent + "\t" + accepted + "\t"
							+ reactionStore.getReactions(channelId).size() + "\t"
							+ rowBytes() + "\t" + dbFileBytes() + "\t"
							+ pullResponseBytes() + "\t" + stats(window));
					window.clear();
				}
				if (System.currentTimeMillis() - start > GROWTH_TIME_LIMIT_MS) {
					stopReason = "time";
					break outer;
				}
			}
		}
		List<ChannelReaction> held = reactionStore.getReactions(channelId);
		boolean newestHeld = lastSigner != null
				&& holds(held, lastSigner, lastSeq);
		line("growth_end\tsent\t" + sent + "\taccepted\t" + accepted
				+ "\tstop\t" + stopReason + "\theld\t" + held.size()
				+ "\theld_bytes\t" + ChannelReactionPolicy.storedBytes(held)
				+ "\tnewest_held\t" + newestHeld + "\trow_bytes\t"
				+ rowBytes() + "\tdb_file_bytes\t" + dbFileBytes()
				+ "\tpull_response_bytes\t" + pullResponseBytes()
				+ "\tlast_window\t" + stats(window));
		assertTrue("growth stopped on " + stopReason,
				stopReason.equals("sends"));
		assertEquals("every valid reaction is taken in", sent, accepted);
		assertEquals(ANONYMOUS_REACTIONS, held.size());
		assertTrue(ChannelReactionPolicy.storedBytes(held) <= BUDGET_BYTES);
		assertTrue("the newest reaction is held", newestHeld);
	}

	private void floodThenLegitimate() throws Exception {
		List<KeyPair> flood = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			flood.add(crypto.generateHybridSignatureKeyPair());
		}
		int floodSent = 0;
		List<Long> lat = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			long t = System.nanoTime();
			assertTrue(send(flood.get(i % flood.size()), i % POSTS, "x"));
			lat.add(System.nanoTime() - t);
			floodSent++;
		}
		int legit = 0;
		for (int i = 0; i < 20; i++) {
			if (send(signers.get(i), i % POSTS, "+1")) legit++;
		}
		List<ChannelReaction> held = reactionStore.getReactions(channelId);
		int legitHeld = 0;
		for (int i = 0; i < 20; i++) {
			if (holds(held, signers.get(i), i % POSTS)) legitHeld++;
		}
		line("flood_then_legitimate\tflood_sent\t" + floodSent
				+ "\tlegit_accepted\t" + legit + "\tlegit_held\t" + legitHeld
				+ "\theld\t" + held.size() + "\tflood_" + stats(lat));
		assertEquals(20, legit);
		assertEquals(20, legitHeld);
		assertTrue(held.size() <= BUDGET_REACTIONS);
	}

	private void writeAllowance() throws Exception {
		clock.stopped = true;
		int accepted = 0;
		boolean refused = false;
		List<Long> refusedLat = new ArrayList<>();
		for (int i = 0; i < 400 && !refused; i++) {
			KeyPair k = signers.get(i % SIGNERS);
			if (send(k, (i / SIGNERS) % POSTS, i % 2 == 0 ? "a" : "b")) {
				accepted++;
			} else {
				refused = true;
			}
		}
		String row = settings.getSettings(REACTIONS_NS)
				.get(ChannelStore.hex(channelId));
		for (int i = 0; i < 20; i++) {
			long t = System.nanoTime();
			assertFalse(send(signers.get(i), 1, "c"));
			refusedLat.add(System.nanoTime() - t);
		}
		boolean unchanged = row.equals(settings.getSettings(REACTIONS_NS)
				.get(ChannelStore.hex(channelId)));
		clock.now += HOUR;
		boolean refilled = send(signers.get(0), 1, "d");
		clock.stopped = false;
		line("write_allowance\tburst_accepted\t" + accepted
				+ "\tthen_refused\t" + refused + "\trefusal_writes_nothing\t"
				+ unchanged + "\trefilled_after_an_hour\t" + refilled
				+ "\trefused_" + stats(refusedLat));
		assertTrue(refused);
		assertTrue(unchanged);
		assertTrue(refilled);
	}

	private static boolean holds(List<ChannelReaction> rs, KeyPair signer,
			long seq) {
		byte[] ed = ((HybridSignaturePublicKey) signer.getPublic())
				.getEd25519PublicKey();
		for (ChannelReaction r : rs) {
			if (r.getPostSeqNum() == seq
					&& Arrays.equals(r.getSignerEd25519PubKey(), ed)) {
				return true;
			}
		}
		return false;
	}

	private boolean send(KeyPair signer, long seq, String emoji)
			throws Exception {
		if (!clock.stopped) clock.now += HOUR;
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) signer.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) signer.getPublic();
		long ts = clock.now / HOUR * HOUR;
		byte[] input = codec.reactionSignedInput(channelId, seq, emoji, ts);
		byte[] sig = signatures.signUserReaction(input,
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		byte[] request = pullCodec.encodeReactionRequest(channelId, seq,
				emoji, ts, pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(),
				sig, null, null);
		byte[] ack = (byte[]) dispatch.invoke(manager, channelId, request);
		return ack != null && ack.length > 0
				&& pullCodec.decodeReactionAck(ack);
	}

	private ChannelReaction firstOfAKnownSigner(List<ChannelReaction> all) {
		for (ChannelReaction r : all) {
			for (KeyPair s : signers) {
				if (Arrays.equals(r.getSignerEd25519PubKey(),
						((HybridSignaturePublicKey) s.getPublic())
								.getEd25519PublicKey())) {
					return r;
				}
			}
		}
		throw new AssertionError("no reaction of a known signer survived");
	}

	private KeyPair signerOf(byte[] ed) {
		for (KeyPair s : signers) {
			if (Arrays.equals(ed, ((HybridSignaturePublicKey) s.getPublic())
					.getEd25519PublicKey())) {
				return s;
			}
		}
		throw new AssertionError("unknown signer");
	}

	private Map<String, String> snapshot() throws Exception {
		Map<String, String> out = new HashMap<>();
		for (String ns : new String[] {REACTIONS_NS,
				"zerion-channels-posts", "zerion-channels-state",
				"zerion-channels-index", "zerion-channels-priv",
				"zerion-channels-unread", "zerion-channels-mirror"}) {
			Settings s = settings.getSettings(ns);
			for (String key : s.keySet()) {
				out.put(ns + "/" + key, s.get(key));
			}
		}
		return out;
	}

	private long rowBytes() throws Exception {
		String v = settings.getSettings(REACTIONS_NS)
				.get(ChannelStore.hex(channelId));
		return v == null ? 0 : v.length();
	}

	private long dbFileBytes() {
		return SqlCipherDatabaseForTests.databaseFile(root).length();
	}

	private long walFileBytes() {
		File db = SqlCipherDatabaseForTests.databaseFile(root);
		return new File(db.getPath() + "-wal").length();
	}

	private long pullResponseBytes() throws Exception {
		List<ChannelPost> batch = posts.subList(
				Math.max(0, posts.size() - 100), posts.size());
		byte[] bytes = pullCodec.encodePullResponse(new BdfDictionary(),
				batch, null, Collections.<String>emptyList(),
				reactionStore.getReactions(channelId),
				Collections.<ChannelComment>emptyList());
		return bytes.length;
	}

	private ChannelState state() {
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) publisher.getPublic();
		return new ChannelState(channelId, salt, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), "name", "description", null, HOUR,
				true, null, "", 1L, true, POSTS - 1L, null, null,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 10L, null,
				ChannelState.NO_PINNED_POST, false,
				Collections.<ChannelDelegationCert>emptyList());
	}

	private ChannelPost post(long seq, byte[] prev) throws Exception {
		long ts = HOUR * (seq + 2);
		char[] body = new char[480];
		Arrays.fill(body, 'p');
		String text = new String(body);
		List<ChannelPost.ChannelAttachment> none = Collections.emptyList();
		byte[] input = codec.postSignedInput(channelId, seq, prev, ts, text,
				codec.attachmentsHash(none), 0L);
		byte[] sig = signatures.signPost(input,
				(HybridSignaturePrivateKey) publisher.getPrivate());
		return new ChannelPost(channelId, seq, prev, ts, text, none, 0L, sig,
				false);
	}

	private static String stats(List<Long> ns) {
		if (ns.isEmpty()) return "n=0";
		List<Long> s = new ArrayList<>(ns);
		Collections.sort(s);
		return "n=" + s.size() + " median_ms=" + ms(s.get(s.size() / 2))
				+ " p95_ms=" + ms(s.get((int) Math.min(s.size() - 1,
				Math.floor(s.size() * 0.95)))) + " max_ms="
				+ ms(s.get(s.size() - 1));
	}

	private static String ms(long ns) {
		return String.format(java.util.Locale.ROOT, "%.1f", ns / 1e6);
	}

	private void line(String s) {
		report.append(s).append('\n');
	}

	private void writeReport(String name) throws Exception {
		File dir = new File(context.getFilesDir(), "device-test-output");
		if (!dir.isDirectory() && !dir.mkdirs()) throw new AssertionError();
		try (FileOutputStream out = new FileOutputStream(new File(dir,
				name))) {
			out.write(report.toString().getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void deleteRecursively(File f) {
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) deleteRecursively(c);
		}
		f.delete();
	}

	private static final class MutableClock
			implements org.zerionproject.core.api.system.Clock {
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
}
