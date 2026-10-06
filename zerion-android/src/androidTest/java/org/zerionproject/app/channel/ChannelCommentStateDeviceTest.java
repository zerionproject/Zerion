package org.zerionproject.app.channel;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;

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
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
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
public class ChannelCommentStateDeviceTest {

	private static final long HOUR = 3_600_000L;
	private static final int POSTS = 60;
	private static final int AUTHORS = 64;
	private static final String COMMENTS_NS = "zerion-channels-comments";
	private static final long GROWTH_TIME_LIMIT_MS = 10L * 60L * 1000L;
	private static final int GROWTH_LIMIT = 1000;
	private static final int BUDGET_COMMENTS = 256;
	private static final int BUDGET_PER_POST = 64;
	private static final int BUDGET_PER_AUTHOR = 32;
	private static final long BUDGET_BYTES = 1536L * 1024L;

	private final SecureRandom random = new SecureRandom();
	private final StringBuilder report = new StringBuilder();
	private final MutableClock clock = new MutableClock();

	private boolean enforce;
	private Context context;
	private File root;
	private SecretKey dbKey;
	private DatabaseComponent db;
	private SettingsManager settings;
	private CryptoComponent crypto;
	private BdfReaderFactory readers;
	private BdfWriterFactory writers;
	private ChannelCodec codec;
	private ChannelSignatures signatures;
	private ChannelChainVerifier chain;
	private ChannelPullCodec pullCodec;
	private ChannelCommentStore commentStore;
	private ChannelStore store;
	private ChannelManagerImpl manager;
	private Method dispatch;
	private KeyPair publisher;
	private byte[] channelId;
	private byte[] salt;
	private List<ChannelPost> posts;
	private List<KeyPair> authors;

