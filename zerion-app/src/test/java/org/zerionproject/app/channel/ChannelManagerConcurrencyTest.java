package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.AttachmentSpec;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.app.channel.ChannelTestNode.MemorySettings;
import org.zerionproject.app.channel.ChannelTestNode.MutableClock;
import org.zerionproject.app.channel.ChannelTestNode.TestChannel;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.TaskScheduler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.channel.ChannelTestNode.HOUR;
import static org.zerionproject.app.channel.ChannelTestNode.PUBLISHER_ONION;
import static org.zerionproject.app.channel.ChannelTestNode.inert;

public class ChannelManagerConcurrencyTest {

	private static final int POSTS = 4;
	private static final long WAIT_MS = 10_000L;

	private final Random random = new Random(4127);
	private final MutableClock clock = new MutableClock();
	private final MemorySettings settings = new MemorySettings();
	private final List<Scheduled> scheduled =
			Collections.synchronizedList(new ArrayList<>());
	private final AtomicBoolean rejectIo = new AtomicBoolean();
	private final AtomicReference<ChannelTransport> transport =
			new AtomicReference<>(ChannelTestNode.noTransport());

	private CryptoComponent crypto;
	private ChannelCodec codec;
	private ChannelSignatures signatures;
	private ChannelPullCodec pullCodec;
	private ChannelContentKey contentKey;
	private RecordingStore store;
	private ChannelReactionStore reactionStore;
	private ChannelCommentStore commentStore;
	private ChannelSubscriberStore subscriberStore;
	private ChannelPostTombstoneStore postTombstones;
	private ChannelManagerImpl manager;
	private TestChannel channel;
	private File root;

	private static final class Scheduled {

		final Runnable task;
		final Executor executor;
		final long delayMs;

		Scheduled(Runnable task, Executor executor, long delayMs) {
			this.task = task;
			this.executor = executor;
			this.delayMs = delayMs;
		}

		void fire() {
			try {
				executor.execute(task);
			} catch (RuntimeException swallowedLikeAScheduledFuture) {
			}
		}
	}

	static final class RecordingStore extends ChannelStore {

		final List<byte[]> handedOut =
				Collections.synchronizedList(new ArrayList<>());

		RecordingStore(SettingsManager settings, BdfReaderFactory reader,
				BdfWriterFactory writer) {
			super(settings, reader, writer);
		}

		@Override
		byte[] getPublisherPrivKey(byte[] channelId) throws DbException {
			byte[] key = super.getPublisherPrivKey(channelId);
			if (key != null) handedOut.add(key);
			return key;
		}
	}

	private interface Step {
		void run() throws Exception;
	}

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		ChannelCodecTestComponent bdf =
				DaggerChannelCodecTestComponent.create();
		codec = new ChannelCodec(crypto);
		signatures = new ChannelSignatures(crypto);
		ChannelChainVerifier chain = new ChannelChainVerifier(codec);
		pullCodec = new ChannelPullCodec(bdf.getBdfReaderFactory(),
				bdf.getBdfWriterFactory());
		contentKey = new ChannelContentKey(crypto);
		root = new File(System.getProperty("java.io.tmpdir"),
				"zt-concurrency-" + System.nanoTime());
		store = new RecordingStore(settings, bdf.getBdfReaderFactory(),
				bdf.getBdfWriterFactory());
		reactionStore = new ChannelReactionStore(settings,
				bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
		commentStore = new ChannelCommentStore(settings,
				bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
		subscriberStore = new ChannelSubscriberStore(settings,
				bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory());
		postTombstones = new ChannelPostTombstoneStore(settings);
		ChannelPostValidator validator =
				new ChannelPostValidator(codec, signatures, chain);
		ChannelPullProtocol protocol = new ChannelPullProtocol(codec,
				pullCodec, new ChannelHmacChallenge(crypto), contentKey,
				validator, signatures, crypto);
		ChannelTransport delegating = new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] id,
					@Nullable String onionPrivateKey,
					ChannelRequestHandler handler) throws IOException {
				return transport.get().bindServer(id, onionPrivateKey,
						handler);
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				return transport.get().requestFromOnion(onion, request);
			}