	@Before
	public void setUp() throws Exception {
		Bundle args = InstrumentationRegistry.getArguments();
		enforce = !"characterize".equals(args.getString("mode"));
		context = InstrumentationRegistry.getInstrumentation()
				.getTargetContext();
		root = new File(context.getCacheDir(),
				"comments-" + System.nanoTime());
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
		new ChannelDiscussionStore(settings).setEnabled(channelId, true);
		posts = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < POSTS; seq++) {
			ChannelPost p = post(seq, prev);
			posts.add(p);
			prev = chain.hashOf(p);
		}
		store.writePosts(channelId, posts);
		authors = new ArrayList<>();
		for (int i = 0; i < AUTHORS; i++) {
			authors.add(crypto.generateHybridSignatureKeyPair());
		}
		line("device\t" + Build.MANUFACTURER + " " + Build.MODEL
				+ "\tandroid " + Build.VERSION.RELEASE + " sdk "
				+ Build.VERSION.SDK_INT + "\tmode\t"
				+ (enforce ? "enforce" : "characterize"));
		line("posts\t" + POSTS + "\tauthors\t" + AUTHORS);
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteRecursively(root);
	}

	private void openDatabase() throws Exception {
		db = SqlCipherDatabaseForTests.open(root, dbKey);
		settings = SettingsManagerForTests.create(db);
		store = new ChannelStore(settings, readers, writers);
		commentStore = new ChannelCommentStore(settings, readers, writers);
		ChannelPostValidator validator =
				new ChannelPostValidator(codec, signatures, chain);
		ChannelPullProtocol protocol = new ChannelPullProtocol(codec,
				pullCodec, new ChannelHmacChallenge(crypto),
				new ChannelContentKey(crypto), validator, signatures, crypto);
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
				clock, codec, signatures, chain, store,
				new ChannelContentKey(crypto), validator, protocol,
				Inert.of(ChannelTransport.class),
				new ChannelBlobStore(blobConfig, settings, crypto),
				new ChannelReactionStore(settings, readers, writers),
				new ChannelSubscriberStore(settings, readers, writers),
				commentStore, new ChannelDiscussionStore(settings),
				new ChannelApplicationStore(settings, readers, writers),
				new ChannelMyApplicationsStore(settings, readers, writers),
				new ChannelTombstoneStore(settings),
				new ChannelPostTombstoneStore(settings),
				new ChannelSelfAnnounceStore(settings),
				Inert.of(IdentityManager.class), Inert.of(TaskScheduler.class),
				Runnable::run);
		manager.readerFactory = readers;
		manager.writerFactory = writers;
		dispatch = ChannelManagerImpl.class.getDeclaredMethod(
				"handlePublisherRequest", byte[].class, byte[].class);
		dispatch.setAccessible(true);
	}

	private void restart() throws Exception {
		db.close();
		db = null;
		openDatabase();
	}

	@Test
	public void commentStateOnThisBuild() throws Exception {
		try {
			measure();
		} catch (Throwable e) {
			line("failed\t" + e.getClass().getSimpleName() + "\t"
					+ e.getMessage());
			throw e;
		} finally {
			writeReport("comment-state.tsv");
		}
	}

	private void measure() throws Exception {
		restart();
		Map<String, String> before = snapshot();
		long fileBefore = dbFileBytes();
		long walBefore = walFileBytes();

		boolean one = send(authors.get(0), POSTS + 1000L, "x", "A");
		line("absent_1\taccepted\t" + one + "\tstored\t" + stored());
		List<Long> lat = new ArrayList<>();
		int acceptedAbsent = one ? 1 : 0;
		for (int i = 0; i < 100; i++) {
			long t = System.nanoTime();
			if (send(authors.get(i % AUTHORS), POSTS + 1L + i, "y" + i, "A")) {
				acceptedAbsent++;
			}
			lat.add(System.nanoTime() - t);
		}
		line("absent_100\taccepted\t" + acceptedAbsent + "\tstored\t"
				+ stored() + "\t" + stats(lat));
		lat.clear();
		long[] odd = {-1L, Long.MIN_VALUE, Long.MAX_VALUE, POSTS,
				Integer.MAX_VALUE};
		long guard = System.currentTimeMillis();
		int adversarialSent = 0;
		for (int i = 0; i < 1000; i++) {
			if (System.currentTimeMillis() - guard > 180_000L) break;
			adversarialSent++;
			long seq = i < odd.length ? odd[i]
					: POSTS + 1L + (random.nextInt() & 0x3fffffff);
			long t = System.nanoTime();
			if (send(authors.get(i % 10), seq, "z" + i, "A")) acceptedAbsent++;
			lat.add(System.nanoTime() - t);
		}
		line("absent_1000_adversarial\tsent\t" + adversarialSent
				+ "\taccepted_total\t" + acceptedAbsent
				+ "\tstored\t" + stored() + "\trow_bytes\t" + rowBytes()
				+ "\t" + stats(lat));
		restart();
		boolean unchanged = before.equals(snapshot());
		line("absent_after_restart\tstate_unchanged\t" + unchanged
				+ "\tdb_file_bytes_before\t" + fileBefore + "\tafter\t"
				+ dbFileBytes() + "\twal_bytes_before\t" + walBefore
				+ "\tafter\t" + walFileBytes());
		if (enforce) {
			assertEquals("absent-target comments created state", 0,
					acceptedAbsent);
			assertEquals(1000, adversarialSent);
			assertTrue("absent-target comments left state", unchanged);
		}
		commentStore.removeAll(channelId);

		char[] longName = new char[60_000];
		Arrays.fill(longName, 'n');
		boolean nameAccepted = send(authors.get(1), 2, "hello",
				new String(longName));
		String decodable;
		try {
			pullCodec.decodePullResponse(pull(), channelId);
			decodable = "true";
		} catch (Exception e) {
			decodable = "false " + e.getClass().getSimpleName();
		}
		line("oversized_name_60000\taccepted\t" + nameAccepted
				+ "\tpull_decodable\t" + decodable);
		if (enforce) {
			assertFalse("an oversized name was accepted", nameAccepted);
			assertEquals("true", decodable);
		}
		commentStore.removeAll(channelId);

		for (int i = 0; i < 300; i++) send(authors.get(2), i % POSTS, "a" + i, "A");
		int byAuthor = 0;
		for (ChannelComment c : commentStore.getComments(channelId)) {
			if (Arrays.equals(c.getAuthorEd25519PubKey(),
					((HybridSignaturePublicKey) authors.get(2).getPublic())
							.getEd25519PublicKey())) {
				byAuthor++;
			}
		}
		boolean lastKept = containsBody("a299");
		line("per_author_300\theld\t" + byAuthor + "\tnewest_held\t"
				+ lastKept + "\trow_bytes\t" + rowBytes());
		if (enforce) {
			assertTrue(byAuthor <= BUDGET_PER_AUTHOR);
			assertTrue("an author's newest comment is held", lastKept);
		}
		commentStore.removeAll(channelId);

		for (int i = 0; i < 300; i++) {
			send(authors.get(3 + i % 30), 5, "t" + i, "T");
		}
		int onPost = commentStore.getComments(channelId).size();
		boolean threadNewest = containsBody("t299");
		line("per_post_300\theld\t" + onPost + "\tnewest_held\t"
				+ threadNewest + "\trow_bytes\t" + rowBytes());
		if (enforce) {
			assertTrue(onPost <= BUDGET_PER_POST);
			assertTrue(threadNewest);
		}
		commentStore.removeAll(channelId);

		grow();

		int stored = stored();
		restart();
		long t = System.nanoTime();
		List<ChannelComment> after = commentStore.getComments(channelId);
		long readNs = System.nanoTime() - t;
		String restartDecodable;
		try {
			pullCodec.decodePullResponse(pull(), channelId);
			restartDecodable = "true";
		} catch (Exception e) {
			restartDecodable = "false " + e.getClass().getSimpleName();
		}
		long t2 = System.nanoTime();
		boolean newAfterRestart = send(authors.get(0), 1, "after restart",
				"A");
		long newNs = System.nanoTime() - t2;
		line("restart\tcomments\t" + after.size() + "\tsame_as_before\t"
				+ (after.size() == stored) + "\tread_ms\t" + ms(readNs)
				+ "\tpull_decodable\t" + restartDecodable
				+ "\tnew_comment_accepted\t" + newAfterRestart
				+ "\tnew_comment_ms\t" + ms(newNs) + "\trow_bytes\t"
				+ rowBytes() + "\tdb_file_bytes\t" + dbFileBytes());
		if (enforce) {
			assertEquals(stored, after.size());
			assertTrue("a new comment is possible at the budget",
					newAfterRestart);
			assertTrue(stored() <= BUDGET_COMMENTS);
			assertTrue(storedBytes() <= BUDGET_BYTES);
		}
	}

	private void grow() throws Exception {
		char[] b = new char[ChannelConstants.MAX_COMMENT_BODY_CHARS - 4];
		Arrays.fill(b, '中');
		String body = new String(b);
		char[] n = new char[64];
		Arrays.fill(n, '中');
		String name = new String(n);
		long start = System.currentTimeMillis();
		int accepted = 0, sent = 0;
		List<Long> window = new ArrayList<>();
		String stopReason = "limit";
		line("growth\tsent\taccepted\theld\trow_bytes\tdb_file_bytes"
				+ "\tpull_response_bytes\tlatency_window");
		while (sent < GROWTH_LIMIT) {
			long t = System.nanoTime();
			boolean ok;
			try {
				ok = send(authors.get(sent % AUTHORS), sent % POSTS,
						body + String.format(java.util.Locale.ROOT, "%04d",
								sent), name);
			} catch (Throwable e) {
				stopReason = "error " + e.getClass().getSimpleName() + " "
						+ e.getMessage();
				break;
			}
			window.add(System.nanoTime() - t);
			sent++;
			if (ok) accepted++;
			if (sent % 50 == 0) {
				line("growth\t" + sent + "\t" + accepted + "\t" + stored()
						+ "\t" + rowBytes() + "\t" + dbFileBytes() + "\t"
						+ pullResponseBytes() + "\t" + stats(window));
				window.clear();
			}
			if (System.currentTimeMillis() - start > GROWTH_TIME_LIMIT_MS) {
				stopReason = "time";
				break;
			}
		}
		line("growth_end\tsent\t" + sent + "\taccepted\t" + accepted
				+ "\tstop\t" + stopReason + "\theld\t" + stored()
				+ "\theld_bytes\t" + storedBytes() + "\trow_bytes\t"
				+ rowBytes() + "\tdb_file_bytes\t" + dbFileBytes()
				+ "\tpull_response_bytes\t" + pullResponseBytes()
				+ "\tlast_window\t" + stats(window));
		assertFalse("growth stopped on an error: " + stopReason,
				stopReason.startsWith("error"));
		if (enforce) {
			assertEquals("every valid comment was accepted", sent, accepted);
			assertTrue(stored() <= BUDGET_COMMENTS);
			assertTrue(storedBytes() <= BUDGET_BYTES);
		}
	}

	private boolean send(KeyPair author, long seq, String body, String name)
			throws Exception {
		clock.advance(HOUR);
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) author.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) author.getPublic();
		long ts = clock.currentTimeMillis() / HOUR * HOUR;
		long id = random.nextLong();
		byte[] input = codec.commentSignedInput(channelId, seq, id, body,
				name, ts);
		byte[] sig = signatures.signUserComment(input,
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		byte[] request = pullCodec.encodeCommentRequest(channelId, seq, id,
				body, name, ts, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), sig, null, null);
		byte[] ack = (byte[]) dispatch.invoke(manager, channelId, request);
		return ack != null && ack.length > 0
				&& pullCodec.decodeCommentAck(ack);
	}

	private byte[] pull() throws Exception {
		byte[] request = pullCodec.encodePullRequest(channelId, -1L, null,
				null);
		return (byte[]) dispatch.invoke(manager, channelId, request);
	}

	private boolean containsBody(String body) throws Exception {
		for (ChannelComment c : commentStore.getComments(channelId)) {
			if (c.getBody().equals(body)) return true;
		}
		return false;
	}

	private int stored() throws Exception {
		return commentStore.getComments(channelId).size();
	}

	private long storedBytes() throws Exception {
		long total = 0;
		for (ChannelComment c : commentStore.getComments(channelId)) {
			total += 24L + c.getBody().getBytes(StandardCharsets.UTF_8).length
					+ c.getAuthorDisplayName()
					.getBytes(StandardCharsets.UTF_8).length
					+ c.getAuthorEd25519PubKey().length
					+ c.getAuthorMlDsaPubKey().length
					+ c.getSignature().length;
		}
		return total;
	}

	private Map<String, String> snapshot() throws Exception {
		Map<String, String> out = new HashMap<>();
		for (String ns : new String[] {COMMENTS_NS,
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
		String v = settings.getSettings(COMMENTS_NS)
				.get(ChannelStore.hex(channelId));
		return v == null ? 0 : v.length();
	}

	private long dbFileBytes() {
		return SqlCipherDatabaseForTests.databaseFile(root).length();
	}

	private long walFileBytes() {
		File f = SqlCipherDatabaseForTests.databaseFile(root);
		return new File(f.getPath() + "-wal").length();
	}

	private long pullResponseBytes() throws Exception {
		List<ChannelPost> batch = posts.subList(
				Math.max(0, posts.size() - 100), posts.size());
		byte[] bytes = pullCodec.encodePullResponse(
				new org.zerionproject.core.api.data.BdfDictionary(), batch,
				null, Collections.<String>emptyList(),
				Collections.<ChannelReaction>emptyList(),
				commentStore.getComments(channelId));
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
}