			@Override
			public boolean isReachable(String onion) {
				return transport.get().isReachable(onion);
			}
		};
		TaskScheduler scheduler = new TaskScheduler() {
			@Override
			public Cancellable schedule(Runnable task, Executor executor,
					long delay, TimeUnit unit) {
				scheduled.add(new Scheduled(task, executor,
						unit.toMillis(delay)));
				return () -> {
				};
			}

			@Override
			public Cancellable scheduleWithFixedDelay(Runnable task,
					Executor executor, long delay, long interval,
					TimeUnit unit) {
				return () -> {
				};
			}
		};
		Executor io = r -> {
			if (rejectIo.get()) throw new RejectedExecutionException();
			r.run();
		};
		manager = new ChannelManagerImpl(crypto, inert(EventBus.class),
				clock, codec, signatures, chain, store, contentKey,
				validator, protocol, delegating,
				new ChannelBlobStore(ChannelTestNode.config(root), settings,
						crypto),
				reactionStore, subscriberStore, commentStore,
				new ChannelDiscussionStore(settings),
				new ChannelApplicationStore(settings,
						bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory()),
				new ChannelMyApplicationsStore(settings,
						bdf.getBdfReaderFactory(), bdf.getBdfWriterFactory()),
				new ChannelTombstoneStore(settings), postTombstones,
				new ChannelSelfAnnounceStore(settings),
				ChannelTestNode.identity(
						crypto.generateHybridSignatureKeyPair()),
				scheduler, io);
		manager.readerFactory = bdf.getBdfReaderFactory();
		manager.writerFactory = bdf.getBdfWriterFactory();
		channel = new TestChannel(crypto, random);
	}

	@After
	public void tearDown() {
		settings.onRead(null);
		delete(root);
	}

	@Test
	public void aCommentWrittenHereDoesNotEraseOneArrivingAtTheSameTime()
			throws Exception {
		seedPublisher(true, null);
		KeyPair member = crypto.generateHybridSignatureKeyPair();
		ChannelComment theirs = comment(member, "from a member");

		raceAt("zerion-channels-comments", 1,
				() -> manager.postComment(channel.channelId, 1L, "mine"),
				() -> assertTrue(sendComment(theirs)));

		List<String> bodies = new ArrayList<>();
		for (ChannelComment c : commentStore.getComments(channel.channelId)) {
			bodies.add(c.getBody());
		}
		assertTrue(bodies.contains("mine"));
		assertTrue(bodies.contains("from a member"));
	}

	@Test
	public void aReactionGivenHereDoesNotEraseOneArrivingAtTheSameTime()
			throws Exception {
		seedPublisher(true, null);
		KeyPair member = crypto.generateHybridSignatureKeyPair();

		raceAt("zerion-channels-reactions", 2,
				() -> manager.reactToPost(channel.channelId, 1L, "a"),
				() -> assertTrue(sendReaction(member, 2L, "b")));

		Set<String> emojis = new java.util.HashSet<>();
		for (ChannelReaction r
				: reactionStore.getReactions(channel.channelId)) {
			emojis.add(r.getEmoji());
		}
		assertEquals(new java.util.HashSet<>(
				java.util.Arrays.asList("a", "b")), emojis);
	}

	@Test
	public void anAnnouncementMadeHereDoesNotEraseOneArrivingAtTheSameTime()
			throws Exception {
		seedPublisher(true, null);
		KeyPair member = crypto.generateHybridSignatureKeyPair();

		raceAt("zerion-channels-subscribers", 1,
				() -> manager.announceMyself(channel.channelId, "Owner"),
				() -> assertTrue(sendAnnouncement(member, "Member")));

		Set<String> names = new java.util.HashSet<>();
		for (ChannelSubscriber sub
				: subscriberStore.getSubscribers(channel.channelId)) {
			names.add(sub.getDisplayName());
		}
		assertEquals(new java.util.HashSet<>(
				java.util.Arrays.asList("Owner", "Member")), names);
	}

	@Test
	public void showingPostsDoesNotEraseADeletionMadeAtTheSameTime()
			throws Exception {
		seedPublisher(true, null);
		manager.publishPost(channel.channelId,
				ChannelConstants.TOMBSTONE_PREFIX
						+ ChannelStore.hex(channel.channelId) + ":1:D", 0L);

		raceAt("zerion-channels-post-tombstones", 2,
				() -> manager.getRecentPosts(channel.channelId, 50L),
				() -> manager.deletePost(channel.channelId, 2L));

		Set<Long> deleted = postTombstones.get(channel.channelId);
		assertTrue(deleted.contains(1L));
		assertTrue(deleted.contains(2L));
	}

	@Test
	public void aPhotoPostedDuringAKeyRotationUsesTheKeyOfItsPost()
			throws Exception {
		byte[] oldKey = contentKey.generateContentKey();
		seedPublisher(false, oldKey);

		raceAt("zerion-channels-state", 1,
				() -> manager.publishPostWithAttachments(channel.channelId,
						"a photo", 0L, Collections.singletonList(
								new AttachmentSpec("image/jpeg",
										new byte[] {1, 2, 3}, null))),
				() -> manager.rotateJoinCapability(channel.channelId));

		ChannelState after = store.getChannel(channel.channelId);
		assertNotNull(after);
		byte[] newKey = after.getContentKey();
		assertNotNull(newKey);
		ChannelPost post = store.posts().getLatestPosts(
				channel.channelId, 1).get(0);
		assertEquals(1, post.getAttachments().size());
		byte[] bodyKey = null;
		for (byte[] k : java.util.Arrays.asList(oldKey, newKey)) {
			try {
				contentKey.decryptBodyOf(k, post,
						Base64.getDecoder().decode(post.getBody()));
				bodyKey = k;
			} catch (java.security.GeneralSecurityException ignored) {
			}
		}
		assertNotNull("the post body opens with one of the keys", bodyKey);
		contentKey.unwrapContentKey(bodyKey, channel.channelId,
				post.getAttachments().get(0).getPerAttachmentKey());
	}

	@Test
	public void applyingToJoinDoesNotHoldTheChannelDuringTheRequest()
			throws Exception {
		seedSubscriber();
		CountDownLatch inFlight = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean lockedDuringRequest = new AtomicBoolean();
		ReentrantLock lock = channelLock();
		transport.set(new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] id,
					@Nullable String onionPrivateKey,
					ChannelRequestHandler handler) throws IOException {
				throw new IOException();
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				if (lock.isLocked()) lockedDuringRequest.set(true);
				if (ChannelConstants.WIRE_TYPE_CONFIRM_ONION.equals(
						pullCodec.peekType(request))) {
					return confirmation(onion);
				}
				inFlight.countDown();
				try {
					release.await(WAIT_MS, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					throw new IOException(e);
				}
				return pullCodec.encodeApplyAck(true);
			}

			@Override
			public boolean isReachable(String onion) {
				return true;
			}
		});
		AtomicReference<Throwable> failed = new AtomicReference<>();
		Thread applicant = new Thread(() -> {
			try {
				manager.applyToJoin(channel.channelId, "Applicant");
			} catch (Throwable t) {
				failed.set(t);
			}
		});
		applicant.start();
		assertTrue(inFlight.await(WAIT_MS, TimeUnit.MILLISECONDS));
		boolean locked = lockedDuringRequest.get();
		release.countDown();
		applicant.join(WAIT_MS);
		if (failed.get() != null) throw new AssertionError(failed.get());
		assertFalse("the channel lock is free during the request", locked);
	}

	@Test
	public void everyHandedOutPublisherKeyIsWipedAfterUse()
			throws Exception {
		seedPublisher(true, null);
		KeyPair delegatee = crypto.generateHybridSignatureKeyPair();
		HybridSignaturePublicKey dPub =
				(HybridSignaturePublicKey) delegatee.getPublic();

		manager.publishPost(channel.channelId, "hello", 0L);
		manager.postComment(channel.channelId, 1L, "a comment");
		manager.reactToPost(channel.channelId, 1L, "a");
		manager.announceMyself(channel.channelId, "Owner");
		manager.delegatePublisher(channel.channelId,
				dPub.getEd25519PublicKey(), dPub.getMlDsaPublicKey(),
				clock.currentTimeMillis() / HOUR * HOUR + 24 * HOUR);
		byte[] served = handle(pullCodec.encodePullRequest(
				channel.channelId, -1L, null, null));
		assertTrue(served.length > 0);
		assertEquals(channel.keys.getPublic().getEncoded().length,
				manager.getMyChannelPublicKey(channel.channelId).length);
		manager.deleteChannel(channel.channelId);

		assertTrue(store.handedOut.size() >= 8);
		for (byte[] key : store.handedOut) {
			for (byte b : key) {
				if (b != 0) fail("a publisher key was left in memory");
			}
		}
	}

	@Test
	public void aRefreshTheExecutorRejectedIsTriedAgain() throws Exception {
		call("scheduleNextRefresh", new Class<?>[] {long.class}, 3_000L);
		assertEquals(1, scheduled.size());

		rejectIo.set(true);
		scheduled.get(0).fire();

		assertEquals("a retry is scheduled", 2, scheduled.size());
		long retry = scheduled.get(1).delayMs;
		assertTrue(retry > 0L && retry <= 60_000L);

		rejectIo.set(false);
		scheduled.get(1).fire();

		assertEquals("the chain goes on", 3, scheduled.size());
	}

	@Test
	public void aRefreshChainThatWentQuietIsRestartedOnce()
			throws Exception {
		call("scheduleNextRefresh", new Class<?>[] {long.class}, 3_000L);
		Scheduled lost = scheduled.get(0);
		clock.advance(3_000L + 4L * 60_000L);

		manager.eventOccurred(new Event() {
		});
		manager.eventOccurred(new Event() {
		});

		assertEquals("one new tick is scheduled", 2, scheduled.size());
		scheduled.get(1).fire();
		assertEquals(3, scheduled.size());
		lost.fire();
		assertEquals("the lost tick does not start a second chain", 3,
				scheduled.size());
	}

	private void raceAt(String namespace, int pauseAtRead, Step first,
			Step second) throws Exception {
		Thread firstThread = Thread.currentThread();
		AtomicInteger reads = new AtomicInteger();
		CountDownLatch secondDone = new CountDownLatch(1);
		AtomicReference<Throwable> secondFailed = new AtomicReference<>();
		ReentrantLock lock = channelLock();
		settings.onRead(ns -> {
			if (Thread.currentThread() != firstThread
					|| !ns.equals(namespace)
					|| reads.incrementAndGet() != pauseAtRead) {
				return;
			}
			Thread t = new Thread(() -> {
				try {
					second.run();
				} catch (Throwable e) {
					secondFailed.set(e);
				} finally {
					secondDone.countDown();
				}
			});
			t.start();
			long end = System.currentTimeMillis() + WAIT_MS;
			while (secondDone.getCount() > 0 && !lock.hasQueuedThread(t)
					&& System.currentTimeMillis() < end) {
				try {
					Thread.sleep(2L);
				} catch (InterruptedException e) {
					return;
				}
			}
		});
		try {
			first.run();
		} finally {
			settings.onRead(null);
		}
		assertTrue("the competing write ran", reads.get() >= pauseAtRead);
		assertTrue(secondDone.await(WAIT_MS, TimeUnit.MILLISECONDS));
		if (secondFailed.get() != null) {
			throw new AssertionError(secondFailed.get());
		}
	}

	private ReentrantLock channelLock() throws Exception {
		return (ReentrantLock) call("lockFor",
				new Class<?>[] {byte[].class}, (Object) channel.channelId);
	}

	private Object call(String name, Class<?>[] types, Object... args)
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

	private byte[] handle(byte[] request) throws Exception {
		return (byte[]) call("handlePublisherRequest",
				new Class<?>[] {byte[].class, byte[].class},
				channel.channelId, request);
	}

	private void seedPublisher(boolean publicChannel,
			@Nullable byte[] kContent) throws Exception {
		List<ChannelPost> posts = new ArrayList<>(POSTS);
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < POSTS; seq++) {
			long ts = HOUR * (seq + 2);
			List<ChannelPost.ChannelAttachment> none =
					Collections.emptyList();
			String body = "post " + seq;
			byte[] input = codec.postSignedInput(channel.channelId, seq,
					prev, ts, body, codec.attachmentsHash(none), 0L);
			ChannelPost p = new ChannelPost(channel.channelId, seq, prev,
					ts, body, none, 0L,
					signatures.signPost(input, channel.priv()), false);
			posts.add(p);
			prev = new ChannelChainVerifier(codec).hashOf(p);
		}
		store.putChannel(new ChannelState(channel.channelId, channel.salt,
				channel.ed(), channel.ml(), "name", "description", null,
				HOUR, publicChannel, publicChannel ? null : new byte[32],
				PUBLISHER_ONION, 1L, true, POSTS - 1L,
				kContent == null ? null : contentKey.hashContentKey(kContent),
				kContent, Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, false,
				Collections.<ChannelDelegationCert>emptyList()));
		store.putPublisherPrivKey(channel.channelId,
				channel.priv().getEncoded().clone());
		store.writePosts(channel.channelId, posts);
	}

	private byte[] confirmation(String onion) throws IOException {
		try {
			return pullCodec.encodeOnionConfirmed(channel.channelId, onion,
					channel.salt, channel.ml(),
					signatures.signOnionConfirmation(
							codec.onionConfirmationSignedInput(
									channel.channelId, onion),
							channel.priv()));
		} catch (java.security.GeneralSecurityException e) {
			throw new IOException(e);
		}
	}

	private void seedSubscriber() throws Exception {
		store.putChannel(new ChannelState(channel.channelId,
				new byte[ChannelConstants.CHANNEL_SALT_BYTES], channel.ed(),
				channel.ml(), "", "", null, HOUR, true, null,
				PUBLISHER_ONION, -1L, false, -1L, null, null,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, true,
				Collections.<ChannelDelegationCert>emptyList()));
		store.writePosts(channel.channelId,
				Collections.<ChannelPost>emptyList());
	}

	private ChannelComment comment(KeyPair author, String body)
			throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) author.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) author.getPublic();
		long ts = clock.currentTimeMillis() / HOUR * HOUR;
		long id = random.nextLong();
		byte[] sig = signatures.signUserComment(
				codec.commentSignedInput(channel.channelId, 2L, id, body,
						"Member", ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		return new ChannelComment(2L, id, body, "Member",
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), ts, sig);
	}

	private boolean sendComment(ChannelComment c) throws Exception {
		byte[] ack = handle(pullCodec.encodeCommentRequest(channel.channelId,
				c.getParentPostSeqNum(), c.getCommentId(), c.getBody(),
				c.getAuthorDisplayName(), c.getTimestampHourMs(),
				c.getAuthorEd25519PubKey(), c.getAuthorMlDsaPubKey(),
				c.getSignature(), null, null));
		return ack.length > 0 && pullCodec.decodeCommentAck(ack);
	}

	private boolean sendReaction(KeyPair signer, long seq, String emoji)
			throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) signer.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) signer.getPublic();
		long ts = clock.currentTimeMillis() / HOUR * HOUR;
		byte[] sig = signatures.signUserReaction(
				codec.reactionSignedInput(channel.channelId, seq, emoji, ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		byte[] ack = handle(pullCodec.encodeReactionRequest(
				channel.channelId, seq, emoji, ts, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), sig, null, null));
		return ack.length > 0 && pullCodec.decodeReactionAck(ack);
	}

	private boolean sendAnnouncement(KeyPair signer, String name)
			throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) signer.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) signer.getPublic();
		long ts = clock.currentTimeMillis() / HOUR * HOUR;
		byte[] sig = signatures.signUserAnnounce(
				codec.announceSignedInput(channel.channelId, name, ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		byte[] ack = handle(pullCodec.encodeAnnounceRequest(
				channel.channelId, name, ts, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), sig, null, null));
		return ChannelTestNode.ackOk(ack);
	}

	private static void delete(File f) {
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) delete(c);
		}
		f.delete();
	}
}
