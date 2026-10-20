package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.db.event.PurgeRequestedEvent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.B4OwnRotationCompletedEvent;
import org.zerionproject.core.api.plugin.event.TransportActiveEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelInviteLink;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ApplicationStatus;
import org.zerionproject.app.api.channel.ChannelApplication;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelQueuedItem;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelRejoinResult;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.app.api.channel.ChannelSyncStatus;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.app.api.channel.event.ChannelCommentReceivedEvent;
import org.zerionproject.app.api.channel.event.ChannelPostReceivedEvent;
import org.zerionproject.app.api.channel.event.ChannelPostsRemovedEvent;
import org.zerionproject.app.api.channel.event.ChannelStateChangedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

@ThreadSafe
@NotNullByDefault
class ChannelManagerImpl
		implements ChannelManager, EventListener, OpenDatabaseHook {

	private static final long HOUR_MS = 60L * 60L * 1000L;

	private final CryptoComponent crypto;
	private final EventBus eventBus;
	private final Clock clock;
	private final ChannelCodec codec;
	private final ChannelSignatures signatures;
	private final ChannelChainVerifier chainVerifier;
	private final ChannelStore store;
	private final ChannelContentKey contentKey;
	private final ChannelPostValidator validator;
	private final ChannelPullProtocol pullProtocol;
	private final ChannelTransport transport;
	private final ChannelBlobStore blobStore;
	private final ChannelReactionStore reactionStore;
	private final ChannelWriteBudget writeBudget =
			new ChannelWriteBudget(ChannelConstants.CHANNEL_WRITE_BURST_BYTES,
					ChannelConstants.CHANNEL_WRITE_BYTES_PER_HOUR);
	private final ChannelWriteBudget knownWriteBudget =
			new ChannelWriteBudget(
					ChannelConstants.KNOWN_SIGNER_WRITE_BURST_BYTES,
					ChannelConstants.KNOWN_SIGNER_WRITE_BYTES_PER_HOUR);
	private final ChannelSubscriberStore subscriberStore;
	private final ChannelCommentStore commentStore;
	private final ChannelDiscussionStore discussionStore;
	private final ChannelApplicationStore applicationStore;
	private final ChannelMyApplicationsStore myApplicationsStore;
	private final ChannelTombstoneStore tombstoneStore;
	private final ChannelPostTombstoneStore postTombstoneStore;
	private final ChannelSelfAnnounceStore selfAnnounceStore;
	private final IdentityManager identityManager;
	private final TaskScheduler taskScheduler;
	private final java.util.concurrent.Executor ioExecutor;
	private final SecureRandom random;
	private final java.util.Map<String, ChannelTransport.ChannelServer>
			boundServers =
					new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String, Integer> publisherVersions =
			new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> inFlightPulls =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final java.util.Map<String,
			java.util.concurrent.locks.ReentrantLock> channelLocks =
					new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String, Long> lastApprovalPollMs =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static final long APPROVAL_POLL_MIN_INTERVAL_MS =
			30L * 1000L;
	private static final long PULL_NONCE_TTL_MS = 5L * 60L * 1000L;
	private static final long BLOB_ORPHAN_GRACE_MS = 24L * 60L * 60L * 1000L;
	private static final int PULL_NONCE_MAX_PER_CHANNEL = 4096;
	private final java.util.Map<String,
			java.util.LinkedHashMap<String, Long>> seenPullNonces =
					new java.util.concurrent.ConcurrentHashMap<>();
	private static final long PULL_MIN_INTERVAL_MS = 5_000L;
	private static final long PULL_MAX_INTERVAL_MS = 60_000L;
	private static final long PULL_ACTIVE_WINDOW_MS = 120_000L;
	private static final long PULL_BACKOFF_STEP_MS = 5_000L;
	private static final long MIN_REFRESH_TICK_MS = 1_000L;
	private static final long FIRST_PULL_SPREAD_MS = 3L * PULL_MIN_INTERVAL_MS;
	private static final long PULL_FAILURE_MAX_INTERVAL_MS = 15L * 60L * 1000L;
	private static final long RECONNECT_SPREAD_FROM_MS = 60_000L;
	private static final long RECONNECT_SPREAD_MS = 60_000L;
	private static final long DORMANT_AT_UNKNOWN = Long.MIN_VALUE;

	private static final class PollState {
		volatile long nextDueAt;
		volatile long intervalMs = PULL_MIN_INTERVAL_MS;
		volatile long lastActivityAt;
		volatile long failingSince;
		volatile int nextFallback;
		int failures;
		boolean resting;
		volatile long dormantAt = DORMANT_AT_UNKNOWN;
	}

	private final java.util.Map<String, PollState> polls =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static final int BLOB_HASH_BYTES = 32;
	private static final int ED25519_PUBLIC_KEY_BYTES = 32;
	private static final long MAX_SERVED_BLOB_BYTES =
			ChannelConstants.MAX_RESPONSE_BYTES - 4096L;
	private static final int MAX_SERVING_BLOB_KIB = 24 * 1024;
	private final java.util.concurrent.Semaphore servingBlobKib =
			new java.util.concurrent.Semaphore(MAX_SERVING_BLOB_KIB);

	long editorPostBytesCap =
			ChannelConstants.MAX_EDITOR_POST_BYTES_PER_CHANNEL;

	private static final String INSTANCE_FILE = "channel-instance";
	private static final String NS_EDITOR_QUOTA_PREFIX =
			"zerion-channels-editor-quota:";
	private final Object instanceLock = new Object();
	private volatile boolean instanceChecked;

	private java.util.concurrent.locks.ReentrantLock lockFor(
			byte[] channelId) {
		return channelLocks.computeIfAbsent(ChannelStore.hex(channelId),
				k -> new java.util.concurrent.locks.ReentrantLock());
	}

	private interface LockedWork {
		void run() throws DbException;
	}

	private void underLock(byte[] channelId, LockedWork work)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			work.run();
		} finally {
			lock.unlock();
		}
	}

	private interface UserSigner {
		byte[] sign(org.zerionproject.core.api.crypto.PrivateKey ed25519,
				byte[] mlDsa) throws GeneralSecurityException;
	}

	private static byte[] signAndClear(HybridSignaturePrivateKey priv,
			UserSigner signer) throws GeneralSecurityException {
		org.zerionproject.core.api.crypto.SignaturePrivateKey ed =
				priv.getEd25519Component();
		byte[] ml = priv.getMlDsaPrivateKey();
		try {
			return signer.sign(ed, ml);
		} finally {
			java.util.Arrays.fill(ed.getEncoded(), (byte) 0);
			java.util.Arrays.fill(ml, (byte) 0);
			priv.clear();
		}
	}

	@Nullable
	private HybridSignaturePrivateKey publisherKey(byte[] channelId)
			throws DbException {
		byte[] encoded = store.getPublisherPrivKey(channelId);
		if (encoded == null) return null;
		try {
			return new HybridSignaturePrivateKey(encoded);
		} catch (RuntimeException e) {
			java.util.Arrays.fill(encoded, (byte) 0);
			throw e;
		}
	}

	@Inject
	ChannelManagerImpl(CryptoComponent crypto, EventBus eventBus,
			Clock clock, ChannelCodec codec,
			ChannelSignatures signatures,
			ChannelChainVerifier chainVerifier, ChannelStore store,
			ChannelContentKey contentKey,
			ChannelPostValidator validator,
			ChannelPullProtocol pullProtocol,
			ChannelTransport transport,
			ChannelBlobStore blobStore,
			ChannelReactionStore reactionStore,
			ChannelSubscriberStore subscriberStore,
			ChannelCommentStore commentStore,
			ChannelDiscussionStore discussionStore,
			ChannelApplicationStore applicationStore,
			ChannelMyApplicationsStore myApplicationsStore,
			ChannelTombstoneStore tombstoneStore,
			ChannelPostTombstoneStore postTombstoneStore,
			ChannelSelfAnnounceStore selfAnnounceStore,
			IdentityManager identityManager,
			TaskScheduler taskScheduler,
			@IoExecutor java.util.concurrent.Executor ioExecutor) {
		this.crypto = crypto;
		this.eventBus = eventBus;
		this.clock = clock;
		this.codec = codec;
		this.signatures = signatures;
		this.chainVerifier = chainVerifier;
		this.store = store;
		this.contentKey = contentKey;
		this.validator = validator;
		this.pullProtocol = pullProtocol;
		this.transport = transport;
		this.blobStore = blobStore;
		this.reactionStore = reactionStore;
		this.subscriberStore = subscriberStore;
		this.commentStore = commentStore;
		this.discussionStore = discussionStore;
		this.applicationStore = applicationStore;
		this.myApplicationsStore = myApplicationsStore;
		this.tombstoneStore = tombstoneStore;
		this.postTombstoneStore = postTombstoneStore;
		this.selfAnnounceStore = selfAnnounceStore;
		this.identityManager = identityManager;
		this.taskScheduler = taskScheduler;
		this.ioExecutor = ioExecutor;
		this.random = new SecureRandom();
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		eventBus.addListener(this);
		taskScheduler.scheduleWithFixedDelay(this::runDailyPurgeSafely,
				ioExecutor, 5L, 24L * 60L * 60L,
				java.util.concurrent.TimeUnit.MINUTES);
		taskScheduler.scheduleWithFixedDelay(this::ensurePublisherServersBound,
				ioExecutor, 3L, 3L,
				java.util.concurrent.TimeUnit.MINUTES);
		taskScheduler.scheduleWithFixedDelay(this::healAllPublisherServers,
				ioExecutor, 5L, 10L,
				java.util.concurrent.TimeUnit.MINUTES);
		taskScheduler.scheduleWithFixedDelay(this::maintainOnionsSafely,
				ioExecutor, 4L, 15L,
				java.util.concurrent.TimeUnit.MINUTES);
		scheduleNextRefresh(3_000L);
		ioExecutor.execute(this::rebindOwnedChannelsOnStartup);
	}

	private void healAllPublisherServers() {
		Collection<ChannelState> all;
		try {
			all = store.listChannels();
		} catch (DbException ignored) {
			return;
		}
		for (ChannelState s : all) {
			if (!s.weArePublisher()) continue;
			healPublisherServer(s.getChannelId());
		}
	}

	private void healPublisherServer(byte[] channelId) {
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null || !s.weArePublisher()) return;
			String key = ChannelStore.hex(channelId);
			ChannelTransport.ChannelServer bound = boundServers.get(key);
			if (bound == null) {
				bindPublisherServer(channelId);
				return;
			}
			if (transport.isReachable(bound.getOnionAddress())) return;
			java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
			lock.lock();
			try {
				ChannelTransport.ChannelServer current = boundServers.get(key);
				if (current != bound) return;
				boundServers.remove(key);
				try {
					current.close();
				} catch (Exception ignored) {
				}
				bindPublisherServer(channelId);
			} finally {
				lock.unlock();
			}
		} catch (Throwable t) {
		}
	}

	private void ensurePublisherServersBound() {
		restartRefreshIfStalled();
		try {
			for (ChannelState s : store.listChannels()) {
				if (!s.weArePublisher()) continue;
				String key = ChannelStore.hex(s.getChannelId());
				if (boundServers.containsKey(key)) continue;
				java.util.concurrent.locks.ReentrantLock lock =
						lockFor(s.getChannelId());
				lock.lock();
				try {
					ChannelState fresh = store.getChannel(s.getChannelId());
					if (fresh == null || !fresh.weArePublisher()) continue;
					if (boundServers.containsKey(key)) continue;
					bindPublisherServer(fresh.getChannelId());
				} finally {
					lock.unlock();
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private long refreshAllSubscriptionsSafely() {
		long now = clock.currentTimeMillis();
		long nextWake = now + PULL_MAX_INTERVAL_MS;
		Collection<ChannelState> all;
		try {
			all = store.listChannels();
		} catch (DbException ignored) {
			return nextWake;
		}
		for (ChannelState s : all) {
			if (s.weArePublisher()) continue;
			if (s.getCurrentOnion() == null
					|| s.getCurrentOnion().isEmpty()) continue;
			byte[] channelId = s.getChannelId();
			String pullKey = ChannelStore.hex(channelId);
			boolean neverPulled = s.getManifestSeq() < 0;
			PollState poll = polls.computeIfAbsent(pullKey, k -> {
				PollState fresh = new PollState();
				fresh.nextDueAt = neverPulled ? now : now + (long) (
						random.nextDouble() * FIRST_PULL_SPREAD_MS);
				return fresh;
			});
			if (now < poll.nextDueAt) {
				nextWake = Math.min(nextWake, poll.nextDueAt);
				continue;
			}
			if (restIfDormant(channelId, poll, now)) {
				nextWake = Math.min(nextWake, poll.nextDueAt);
				continue;
			}
			pullDue(channelId, pullKey);
			nextWake = Math.min(nextWake, planNextPull(poll, now));
		}
		return scheduleLinkCandidates(now, nextWake);
	}

	private long planNextPull(PollState poll, long now) {
		synchronized (poll) {
			if (poll.failures == 0) {
				boolean active = clock.currentTimeMillis()
						- poll.lastActivityAt < PULL_ACTIVE_WINDOW_MS;
				poll.intervalMs = active ? PULL_MIN_INTERVAL_MS
						: Math.min(PULL_MAX_INTERVAL_MS,
						poll.intervalMs + PULL_BACKOFF_STEP_MS);
				poll.nextDueAt = now + jittered(poll.intervalMs);
			} else if (poll.nextDueAt <= now) {
				poll.nextDueAt = now + jittered(poll.intervalMs);
			}
			return poll.nextDueAt;
		}
	}

	private long jittered(long intervalMs) {
		return (long) (intervalMs * (0.5 + random.nextDouble()));
	}

	private boolean restIfDormant(byte[] channelId, PollState poll,
			long now) {
		long at = poll.dormantAt;
		if (at == DORMANT_AT_UNKNOWN) {
			try {
				at = syncStatus().dormantAt(channelId);
			} catch (DbException e) {
				return false;
			}
			synchronized (poll) {
				if (poll.dormantAt == DORMANT_AT_UNKNOWN) poll.dormantAt = at;
				at = poll.dormantAt;
			}
		}
		if (now < at) return false;
		synchronized (poll) {
			poll.resting = true;
			poll.nextDueAt = now + PULL_FAILURE_MAX_INTERVAL_MS;
		}
		return true;
	}

	private void notePullOutcome(String pullKey, boolean reached,
			boolean failed) {
		long now = clock.currentTimeMillis();
		if (failed) {
			PollState poll = polls.computeIfAbsent(pullKey,
					k -> new PollState());
			synchronized (poll) {
				poll.failures++;
				poll.intervalMs = Math.min(PULL_FAILURE_MAX_INTERVAL_MS,
						Math.max(PULL_MIN_INTERVAL_MS, poll.intervalMs) * 2L);
				poll.nextDueAt = Math.max(poll.nextDueAt,
						now + jittered(poll.intervalMs));
			}
		} else if (reached) {
			PollState poll = polls.get(pullKey);
			if (poll == null) return;
			synchronized (poll) {
				if (poll.failures == 0 && !poll.resting) return;
				poll.failures = 0;
				poll.resting = false;
				poll.intervalMs = PULL_MIN_INTERVAL_MS;
				poll.nextDueAt = Math.min(poll.nextDueAt,
						now + jittered(poll.intervalMs));
			}
		}
	}

	private void resetPullBackoff(byte[] channelId) {
		PollState poll = polls.get(ChannelStore.hex(channelId));
		if (poll == null) return;
		synchronized (poll) {
			if (poll.failures == 0) return;
			poll.failures = 0;
			poll.intervalMs = PULL_MIN_INTERVAL_MS;
		}
	}

	private void easeAllPullBackoffs() {
		long now = clock.currentTimeMillis();
		for (PollState poll : polls.values()) {
			synchronized (poll) {
				if (poll.failures == 0) continue;
				poll.intervalMs = Math.max(PULL_MIN_INTERVAL_MS,
						poll.intervalMs / 2L);
				poll.nextDueAt = now + RECONNECT_SPREAD_FROM_MS + (long) (
						random.nextDouble() * RECONNECT_SPREAD_MS);
			}
		}
	}

	private void noteSyncOutcome(byte[] channelId, boolean reached,
			boolean failed) {
		try {
			underLock(channelId, () -> {
				ChannelState s = store.getChannel(channelId);
				if (s == null || s.weArePublisher()) return;
				String key = ChannelStore.hex(channelId);
				notePullOutcome(key, reached, failed);
				long now = clock.currentTimeMillis();
				ChannelSyncStatusStore.Record r = null;
				if (reached) r = syncStatus().noteSuccess(channelId, now);
				if (failed) r = syncStatus().noteFailure(channelId, now);
				if (r != null) keepDormantAt(key, r.dormantAt());
			});
		} catch (DbException | RuntimeException ignored) {
		}
	}

	private void keepDormantAt(String pullKey, long at) {
		PollState poll = polls.get(pullKey);
		if (poll == null) return;
		synchronized (poll) {
			poll.dormantAt = at;
		}
	}

	private ChannelSyncStatusStore syncStatus() {
		ChannelSyncStatusStore s = syncStatusInstance;
		if (s != null) return s;
		synchronized (syncStatusLock) {
			if (syncStatusInstance == null) {
				syncStatusInstance =
						new ChannelSyncStatusStore(store.settings());
			}
			return syncStatusInstance;
		}
	}

	private final Object syncStatusLock = new Object();
	private volatile ChannelSyncStatusStore syncStatusInstance;

	@Override
	public ChannelSyncStatus getSyncStatus(byte[] channelId)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null || s.weArePublisher()) return ChannelSyncStatus.UNKNOWN;
		return syncStatus().status(channelId, clock.currentTimeMillis());
	}

	@Override
	public List<String> getOwnerDeletedChannelNames() throws DbException {
		return syncStatus().deletedNotices(clock.currentTimeMillis());
	}

	@Override
	public void dismissOwnerDeletedChannelName(String name)
			throws DbException {
		syncStatus().dismissDeletedNotice(name, clock.currentTimeMillis());
	}

	private void noteOwnerDeleted(ChannelState s) {
		try {
			syncStatus().addDeletedNotice(s.getName(),
					clock.currentTimeMillis());
		} catch (DbException ignored) {
		}
	}

	private void purgeOldDeletedNotices() {
		try {
			syncStatus().purgeDeletedNotices(clock.currentTimeMillis());
		} catch (DbException | RuntimeException ignored) {
		}
	}

	long pollIntervalMs(byte[] channelId) {
		PollState p = polls.get(ChannelStore.hex(channelId));
		return p == null ? PULL_MIN_INTERVAL_MS : p.intervalMs;
	}

	long nextPullDueMs(byte[] channelId) {
		PollState p = polls.get(ChannelStore.hex(channelId));
		return p == null ? -1L : p.nextDueAt;
	}

	private void pullDue(byte[] channelId, String pullKey) {
		if (!inFlightPulls.add(pullKey)) return;
		try {
			ioExecutor.execute(() -> {
				try {
					pullAndApply(channelId, false);
					flushOutboxSafely(channelId);
				} catch (DbException ignored) {
				} finally {
					inFlightPulls.remove(pullKey);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException re) {
			inFlightPulls.remove(pullKey);
		}
	}

	private static final long REFRESH_STALL_MS = 3L * PULL_MAX_INTERVAL_MS;
	private static final int MAX_REFRESH_RETRY_SHIFT = 6;
	private final java.util.concurrent.atomic.AtomicLong refreshGeneration =
			new java.util.concurrent.atomic.AtomicLong();
	private volatile long refreshDueAt;

	private void scheduleNextRefresh(long delayMs) {
		scheduleRefresh(refreshGeneration.get(), delayMs, 0);
	}

	private void scheduleRefresh(long generation, long delayMs,
			int failures) {
		refreshDueAt = clock.currentTimeMillis() + delayMs;
		try {
			taskScheduler.schedule(() -> startRefresh(generation, failures),
					Runnable::run, delayMs,
					java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
		}
	}

	private void startRefresh(long generation, int failures) {
		if (generation != refreshGeneration.get()) return;
		try {
			ioExecutor.execute(() -> refreshAndReschedule(generation));
		} catch (RuntimeException e) {
			int next = Math.min(failures + 1, MAX_REFRESH_RETRY_SHIFT);
			scheduleRefresh(generation, Math.min(PULL_MAX_INTERVAL_MS,
					MIN_REFRESH_TICK_MS << next), next);
		}
	}

	private void refreshAndReschedule() {
		refreshAndReschedule(refreshGeneration.get());
	}

	private void refreshAndReschedule(long generation) {
		if (generation != refreshGeneration.get()) return;
		long nextWake = clock.currentTimeMillis() + PULL_MAX_INTERVAL_MS;
		try {
			nextWake = refreshAllSubscriptionsSafely();
		} finally {
			scheduleRefresh(generation, Math.max(MIN_REFRESH_TICK_MS,
					nextWake - clock.currentTimeMillis()), 0);
		}
	}

	void restartRefreshIfStalled() {
		long due = refreshDueAt;
		if (due == 0L
				|| clock.currentTimeMillis() - due <= REFRESH_STALL_MS) {
			return;
		}
		scheduleRefresh(refreshGeneration.incrementAndGet(), 0L, 0);
	}

	private void rebindOwnedChannelsOnStartup() {
		try {
			finishPendingRemovals();
		} catch (Throwable ignored) {
		}
		try {
			dropSharedSyncState();
		} catch (Throwable ignored) {
		}
		try {
			ensureInstanceChecked();
		} catch (Throwable ignored) {
		}
		try {
			for (ChannelState s : store.listChannels()) {
				if (!s.weArePublisher()) continue;
				bindPublisherServer(s.getChannelId());
			}
		} catch (Throwable ignored) {
		}
		maintainOnionsSafely();
	}

	void finishPendingRemovals() throws DbException {
		for (java.util.Map.Entry<String, Boolean> e
				: store.pendingRemovals().entrySet()) {
			byte[] channelId = ChannelOnionStore.unhex(e.getKey());
			if (channelId == null) continue;
			java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
			lock.lock();
			try {
				removeChannelLocally(channelId, e.getValue());
			} finally {
				lock.unlock();
			}
		}
	}

	private void dropSharedSyncState() throws DbException {
		java.util.List<String> shared = new ArrayList<>();
		for (String ns : new String[] {ChannelItemSync.NS_SHARED_STATE,
				ChannelItemSync.NS_SHARED_CURSOR}) {
			if (!store.settings().getSettings(ns).isEmpty()) shared.add(ns);
		}
		if (!shared.isEmpty()) store.settings().deleteNamespaces(shared);
	}

	void ensureInstanceChecked() throws DbException {
		if (instanceChecked) return;
		synchronized (instanceLock) {
			if (instanceChecked) return;
			java.io.File marker = new java.io.File(blobStore.profileDir(),
					INSTANCE_FILE);
			String stored = store.getInstanceId();
			if (stored == null || !stored.equals(readMarker(marker))) {
				if (stored != null) recoverFromRestore();
				String id = ChannelStore.hex(freshBytes(16));
				writeMarker(marker, id);
				store.setInstanceId(id);
			}
			instanceChecked = true;
		}
	}

	@Nullable
	private static String readMarker(java.io.File marker) {
		if (!marker.isFile() || marker.length() > 256) return null;
		try {
			byte[] b = java.nio.file.Files.readAllBytes(marker.toPath());
			return new String(b, java.nio.charset.StandardCharsets.US_ASCII)
					.trim();
		} catch (IOException e) {
			return null;
		}
	}

	private static void writeMarker(java.io.File marker, String id) {
		java.io.File dir = marker.getParentFile();
		if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
		try (java.io.FileOutputStream out =
				new java.io.FileOutputStream(marker)) {
			out.write(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			out.getFD().sync();
		} catch (IOException ignored) {
		}
	}

	private void recoverFromRestore() throws DbException {
		long now = clock.currentTimeMillis();
		for (ChannelState listed : store.listChannels()) {
			if (!listed.weArePublisher()) continue;
			byte[] channelId = listed.getChannelId();
			java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
			lock.lock();
			try {
				ChannelState s = store.getChannel(channelId);
				if (s == null || !s.weArePublisher()) continue;
				ChannelChainTip tip = chainTip(s);
				long base = Math.max(0L, Math.max(
						tip == null ? -1L : tip.seqNum,
						s.getHighestKnownPostSeq()));
				long jumped = Math.min(ChannelConstants.MAX_SEQUENCE_NUMBER
						- ChannelConstants.RESTORE_SEQUENCE_JUMP, base)
						+ ChannelConstants.RESTORE_SEQUENCE_JUMP;
				store.posts().jumpTip(channelId, jumped);
				ChannelOnionStore.Record r = store.onions().get(channelId);
				long keepUntil = now
						+ ChannelConstants.ONION_MIGRATION_DAYS * DAY_MS;
				ChannelOnionStore.Record kept = new ChannelOnionStore.Record();
				kept.nextRotationMs = r.nextRotationMs;
				kept.deleted = r.deleted;
				for (ChannelOnionStore.Retiring e : r.retiring) {
					kept.retiring.add(new ChannelOnionStore.Retiring(e.onion,
							e.privateKey, Math.max(e.retireAtMs, keepUntil)));
				}
				java.util.Map<String, org.zerionproject.core.api.settings
						.Settings> also = new java.util.HashMap<>();
				also.put(ChannelOnionStore.NS,
						store.onions().encode(channelId, kept));
				store.putChannel(restoredState(s, jumped), also);
				reactionStore.sync().restart(channelId);
				commentStore.sync().restart(channelId);
			} finally {
				lock.unlock();
			}
		}
	}

	private static ChannelState restoredState(ChannelState s, long tipSeq) {
		long manifestSeq = Math.min(ChannelConstants.MAX_SEQUENCE_NUMBER
				- ChannelConstants.RESTORE_SEQUENCE_JUMP,
				Math.max(0L, s.getManifestSeq()))
				+ ChannelConstants.RESTORE_SEQUENCE_JUMP;
		long nextDelegationSeq = s.getNextDelegationSeq()
				+ ChannelConstants.RESTORE_DELEGATION_SEQUENCE_JUMP;
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), s.getCurrentOnion(),
				manifestSeq, true, tipSeq,
				s.getContentKeyHash(), s.getContentKey(),
				s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(),
				nextDelegationSeq,
				s.getOnionPrivateKey(),
				s.getPinnedPostSeq(),
				s.requiresApproval(),
				s.getRetiredDelegations());
	}

	private static final long DAY_MS = 24L * HOUR_MS;
	private final java.util.Map<String, ChannelTransport.ChannelServer>
			retiringServers = new java.util.concurrent.ConcurrentHashMap<>();

	private void maintainOnionsSafely() {
		try {
			maintainOnions();
		} catch (Throwable ignored) {
		}
	}

	void maintainOnions() throws DbException {
		ensureInstanceChecked();
		long now = clock.currentTimeMillis();
		for (String hex : store.onions().channels()) {
			byte[] channelId = ChannelOnionStore.unhex(hex);
			if (channelId == null) continue;
			java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
			lock.lock();
			try {
				retireDueOnions(channelId, now);
			} finally {
				lock.unlock();
			}
		}
		for (ChannelState s : store.listChannels()) {
			if (!s.weArePublisher()) continue;
			byte[] channelId = s.getChannelId();
			java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
			lock.lock();
			try {
				ChannelOnionStore.Record r = store.onions().get(channelId);
				if (r.nextRotationMs == 0L) {
					r.nextRotationMs = now + rotationInterval();
					store.onions().put(channelId, r);
				} else if (now >= r.nextRotationMs) {
					rotateOnionLocked(channelId);
				}
			} finally {
				lock.unlock();
			}
		}
	}

	private long rotationInterval() {
		int range = ChannelConstants.ONION_ROTATION_MAX_DAYS
				- ChannelConstants.ONION_ROTATION_MIN_DAYS + 1;
		return (ChannelConstants.ONION_ROTATION_MIN_DAYS
				+ random.nextInt(range)) * DAY_MS
				+ (long) (random.nextDouble() * DAY_MS);
	}

	private void retireDueOnions(byte[] channelId, long now)
			throws DbException {
		ChannelOnionStore.Record r = store.onions().get(channelId);
		boolean changed = false;
		java.util.Iterator<ChannelOnionStore.Retiring> it =
				r.retiring.iterator();
		while (it.hasNext()) {
			ChannelOnionStore.Retiring e = it.next();
			if (now >= e.retireAtMs) {
				closeQuietly(retiringServers.remove(e.onion));
				it.remove();
				changed = true;
			} else if (!retiringServers.containsKey(e.onion)) {
				try {
					retiringServers.put(e.onion, transport.bindServer(
							channelId, e.privateKey,
							requestBytes -> handlePublisherRequest(
									channelId, requestBytes)));
				} catch (IOException | RuntimeException ignored) {
				}
			}
		}
		if (r.deleted && r.retiring.isEmpty()) {
			store.onions().remove(channelId);
			tombstoneStore.remove(channelId);
			return;
		}
		if (changed) store.onions().put(channelId, r);
	}

	private static void closeQuietly(
			@Nullable ChannelTransport.ChannelServer server) {
		if (server == null) return;
		try {
			server.close();
		} catch (RuntimeException ignored) {
		}
	}

	private void rotateOnionLocked(byte[] channelId) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null || !s.weArePublisher()) return;
		String key = ChannelStore.hex(channelId);
		ChannelTransport.ChannelServer fresh;
		try {
			fresh = transport.bindServer(channelId, null,
					requestBytes -> handlePublisherRequest(channelId,
							requestBytes));
		} catch (IOException | RuntimeException e) {
			return;
		}
		String oldOnion = s.getCurrentOnion();
		String oldKey = s.getOnionPrivateKey();
		long now = clock.currentTimeMillis();
		ChannelOnionStore.Record r = store.onions().get(channelId);
		r.nextRotationMs = now + rotationInterval();
		boolean retire = oldKey != null && !oldOnion.isEmpty()
				&& !oldOnion.equals(fresh.getOnionAddress());
		if (retire) {
			r.retiring.add(new ChannelOnionStore.Retiring(oldOnion, oldKey,
					now + ChannelConstants.ONION_MIGRATION_DAYS * DAY_MS));
		}
		ChannelState updated = withOnionPrivateKey(
				withRotatedOnion(s, fresh.getOnionAddress()),
				fresh.getOnionPrivateKey());
		try {
			store.putChannel(updated, onionRecordWrite(channelId, r));
		} catch (DbException | RuntimeException e) {
			closeQuietly(fresh);
			throw e;
		}
		ChannelTransport.ChannelServer old = boundServers.put(key, fresh);
		if (old != null && retire && oldOnion.equals(old.getOnionAddress())) {
			retiringServers.put(oldOnion, old);
		} else {
			closeQuietly(old);
			if (retire) retireDueOnions(channelId, now);
		}
		fireEvent(channelId, ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
	}

	private void revokeOnionsLocked(byte[] channelId, ChannelState next,
			java.util.Map<String, org.zerionproject.core.api.settings.Settings>
					alsoWrite) throws DbException {
		ChannelOnionStore.Record r = store.onions().get(channelId);
		List<String> retired = new ArrayList<>();
		for (ChannelOnionStore.Retiring e : r.retiring) retired.add(e.onion);
		r.retiring.clear();
		r.nextRotationMs = clock.currentTimeMillis() + rotationInterval();
		java.util.Map<String, org.zerionproject.core.api.settings.Settings>
				writes = new java.util.LinkedHashMap<>(alsoWrite);
		writes.putAll(onionRecordWrite(channelId, r));
		store.putChannel(withoutOnion(next), writes);
		for (String onion : retired) {
			closeQuietly(retiringServers.remove(onion));
		}
		closeQuietly(boundServers.remove(ChannelStore.hex(channelId)));
		bindPublisherServer(channelId);
		fireEvent(channelId, ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
	}

	private java.util.Map<String, org.zerionproject.core.api.settings.Settings>
			onionRecordWrite(byte[] channelId, ChannelOnionStore.Record r)
			throws DbException {
		java.util.Map<String, org.zerionproject.core.api.settings.Settings>
				out = new java.util.LinkedHashMap<>();
		out.put(ChannelOnionStore.NS, store.onions().encode(channelId, r));
		return out;
	}

	private void runDailyPurgeSafely() {
		try {
			purgeExpiredPosts();
		} catch (DbException ignored) {
		}
		purgeOldDeletedNotices();
	}

	@Override
	public void eventOccurred(Event e) {
		restartRefreshIfStalled();
		try {
			if (e instanceof B4OwnRotationCompletedEvent) {
				ioExecutor.execute(this::ensurePublisherServersBound);
			} else if (e instanceof TransportActiveEvent) {
				TransportActiveEvent t = (TransportActiveEvent) e;
				if (TorConstants.ID.equals(t.getTransportId())) {
					easeAllPullBackoffs();
					ioExecutor.execute(this::ensurePublisherServersBound);
				}
			}
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
		}
	}

	private ChannelState withRotatedOnion(ChannelState s, String onion) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), onion,
				s.getManifestSeq() + 1L, true,
				s.getHighestKnownPostSeq(),
				s.getContentKeyHash(), s.getContentKey(),
				s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(),
				s.getOnionPrivateKey(),
				s.getPinnedPostSeq(),
				s.requiresApproval(),
				s.getRetiredDelegations());
	}

	@Override
	public ChannelState createChannel(String name, String description,
			boolean publicChannel) throws DbException {
		return createChannel(name, description, publicChannel, false);
	}

	@Override
	public ChannelState createChannel(String name, String description,
			boolean publicChannel, boolean requiresApproval)
			throws DbException {
		validateNameAndDescription(name, description);
		KeyPair sigKeys = crypto.generateHybridSignatureKeyPair();
		HybridSignaturePublicKey hybridPub =
				(HybridSignaturePublicKey) sigKeys.getPublic();
		HybridSignaturePrivateKey hybridPriv =
				(HybridSignaturePrivateKey) sigKeys.getPrivate();
		byte[] ed25519Pub = hybridPub.getEd25519PublicKey();
		byte[] mlDsaPub = hybridPub.getMlDsaPublicKey();
		byte[] salt = new byte[ChannelConstants.CHANNEL_SALT_BYTES];
		random.nextBytes(salt);
		byte[] channelId =
				crypto.hash("org.zerionproject/CHANNEL_ID",
						hybridPub.getEncoded(), salt);
		byte[] capability = publicChannel ? null
				: freshBytes(ChannelConstants.JOIN_CAPABILITY_BYTES);
		byte[] kContent = publicChannel ? null
				: contentKey.generateContentKey();
		byte[] kContentHash = kContent == null ? null
				: contentKey.hashContentKey(kContent);
		long nowHourMs =
				clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		String onion = "";
		long manifestSeq = 0L;
		boolean approvalFlag = !publicChannel && requiresApproval;
		byte[] signedInput = codec.manifestSignedInput(channelId, salt,
				ed25519Pub, mlDsaPub, name, description, null,
				nowHourMs, publicChannel, capability, onion, manifestSeq,
				kContentHash,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(),
				ChannelState.NO_PINNED_POST,
				approvalFlag, discussionStore.isEnabled(channelId));
		byte[] manifestSig;
		try {
			manifestSig = signatures.signManifest(signedInput, hybridPriv);
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		ChannelState state = new ChannelState(channelId, salt,
				ed25519Pub, mlDsaPub, name, description, null,
				nowHourMs, publicChannel, capability, onion,
				manifestSeq, true, -1L,
				kContentHash, kContent,
				java.util.Collections.<org.zerionproject.app.api.channel
						.ChannelDelegationCert>emptyList(),
				java.util.Collections.<Long>emptyList(), 0L, null,
				ChannelState.NO_PINNED_POST, approvalFlag);
		store.putChannel(state);
		store.putPublisherPrivKey(channelId, hybridPriv.getEncoded());
		store.writePosts(channelId, Collections.emptyList());
		bindPublisherServer(channelId);
		ChannelState bound = store.getChannel(channelId);
		if (bound != null) state = bound;
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.CREATED);
		clearReturned(manifestSig);
		return state;
	}

	@Nullable
	private String bindPublisherServer(byte[] channelId) {
		try {
			ensureInstanceChecked();
		} catch (DbException | RuntimeException ignored) {
		}
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			String key = ChannelStore.hex(channelId);
			ChannelTransport.ChannelServer bound = boundServers.get(key);
			if (bound == null) {
				ChannelState existing = store.getChannel(channelId);
				String existingPriv = existing == null ? null
						: existing.getOnionPrivateKey();
				bound = transport.bindServer(channelId, existingPriv,
						requestBytes -> handlePublisherRequest(
								channelId, requestBytes));
				boundServers.put(key, bound);
			}
			reconcilePublisherState(channelId, bound);
			return bound.getOnionAddress();
		} catch (IOException | DbException | RuntimeException e) {
			return null;
		} finally {
			lock.unlock();
		}
	}

	private void reconcilePublisherState(byte[] channelId,
			ChannelTransport.ChannelServer server) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null || !s.weArePublisher()) return;
		ChannelState updated = s;
		boolean changed = false;
		String returnedPriv = server.getOnionPrivateKey();
		if (returnedPriv != null
				&& !returnedPriv.equals(updated.getOnionPrivateKey())) {
			updated = withOnionPrivateKey(updated, returnedPriv);
			changed = true;
		}
		String boundOnion = server.getOnionAddress();
		if (!boundOnion.equals(updated.getCurrentOnion())) {
			updated = withRotatedOnion(updated, boundOnion);
			changed = true;
		}
		if (changed) {
			store.putChannel(updated);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		}
	}

	private static ChannelState withoutOnion(ChannelState s) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), "",
				s.getManifestSeq() + 1L, s.weArePublisher(),
				s.getHighestKnownPostSeq(),
				s.getContentKeyHash(), s.getContentKey(),
				s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(), null,
				s.getPinnedPostSeq(),
				s.requiresApproval(),
				s.getRetiredDelegations());
	}

	private ChannelState withOnionPrivateKey(ChannelState s,
			String privKey) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), s.getCurrentOnion(),
				s.getManifestSeq(), s.weArePublisher(),
				s.getHighestKnownPostSeq(),
				s.getContentKeyHash(), s.getContentKey(),
				s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(), privKey,
				s.getPinnedPostSeq(),
				s.requiresApproval(),
				s.getRetiredDelegations());
	}

	private byte[] handlePublisherRequest(byte[] channelId,
			byte[] requestBytes) {
		try {
			byte[] tombstone = tombstoneStore.get(channelId);
			if (tombstone != null) return tombstone;
		} catch (DbException ignored) {
		}
		String wireType = pullCodec().peekType(requestBytes);
		if (requiresCapability(channelId, wireType)
				&& !challengeAccepted(channelId, requestBytes)) {
			return new byte[0];
		}
		if (ChannelConstants.WIRE_TYPE_GET_ATTACHMENT.equals(wireType)) {
			return handleAttachmentFetch(channelId, requestBytes);
		}
		if (ChannelConstants.WIRE_TYPE_POST_REACTION.equals(wireType)) {
			java.util.concurrent.locks.ReentrantLock l = lockFor(channelId);
			l.lock();
			try {
				return handleReactionRequest(channelId, requestBytes);
			} finally {
				l.unlock();
			}
		}
		if (ChannelConstants.WIRE_TYPE_ANNOUNCE.equals(wireType)) {
			java.util.concurrent.locks.ReentrantLock l = lockFor(channelId);
			l.lock();
			try {
				return handleAnnounceRequest(channelId, requestBytes);
			} finally {
				l.unlock();
			}
		}
		if (ChannelConstants.WIRE_TYPE_POST_COMMENT.equals(wireType)) {
			java.util.concurrent.locks.ReentrantLock l = lockFor(channelId);
			l.lock();
			try {
				return handleCommentRequest(channelId, requestBytes);
			} finally {
				l.unlock();
			}
		}
		if (ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(wireType)) {
			java.util.concurrent.locks.ReentrantLock l = lockFor(channelId);
			l.lock();
			try {
				return handleApplyRequest(channelId, requestBytes);
			} finally {
				l.unlock();
			}
		}
		if (ChannelConstants.WIRE_TYPE_CHECK_APPROVAL.equals(wireType)) {
			return handleCheckApprovalRequest(channelId, requestBytes);
		}
		if (ChannelConstants.WIRE_TYPE_CONFIRM_ONION.equals(wireType)) {
			return handleConfirmOnion(channelId, requestBytes);
		}
		if (ChannelConstants.WIRE_TYPE_SUBMIT_POST.equals(wireType)) {
			java.util.concurrent.locks.ReentrantLock l = lockFor(channelId);
			l.lock();
			try {
				return handleSubmitPost(channelId, requestBytes);
			} finally {
				l.unlock();
			}
		}
		if (ChannelConstants.WIRE_TYPE_CHANGE_POST.equals(wireType)) {
			java.util.concurrent.locks.ReentrantLock l = lockFor(channelId);
			l.lock();
			try {
				return handleChangePost(channelId, requestBytes);
			} finally {
				l.unlock();
			}
		}
		java.util.concurrent.locks.ReentrantLock pullLock =
				lockFor(channelId);
		pullLock.lock();
		try {
			return handlePullRequest(channelId, requestBytes);
		} catch (IOException | DbException e) {
			return new byte[0];
		} finally {
			pullLock.unlock();
		}
	}

	private byte[] handleConfirmOnion(byte[] channelId, byte[] requestBytes) {
		try {
			ChannelPullCodec.ConfirmOnionRequest req =
					pullCodec().decodeConfirmOnionRequest(requestBytes);
			if (!java.util.Arrays.equals(req.channelId, channelId)) {
				return new byte[0];
			}
			ChannelState s = store.getChannel(channelId);
			if (s == null || !s.weArePublisher()) return new byte[0];
			String onion = bareOnion(req.onion);
			if (!servesOnion(s, onion)) return new byte[0];
			HybridSignaturePrivateKey priv = publisherKey(channelId);
			if (priv == null) return new byte[0];
			byte[] sig;
			try {
				sig = signatures.signOnionConfirmation(
						codec.onionConfirmationSignedInput(channelId, onion),
						priv);
			} catch (GeneralSecurityException e) {
				return new byte[0];
			} finally {
				priv.clear();
			}
			return pullCodec().encodeOnionConfirmed(channelId, onion,
					s.getSalt(), s.getPublisherMlDsaPubKey(), sig);
		} catch (IOException | DbException | RuntimeException e) {
			return new byte[0];
		}
	}

	private boolean servesOnion(ChannelState s, String onion)
			throws DbException {
		if (onion.isEmpty()) return false;
		if (onion.equals(bareOnion(s.getCurrentOnion()))) return true;
		for (ChannelOnionStore.Retiring r
				: store.onions().get(s.getChannelId()).retiring) {
			if (onion.equals(bareOnion(r.onion))) return true;
		}
		return false;
	}

	private static String bareOnion(String onion) {
		String lower = onion.toLowerCase(java.util.Locale.ROOT);
		return lower.endsWith(".onion")
				? lower.substring(0, lower.length() - ".onion".length())
				: lower;
	}

	private static final java.util.regex.Pattern BARE_ONION_V3 =
			java.util.regex.Pattern.compile("^[a-z2-7]{56}$");

	private String confirmedOnion(ChannelState s) throws DbException {
		String onion = s.getCurrentOnion();
		if (onion.isEmpty()) throw new DbException();
		if (s.getManifestSeq() >= 0L) return onion;
		byte[] channelId = s.getChannelId();
		ChannelPullCodec.OnionConfirmed c;
		try {
			c = pullCodec().decodeOnionConfirmed(transport.requestFromOnion(
					onion, pullCodec().encodeConfirmOnionRequest(channelId,
							bareOnion(onion))));
		} catch (IOException e) {
			throw new DbException(e);
		}
		byte[] localMlDsa = s.getPublisherMlDsaPubKey();
		if (!java.util.Arrays.equals(c.channelId, channelId)
				|| !BARE_ONION_V3.matcher(c.onion).matches()
				|| (localMlDsa != null && localMlDsa.length > 0
				&& !java.util.Arrays.equals(localMlDsa, c.publisherMlDsa))) {
			throw new DbException();
		}
		HybridSignaturePublicKey pub;
		try {
			pub = new HybridSignaturePublicKey(s.getPublisherEd25519PubKey(),
					c.publisherMlDsa);
		} catch (RuntimeException e) {
			throw new DbException();
		}
		if (!java.util.Arrays.equals(channelId, crypto.hash(
				"org.zerionproject/CHANNEL_ID", pub.getEncoded(), c.salt))
				|| !signatures.verifyOnionConfirmation(c.signature,
				codec.onionConfirmationSignedInput(channelId, c.onion),
				pub)) {
			throw new DbException();
		}
		if (c.onion.equals(bareOnion(onion))) return onion;
		underLock(channelId, () -> {
			ChannelState cur = store.getChannel(channelId);
			if (cur != null && cur.getManifestSeq() < 0L
					&& onion.equals(cur.getCurrentOnion())) {
				store.putChannel(withOnion(cur, c.onion));
			}
		});
		return c.onion;
	}

	private static ChannelState withOnion(ChannelState s, String onion) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(), s.getPublisherMlDsaPubKey(),
				s.getName(), s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), onion, s.getManifestSeq(),
				s.weArePublisher(), s.getHighestKnownPostSeq(),
				s.getContentKeyHash(), s.getContentKey(),
				s.getActiveDelegations(), s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(), s.getOnionPrivateKey(),
				s.getPinnedPostSeq(), s.requiresApproval(),
				s.getRetiredDelegations());
	}

	private byte[] handlePullRequest(byte[] channelId, byte[] requestBytes)
			throws IOException, DbException {
		ChannelPullCodec.PullRequest req = pullCodec()
				.decodePullRequest(requestBytes);
		ChannelState s = store.getChannel(channelId);
		if (s == null) return new byte[0];
		boolean challengeOk = false;
		if (s.getJoinCapability() != null && req.nonce != null
				&& (req.hmacResponse != null || req.version >= 2)) {
			challengeOk = proofAccepted(channelId, s.getJoinCapability(),
					requestBytes, req.nonce, req.hmacResponse, req.version);
			if (!challengeOk) return new byte[0];
		}
		if (!s.isPublicChannel() && s.getJoinCapability() != null
				&& !challengeOk) {
			return new byte[0];
		}
		boolean legacy =
				req.version < ChannelConstants.PROTOCOL_VERSION_DELTAS;
		boolean changes = req.version
				>= ChannelConstants.PROTOCOL_VERSION_POST_CHANGES;
		boolean erasedEdits = req.version
				>= ChannelConstants.PROTOCOL_VERSION_ERASED_EDITS;
		java.util.List<ChannelPost> toSend = legacy
				? store.posts().getPostsAfter(channelId, req.sinceSeqNum,
						(int) ChannelConstants.PULL_BATCH_MAX_POSTS)
				: withoutChanges(s, req.sinceSeqNum, changes, erasedEdits);
		if (legacy) {
			legacyPullAt.put(ChannelStore.hex(channelId),
					clock.currentTimeMillis());
			toSend = legacyFormatOnly(toSend);
		}
		byte[] envelope = null;
		if (challengeOk && s.getContentKey() != null) {
			try {
				envelope = contentKey.wrapContentKey(
						s.getJoinCapability(), channelId,
						s.getContentKey());
			} catch (GeneralSecurityException ignored) {
				envelope = null;
			}
		}
		boolean discussions = discussionStore.isEnabled(channelId);
		java.util.Set<Long> shown = visiblePosts(channelId);
		java.util.List<ChannelReaction> reactions =
				ChannelReactionPolicy.retainPosts(
						reactionStore.getReactions(channelId), shown);
		java.util.List<ChannelComment> comments =
				ChannelCommentPolicy.retainPosts(
						commentStore.getComments(channelId), shown);
		if (legacy) {
			return pullProtocol.buildResponseAsPublisher(s,
					s.getPublisherEd25519PubKey(),
					s.getPublisherMlDsaPubKey(),
					signLatestManifest(s, discussions, null),
					discussions, toSend, envelope,
					java.util.Collections.<String>emptyList(),
					reactions, comments, null, null);
		}
		boolean bound = req.version
				>= ChannelConstants.PROTOCOL_VERSION_AUTHOR_BINDING;
		ChannelItemSync.Delta rd = reactionStore.sync().since(channelId,
				req.reactionsCursor);
		ChannelItemSync.Delta cd = commentStore.sync().since(channelId,
				req.commentsCursor);
		java.util.List<ChannelReaction> sendReactions = new ArrayList<>();
		for (ChannelReaction r : reactions) {
			if (bound && !ChannelAuthorBinding.wellFormed(
					r.getSignerEd25519PubKey(), r.getSignerMlDsaPubKey())) {
				continue;
			}
			if (rd.full || rd.changed.contains(
					ChannelReactionStore.keyOf(r))) {
				sendReactions.add(r);
			}
		}
		java.util.List<ChannelComment> sendComments = new ArrayList<>();
		for (ChannelComment c : comments) {
			if (bound && (!ChannelCommentPolicy.validFields(c)
					|| !ChannelAuthorBinding.wellFormed(
					c.getAuthorEd25519PubKey(), c.getAuthorMlDsaPubKey()))) {
				continue;
			}
			if (cd.full || cd.changed.contains(
					ChannelCommentStore.keyOf(c))) {
				sendComments.add(c);
			}
		}
		ChannelPullCodec.Discussions ds = null;
		if (changes) {
			java.util.Set<Long> exceptions = new java.util.TreeSet<>(
					discussionStore.exceptions(channelId));
			exceptions.retainAll(shown);
			ds = new ChannelPullCodec.Discussions(discussions, exceptions);
		}
		byte[] manifestSig = signLatestManifest(s, discussions,
				bound ? ChannelAuthorBinding.digest(crypto, sendReactions,
						sendComments) : null,
				ds == null ? null : codec.discussionsDigest(ds.enabled,
						ds.exceptions));
		int version = erasedEdits
				? ChannelConstants.PROTOCOL_VERSION_ERASED_EDITS
				: changes ? ChannelConstants.PROTOCOL_VERSION_POST_CHANGES
				: bound ? ChannelConstants.PROTOCOL_VERSION_AUTHOR_BINDING
				: ChannelConstants.PROTOCOL_VERSION_DELTAS;
		return pullProtocol.buildResponseAsPublisher(s,
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), manifestSig,
				discussions, toSend, envelope,
				java.util.Collections.<String>emptyList(),
				sendReactions, sendComments,
				new ChannelPullCodec.ItemSync(rd.cursor(), rd.full, rd.removed),
				new ChannelPullCodec.ItemSync(cd.cursor(), cd.full,
						cd.removed), version, ds);
	}

	private java.util.List<ChannelPost> withoutChanges(ChannelState s,
			long sinceSeqNum, boolean marksKept, boolean erasedKept)
			throws DbException {
		byte[] channelId = s.getChannelId();
		int max = (int) ChannelConstants.PULL_BATCH_MAX_POSTS;
		List<byte[]> keys = marksKept ? Collections.<byte[]>emptyList()
				: contentKeys(s);
		byte[] current = s.getContentKey();
		List<byte[]> currentKey = s.isPublicChannel() || current == null
				? Collections.<byte[]>emptyList()
				: Collections.singletonList(
						contentKey.hashContentKey(current));
		boolean keyNeverRenewed = erasedKept && !s.isPublicChannel()
				&& store.getRetiredContentKeys(channelId).isEmpty();
		java.util.List<ChannelPost> out = new ArrayList<>();
		long after = sinceSeqNum;
		while (out.size() < max) {
			java.util.List<ChannelPost> page =
					store.posts().getPostsAfter(channelId, after, max);
			if (page.isEmpty()) break;
			for (ChannelPost p : page) {
				after = p.getSeqNum();
				if (p.isErased() && (!erasedKept
						|| !servable(s, currentKey, keyNeverRenewed, p))) {
					continue;
				}
				if (!marksKept && editOf(s, keys, p) != null) continue;
				out.add(p);
				if (out.size() >= max) break;
			}
		}
		return out;
	}

	private static java.util.List<ChannelPost> legacyFormatOnly(
			java.util.List<ChannelPost> posts) {
		java.util.List<ChannelPost> out = new ArrayList<>();
		for (ChannelPost p : posts) {
			if (p.getFormatVersion() != ChannelPost.FORMAT_LEGACY) break;
			out.add(p);
		}
		return out;
	}

	private static final long LEGACY_NOTICE_MS = 7L * 24L * 60L * 60L * 1000L;
	private final java.util.Map<String, Long> legacyPullAt =
			new java.util.concurrent.ConcurrentHashMap<>();

	@Override
	public boolean hasOutdatedSubscribers(byte[] channelId) {
		Long at = legacyPullAt.get(ChannelStore.hex(channelId));
		return at != null
				&& clock.currentTimeMillis() - at < LEGACY_NOTICE_MS;
	}

	private java.util.List<ChannelPost> convertToWirePosts(
			ChannelState s, java.util.List<ChannelPost> stored) {
		return stored;
	}

	private byte[] handleAttachmentFetch(byte[] channelId,
			byte[] requestBytes) {
		try {
			ChannelPullCodec.AttachmentRequest req = pullCodec()
					.decodeAttachmentRequest(requestBytes);
			if (!java.util.Arrays.equals(req.channelId, channelId)
					|| req.blobHash.length != BLOB_HASH_BYTES) {
				return new byte[0];
			}
			long size = blobStore.size(channelId, req.blobHash);
			if (size < 0 || size > MAX_SERVED_BLOB_BYTES) {
				return pullCodec().encodeAttachmentResponse(req.blobHash,
						new byte[0]);
			}
			int kib = (int) Math.max(1L, (size + 1023L) / 1024L);
			if (!servingBlobKib.tryAcquire(kib)) return new byte[0];
			try {
				byte[] blob = blobStore.get(channelId, req.blobHash);
				byte[] payload = blob == null
						|| blob.length > MAX_SERVED_BLOB_BYTES
						? new byte[0] : blob;
				return pullCodec().encodeAttachmentResponse(req.blobHash,
						payload);
			} finally {
				servingBlobKib.release(kib);
			}
		} catch (IOException e) {
			return new byte[0];
		}
	}

	private byte[] signLatestManifest(ChannelState s,
			boolean discussionsEnabled, @Nullable byte[] authorBindings) {
		return signLatestManifest(s, discussionsEnabled, authorBindings,
				null);
	}

	private byte[] signLatestManifest(ChannelState s,
			boolean discussionsEnabled, @Nullable byte[] authorBindings,
			@Nullable byte[] discussionsDigest) {
		byte[] signedInput = codec.manifestSignedInput(s.getChannelId(),
				s.getSalt(), s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), s.getCurrentOnion(),
				s.getManifestSeq(),
				s.getContentKeyHash(),
				s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(),
				s.getPinnedPostSeq(),
				s.requiresApproval(), discussionsEnabled);
		try {
			HybridSignaturePrivateKey priv = publisherKey(s.getChannelId());
			if (priv == null) return new byte[0];
			try {
				if (authorBindings != null && discussionsDigest != null) {
					return signatures.signPostChangesManifest(
							ChannelCodec.postChangesManifestSignedInput(
									signedInput, authorBindings,
									discussionsDigest), priv);
				}
				return authorBindings == null
						? signatures.signManifest(signedInput, priv)
						: signatures.signBoundManifest(
						ChannelCodec.boundManifestSignedInput(signedInput,
								authorBindings), priv);
			} finally {
				priv.clear();
			}
		} catch (DbException | GeneralSecurityException e) {
			return new byte[0];
		}
	}

	@Nullable
	private byte[][] buildChallenge(ChannelState s, byte[] channelId) {
		byte[] capability = s.getJoinCapability();
		if (capability == null) return null;
		byte[] nonce = hmacChallenge().freshNonce();
		return new byte[][] {nonce,
				hmacChallenge().respond(capability, nonce, channelId)};
	}

	private boolean requiresCapability(byte[] channelId, String wireType) {
		if (!ChannelConstants.WIRE_TYPE_POST_COMMENT.equals(wireType)
				&& !ChannelConstants.WIRE_TYPE_POST_REACTION.equals(wireType)
				&& !ChannelConstants.WIRE_TYPE_ANNOUNCE.equals(wireType)
				&& !ChannelConstants.WIRE_TYPE_SUBMIT_POST.equals(wireType)
				&& !ChannelConstants.WIRE_TYPE_CHANGE_POST.equals(wireType)
				&& !ChannelConstants.WIRE_TYPE_GET_ATTACHMENT.equals(
						wireType)) {
			return false;
		}
		try {
			ChannelState s = store.getChannel(channelId);
			return s != null && !s.isPublicChannel()
					&& s.getJoinCapability() != null;
		} catch (DbException e) {
			return true;
		}
	}

	private boolean challengeAccepted(byte[] channelId, byte[] requestBytes) {
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) return false;
			byte[] capability = s.getJoinCapability();
			if (capability == null) return false;
			ChannelPullCodec.Challenge c =
					pullCodec().peekChallenge(requestBytes);
			int version = pullCodec().peekVersion(requestBytes);
			byte[] nonce = c == null ? peekNonce(requestBytes) : c.nonce;
			if (nonce == null) return false;
			return proofAccepted(channelId, capability, requestBytes, nonce,
					c == null ? null : c.hmac, version);
		} catch (Exception e) {
			return false;
		}
	}

	@Nullable
	private byte[] peekNonce(byte[] requestBytes) {
		try {
			ChannelPullCodec.PullRequest asPull = null;
			if (ChannelConstants.WIRE_TYPE_PULL_REQUEST.equals(
					pullCodec().peekType(requestBytes))) {
				asPull = pullCodec().decodePullRequest(requestBytes);
			}
			return asPull == null ? null : asPull.nonce;
		} catch (IOException e) {
			return null;
		}
	}

	private boolean proofAccepted(byte[] channelId, byte[] capability,
			byte[] requestBytes, byte[] nonce, @Nullable byte[] legacyProof,
			int version) {
		if (nonce.length == 0 || nonceSeen(channelId, nonce)) return false;
		boolean ok;
		if (version >= ChannelConstants.PROTOCOL_VERSION_DELTAS) {
			byte[] proof = pullCodec().peekProofV2(requestBytes);
			try {
				ok = proof != null && hmacChallenge().verifyV2(capability,
						nonce, channelId,
						pullCodec().withoutProof(requestBytes), proof);
			} catch (IOException e) {
				ok = false;
			}
		} else {
			ok = legacyProof != null && verifyChallenge(capability, nonce,
					channelId, legacyProof);
		}
		return ok && recordFreshNonce(channelId, nonce);
	}

	private boolean nonceSeen(byte[] channelId, byte[] nonce) {
		java.util.LinkedHashMap<String, Long> ring =
				seenPullNonces.get(ChannelStore.hex(channelId));
		if (ring == null) return false;
		synchronized (ring) {
			return ring.containsKey(ChannelStore.hex(nonce));
		}
	}

	private byte[] proveV2(ChannelState s, byte[] channelId, byte[] request,
			byte[] nonce) throws IOException {
		byte[] capability = s.getJoinCapability();
		if (capability == null) return request;
		byte[] proof = hmacChallenge().respondV2(capability, nonce, channelId,
				pullCodec().withoutProof(request));
		return pullCodec().withProofV2(request, proof);
	}

	private boolean recordFreshNonce(byte[] channelId, byte[] nonce) {
		if (nonce == null || nonce.length == 0) return false;
		String key = ChannelStore.hex(channelId);
		java.util.LinkedHashMap<String, Long> ring =
				seenPullNonces.computeIfAbsent(key,
						k -> new java.util.LinkedHashMap<>());
		String nonceHex = ChannelStore.hex(nonce);
		long now = clock.currentTimeMillis();
		synchronized (ring) {
			java.util.Iterator<java.util.Map.Entry<String, Long>> it =
					ring.entrySet().iterator();
			while (it.hasNext()) {
				java.util.Map.Entry<String, Long> e = it.next();
				if (now - e.getValue() > PULL_NONCE_TTL_MS) {
					it.remove();
				} else {
					break;
				}
			}
			if (ring.containsKey(nonceHex)) return false;
			ring.put(nonceHex, now);
			while (ring.size() > PULL_NONCE_MAX_PER_CHANNEL) {
				java.util.Iterator<java.util.Map.Entry<String, Long>>
						it2 = ring.entrySet().iterator();
				if (it2.hasNext()) {
					it2.next();
					it2.remove();
				} else {
					break;
				}
			}
		}
		return true;
	}

	private boolean verifyChallenge(byte[] capability, byte[] nonce,
			byte[] channelId, byte[] response) {
		return hmacChallenge().verify(capability, nonce, channelId,
				response);
	}

	@Override
	public void bootstrapChannel(byte[] channelId) throws DbException {
		restartRefreshIfStalled();
		resetCandidateBackoffs(channelId);
		pullLinkCandidatesSafely(channelId, false);
		resetPullBackoff(channelId);
		pullAndApply(channelId, true);
		flushOutboxSafely(channelId);
	}

	@Override
	public void refreshChannelReachability(byte[] channelId) {
		try {
			ioExecutor.execute(() -> healPublisherServer(channelId));
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
		}
	}

	@Override
	public void refreshChannel(byte[] channelId) throws DbException {
		restartRefreshIfStalled();
		pullLinkCandidatesSafely(channelId, false);
		pullAndApply(channelId, false);
		flushOutboxSafely(channelId);
	}

	@Override
	public boolean refreshChannelTick(byte[] channelId) throws DbException {
		restartRefreshIfStalled();
		pullLinkCandidatesSafely(channelId, true);
		if (getSyncStatus(channelId).isDormant()) return false;
		pullAndApply(channelId, false);
		flushOutboxSafely(channelId);
		return true;
	}

	@Override
	public void refreshChannelOnOpen(byte[] channelId) throws DbException {
		resetCandidateBackoffs(channelId);
		resetPullBackoff(channelId);
		refreshChannel(channelId);
	}

	static java.util.List<ChannelPost> nextBatch(
			java.util.List<ChannelPost> all, long sinceSeqNum, int max) {
		java.util.List<ChannelPost> after = new java.util.ArrayList<>();
		for (ChannelPost p : all) {
			if (p.getSeqNum() > sinceSeqNum) after.add(p);
		}
		after.sort((a, b) -> Long.compare(a.getSeqNum(), b.getSeqNum()));
		return after.size() > max ? new java.util.ArrayList<>(
				after.subList(0, max)) : after;
	}

	private static final int MAX_PULL_ROUNDS = 64;

	private static final long EMPTY_VERIFY_RUN_MS = 24L * HOUR_MS;

	private void pullAndApply(byte[] channelId, boolean isBootstrap)
			throws DbException {
		boolean reached = false;
		boolean failed = true;
		try {
			for (int round = 0; round < MAX_PULL_ROUNDS; round++) {
				int accepted = pullOnce(channelId, isBootstrap && round == 0);
				reached = true;
				if (accepted < ChannelConstants.PULL_BATCH_MAX_POSTS) break;
			}
			failed = false;
		} finally {
			noteSyncOutcome(channelId, reached, failed);
		}
	}

	private int pullOnce(byte[] channelId, boolean isBootstrap)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (s.weArePublisher()) return 0;
		pollApprovalStatusIfPending(channelId);
		s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		byte[] requestBytes;
		try {
			byte[] capability = s.getJoinCapability();
			requestBytes = pullProtocol.buildRequest(channelId,
					sinceSeqOf(s), capability,
					capability == null ? null : hmacChallenge().freshNonce(),
					reactionStore.sync().cursor(channelId),
					commentStore.sync().cursor(channelId));
		} catch (IOException e) {
			throw new DbException(e);
		}
		byte[] responseBytes;
		String onion = onionToPull(channelId, s);
		try {
			responseBytes = transport.requestFromOnion(onion, requestBytes);
		} catch (IOException e) {
			noteOnionFailure(channelId);
			throw new DbException(e);
		}
		boolean isTombstone =
				ChannelConstants.WIRE_TYPE_CHANNEL_TOMBSTONE.equals(
						pullCodec().peekType(responseBytes));
		boolean signedOnion = s.getManifestSeq() >= 0L
				&& onion.equals(s.getCurrentOnion());
		boolean manifestOnly;
		boolean caughtUp;
		int accepted;
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState cur = store.getChannel(channelId);
			if (cur == null) throw new DbException();
			if (isTombstone) {
				applyTombstoneIfValid(cur, responseBytes);
				return 0;
			}
			ChannelPullProtocol.ProcessResult r =
					pullProtocol.processSubscriberResponse(responseBytes,
							cur, chainTip(cur), cur.getJoinCapability());
			if (!r.ok || r.mergedState == null) {
				throw new DbException();
			}
			onionFailures.remove(ChannelStore.hex(channelId));
			if (cur.getManifestSeq() >= 0L && !r.mergedState
					.getCurrentOnion().equals(cur.getCurrentOnion())) {
				store.onions().remember(channelId, cur.getCurrentOnion());
			}
			store.putChannel(r.mergedState);
			withholdNewlyRevoked(channelId, cur, r.mergedState);
			if (!java.util.Arrays.equals(cur.getContentKey(),
					r.mergedState.getContentKey())) {
				openNewlyReadable(r.mergedState);
			}
			manifestOnly = !signedOnion && !bareOnion(onion).equals(
					bareOnion(r.manifestOnion));
			caughtUp = r.complete;
			accepted = manifestOnly ? 0 : r.acceptedPosts.size();
			if (!manifestOnly) takeReply(channelId, r);
		} finally {
			lock.unlock();
		}
		if (manifestOnly) {
			return s.getManifestSeq() < 0L ? pullOnce(channelId, false) : 0;
		}
		if (caughtUp) repairWaiting(channelId, onion);
		return accepted;
	}

	private void takeReply(byte[] channelId,
			ChannelPullProtocol.ProcessResult r) throws DbException {
		long prunedBefore = store.posts().meta(channelId).pruned;
		for (ChannelPost p : r.acceptedPosts) {
			acceptIncomingPostLocked(channelId, p);
		}
		if (store.posts().meta(channelId).pruned != prunedBefore) {
			java.util.Set<Long> shown = visiblePosts(channelId);
			reactionStore.retainPosts(channelId, shown);
			commentStore.retainPosts(channelId, shown);
		}
		publisherVersions.put(ChannelStore.hex(channelId),
				r.publisherVersion);
		boolean caughtUp = r.acceptedPosts.size()
				< ChannelConstants.PULL_BATCH_MAX_POSTS;
		if (r.boundItems) store.pins().markBoundReplies(channelId);
		boolean takeItems = r.boundItems
				|| !store.pins().boundReplies(channelId);
		boolean reactionsChanged = takeItems && applyIncomingReactions(
				channelId, r.reactions, r.reactionsSync);
		boolean commentsChanged = takeItems && applyIncomingComments(
				channelId, r.comments, r.commentsSync);
		if (caughtUp && takeItems) {
			keepCursor(reactionStore.sync(), channelId, r.reactionsSync);
			keepCursor(commentStore.sync(), channelId, r.commentsSync);
		}
		boolean commentSettingsChanged = false;
		if (ChannelConstants.DISCUSSIONS_IN_MANIFEST) {
			discussionStore.setEnabled(channelId,
					r.discussionsEnabled);
		} else if (r.discussions != null) {
			commentSettingsChanged = discussionStore.takeFromPublisher(
					channelId, r.discussions.enabled,
					r.discussions.exceptions);
		} else {
			commentSettingsChanged = discussionStore.takeFromPublisher(
					channelId, null, Collections.<Long>emptyList());
		}
		if (commentSettingsChanged) {
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		}
		if (!r.acceptedPosts.isEmpty() || reactionsChanged
				|| commentsChanged) {
			PollState poll = polls.get(ChannelStore.hex(channelId));
			if (poll != null) {
				poll.lastActivityAt = clock.currentTimeMillis();
			}
		}
	}

	private static final int MAX_REPAIR_ROUNDS = 4;
	private final java.util.Set<String> waitingChecked =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	private void repairWaiting(byte[] channelId, String onion) {
		try {
			if (store.posts().meta(channelId).pending.isEmpty()) return;
			if (waitingChecked.add(ChannelStore.hex(channelId))) {
				underLock(channelId, () -> {
					ChannelState s = store.getChannel(channelId);
					if (s != null) settleWaiting(s, null);
				});
			}
			for (int round = 0; round < MAX_REPAIR_ROUNDS; round++) {
				if (!repairOnce(channelId, onion)) return;
			}
		} catch (DbException | IOException ignored) {
		}
	}

	private boolean repairOnce(byte[] channelId, String onion)
			throws DbException, IOException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		ChannelState s;
		long first;
		lock.lock();
		try {
			s = store.getChannel(channelId);
			if (s == null || s.weArePublisher()) return false;
			Long due = firstUnexplained(store.posts().meta(channelId));
			if (due == null) return false;
			first = due;
		} finally {
			lock.unlock();
		}
		byte[] capability = s.getJoinCapability();
		byte[] request = pullProtocol.buildRequest(channelId, first - 1L,
				capability,
				capability == null ? null : hmacChallenge().freshNonce(),
				reactionStore.sync().cursor(channelId),
				commentStore.sync().cursor(channelId));
		List<ChannelPost> offered = pullProtocol.postsOf(
				transport.requestFromOnion(onion, request), channelId);
		if (offered == null) return false;
		lock.lock();
		try {
			ChannelState cur = store.getChannel(channelId);
			return cur != null && settleWaiting(cur, offered);
		} finally {
			lock.unlock();
		}
	}

	@Nullable
	private static Long firstUnexplained(ChannelPostStore.Meta m) {
		return m.pending.higher(m.unreadable.highest());
	}

	private boolean settleWaiting(ChannelState s,
			@Nullable List<ChannelPost> offered) throws DbException {
		byte[] channelId = s.getChannelId();
		ChannelPostStore.Meta m = store.posts().meta(channelId);
		java.util.TreeSet<Long> waiting = m.pending;
		if (waiting.isEmpty()) return false;
		long explained = m.unreadable.highest();
		java.util.Map<Long, ChannelPost> bySeq = new java.util.HashMap<>();
		long last = -1L;
		if (offered != null) {
			for (ChannelPost p : offered) {
				bySeq.put(p.getSeqNum(), p);
				last = Math.max(last, p.getSeqNum());
			}
		}
		boolean whole = offered != null
				&& offered.size() < ChannelConstants.PULL_BATCH_MAX_POSTS;
		List<byte[]> keys = contentKeys(s);
		List<Long> unproven = new ArrayList<>();
		boolean changed = false;
		for (Long seq : waiting) {
			if (offered != null
					&& (seq <= explained || (!whole && seq > last))) {
				continue;
			}
			ChannelPost held = store.posts().getPost(channelId, seq);
			if (held == null) continue;
			ChannelPost original = held;
			if (held.isErased()) {
				if (offered == null) continue;
				ChannelPost offer = bySeq.get(seq);
				if (offer == null || offer.isErased()
						|| ChannelPostCeilings.storedBytes(offer)
						> ChannelConstants.MAX_SUBSCRIBER_BYTES_PER_POST
						|| !java.util.Arrays.equals(
						codec.canonicalHashOf(offer),
						codec.canonicalHashOf(held))) {
					unproven.add(seq);
					changed = true;
					continue;
				}
				original = offer;
			}
			boolean readable = s.isPublicChannel()
					|| openedBody(s, keys, original) != null;
			if (!readable
					&& !store.posts().admitsUnreadable(channelId, seq)) {
				unproven.add(seq);
				changed = true;
				continue;
			}
			boolean marker = readable && isMark(s, keys, original);
			if (marker) {
				store.posts().setWithheld(channelId,
						Collections.singletonList(seq));
			}
			boolean shown = !marker && !held.isWithheld();
			boolean announce = held.isErased()
					? store.posts().restore(channelId, original, readable,
							shown)
					: store.posts().prove(channelId, seq, readable, shown);
			if (announce) announceArrival(channelId, seq);
			if (marker) {
				applyDeletionMark(s, original);
				applyEditMark(s, original);
			}
			changed = true;
		}
		if (!unproven.isEmpty()) dropUnproven(channelId, unproven);
		if (changed) {
			store.setUnread(channelId, store.posts().meta(channelId).unread());
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.UNREAD_COUNT_CHANGED);
		}
		return changed;
	}

	private void dropUnproven(byte[] channelId, List<Long> seqs)
			throws DbException {
		List<ChannelPost> gone = store.posts().remove(channelId, seqs, true);
		if (gone.isEmpty()) return;
		for (ChannelPost p : gone) {
			for (ChannelPost.ChannelAttachment a : p.getAttachments()) {
				blobStore.removeBlob(channelId, a.getBlobHash());
			}
		}
		java.util.Set<Long> shown = visiblePosts(channelId);
		reactionStore.retainPosts(channelId, shown);
		commentStore.retainPosts(channelId, shown);
		announceRemoved(channelId, gone);
	}

	private void announceRemoved(byte[] channelId, List<ChannelPost> gone) {
		if (gone.isEmpty()) return;
		List<Long> seqs = new ArrayList<>(gone.size());
		for (ChannelPost p : gone) seqs.add(p.getSeqNum());
		eventBus.broadcast(new ChannelPostsRemovedEvent(channelId, seqs));
	}

	private void openNewlyReadable(ChannelState s) throws DbException {
		byte[] channelId = s.getChannelId();
		ChannelPostStore.Meta m = store.posts().meta(channelId);
		if (m.unreadable.isEmpty()) return;
		List<byte[]> keys = contentKeys(s);
		List<byte[]> hashes = keyHashes(keys);
		List<ChannelPost> marks = new ArrayList<>();
		List<Long> opened = new ArrayList<>();
		for (ChannelPost p : store.getPosts(channelId)) {
			if (!m.unreadable.contains(p.getSeqNum())) continue;
			boolean readable = p.isErased() ? readableUnder(s, hashes, p)
					: s.isPublicChannel() || openedBody(s, keys, p) != null;
			if (!readable) continue;
			if (!p.isErased() && isMark(s, keys, p)) marks.add(p);
			else opened.add(p.getSeqNum());
		}
		if (marks.isEmpty() && opened.isEmpty()) return;
		for (ChannelPost mark : marks) {
			store.posts().setWithheld(channelId,
					Collections.singletonList(mark.getSeqNum()));
			store.posts().opened(channelId, mark.getSeqNum(), false);
			applyDeletionMark(s, mark);
			applyEditMark(s, mark);
		}
		for (Long seq : opened) {
			ChannelPost now = store.posts().getPost(channelId, seq);
			if (now == null) continue;
			if (store.posts().opened(channelId, seq, !now.isWithheld())) {
				announceArrival(channelId, seq);
			}
		}
		store.setUnread(channelId, store.posts().meta(channelId).unread());
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.UNREAD_COUNT_CHANGED);
	}

	private void announceArrival(byte[] channelId, long seq) {
		eventBus.broadcast(new ChannelPostReceivedEvent(channelId, seq,
				false));
	}

	private final java.util.Map<String, long[]> onionFailures =
			new java.util.concurrent.ConcurrentHashMap<>();

	private void noteOnionFailure(byte[] channelId) {
		long[] f = onionFailures.computeIfAbsent(ChannelStore.hex(channelId),
				k -> new long[] {clock.currentTimeMillis(), 0L});
		if (f[0] == 0L) f[0] = clock.currentTimeMillis();
	}

	private String onionToPull(byte[] channelId, ChannelState s)
			throws DbException {
		String current = s.getCurrentOnion();
		long[] f = onionFailures.get(ChannelStore.hex(channelId));
		if (f == null || f[0] == 0L || clock.currentTimeMillis() - f[0]
				< ChannelConstants.ONION_FALLBACK_AFTER_MS) {
			return current;
		}
		java.util.List<String> earlier = store.onions().remembered(channelId);
		earlier.remove(current);
		if (earlier.isEmpty()) return current;
		long attempt = f[1]++;
		if (attempt % 2L == 0L) return current;
		return earlier.get((int) ((attempt / 2L) % earlier.size()));
	}

	private static void keepCursor(ChannelItemSync sync, byte[] channelId,
			@Nullable ChannelPullCodec.ItemSync delta) throws DbException {
		if (delta != null) {
			sync.setCursor(channelId, delta.cursor);
		} else if (sync.cursor(channelId) != null) {
			sync.setCursor(channelId, null);
		}
	}

	private long sinceSeqOf(ChannelState s) throws DbException {
		ChannelChainTip tip = chainTip(s);
		return tip == null ? -1L : tip.seqNum;
	}

	private boolean applyIncomingComments(byte[] channelId,
			java.util.List<ChannelComment> incoming) throws DbException {
		return applyIncomingComments(channelId, incoming, null);
	}

	private boolean applyIncomingComments(byte[] channelId,
			java.util.List<ChannelComment> incoming,
			@Nullable ChannelPullCodec.ItemSync sync)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) return false;
		ChannelAuthorBinding.Session binding = bindings(s);
		java.util.Set<Long> posts = visiblePosts(channelId);
		java.util.List<ChannelComment> stored =
				commentStore.getComments(channelId);
		java.util.Map<Long, ChannelComment> known = new java.util.HashMap<>();
		for (ChannelComment c : stored) known.put(c.getCommentId(), c);
		java.util.LinkedHashMap<Long, ChannelComment> held =
				new java.util.LinkedHashMap<>();
		if (sync != null && !sync.full) {
			for (ChannelComment c : stored) {
				if (posts.contains(c.getParentPostSeqNum())) {
					held.put(c.getCommentId(), c);
				}
			}
			for (String k : sync.removed) {
				try {
					held.remove(Long.parseLong(k));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		java.util.List<Long> added = new ArrayList<>();
		for (ChannelComment c : incoming) {
			if (!posts.contains(c.getParentPostSeqNum())) continue;
			if (!ChannelCommentPolicy.validFields(c)) continue;
			if (subscriberStore.isBanned(channelId,
					c.getAuthorEd25519PubKey())) {
				continue;
			}
			if (!binding.admits(c.getAuthorEd25519PubKey(),
					c.getAuthorMlDsaPubKey())) {
				continue;
			}
			ChannelComment k = known.get(c.getCommentId());
			if (k == null || !ChannelCommentPolicy.sameComment(k, c)) {
				if (!verifiedComment(channelId, c)) continue;
				added.add(c.getParentPostSeqNum());
			}
			binding.bind(c.getAuthorEd25519PubKey(),
					c.getAuthorMlDsaPubKey());
			held.remove(c.getCommentId());
			held.put(c.getCommentId(), c);
		}
		binding.commit(heldAuthors(s));
		java.util.List<ChannelComment> next =
				ChannelCommentPolicy.fitToCeilings(
						new ArrayList<>(held.values()));
		if (ChannelCommentPolicy.sameSet(next, stored)) return false;
		commentStore.setComments(channelId, next);
		ChannelPostStore.Meta unseen = store.posts().meta(channelId);
		for (Long parent : new java.util.LinkedHashSet<>(added)) {
			if (unseen.pending.contains(parent)
					|| unseen.unreadable.contains(parent)) {
				continue;
			}
			eventBus.broadcast(new ChannelCommentReceivedEvent(channelId,
					parent));
		}
		fireEvent(channelId, ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		return true;
	}

	private boolean verifiedComment(byte[] channelId, ChannelComment c)
			throws DbException {
		byte[] sig = c.getSignature();
		if (sig == null || sig.length == 0) return false;
		byte[] signedInput = codec.commentSignedInput(channelId,
				c.getParentPostSeqNum(), c.getCommentId(), c.getBody(),
				c.getAuthorDisplayName(), c.getTimestampHourMs());
		org.zerionproject.core.api.crypto.PublicKey edPub;
		try {
			edPub = crypto.getSignatureKeyParser()
					.parsePublicKey(c.getAuthorEd25519PubKey());
		} catch (GeneralSecurityException ex) {
			return false;
		}
		return signatures.verifyUserComment(sig, signedInput, edPub,
				c.getAuthorMlDsaPubKey())
				&& !subscriberStore.isBanned(channelId,
				c.getAuthorEd25519PubKey());
	}

	private boolean applyTombstoneIfValid(ChannelState s,
			byte[] tombstoneBytes) throws DbException {
		ChannelPullCodec.Tombstone tomb;
		try {
			tomb = pullCodec().decodeTombstone(tombstoneBytes);
		} catch (IOException e) {
			return false;
		}
		if (!java.util.Arrays.equals(tomb.channelId, s.getChannelId())) {
			return false;
		}
		byte[] signedInput = codec.tombstoneSignedInput(
				s.getChannelId(), tomb.timestampHourMs);
		HybridSignaturePublicKey pub;
		try {
			pub = new HybridSignaturePublicKey(s.getPublisherEd25519PubKey(),
					s.getPublisherMlDsaPubKey());
		} catch (RuntimeException e) {
			return false;
		}
		if (!signatures.verifyTombstone(tomb.hybridSig, signedInput, pub)) {
			return false;
		}
		noteOwnerDeleted(s);
		removeChannelLocally(s.getChannelId());
		return true;
	}

	private boolean applyIncomingReactions(byte[] channelId,
			java.util.List<ChannelReaction> incoming) throws DbException {
		return applyIncomingReactions(channelId, incoming, null);
	}

	private boolean applyIncomingReactions(byte[] channelId,
			java.util.List<ChannelReaction> incoming,
			@Nullable ChannelPullCodec.ItemSync sync)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) return false;
		ChannelAuthorBinding.Session binding = bindings(s);
		java.util.Set<Long> posts = visiblePosts(channelId);
		java.util.List<ChannelReaction> stored =
				reactionStore.getReactions(channelId);
		java.util.Map<String, ChannelReaction> known =
				new java.util.HashMap<>();
		for (ChannelReaction r : stored) known.put(reactionKey(r), r);
		java.util.LinkedHashMap<String, ChannelReaction> held =
				new java.util.LinkedHashMap<>();
		if (sync != null && !sync.full) {
			for (ChannelReaction r : stored) {
				if (posts.contains(r.getPostSeqNum())) {
					held.put(reactionKey(r), r);
				}
			}
			for (String k : sync.removed) held.remove(k);
		}
		for (ChannelReaction r : incoming) {
			if (!posts.contains(r.getPostSeqNum())) continue;
			if (subscriberStore.isBanned(channelId,
					r.getSignerEd25519PubKey())) {
				continue;
			}
			if (!binding.admits(r.getSignerEd25519PubKey(),
					r.getSignerMlDsaPubKey())) {
				continue;
			}
			ChannelReaction k = known.get(reactionKey(r));
			if (k == null || !ChannelReactionPolicy.sameReaction(k, r)) {
				if (!verifiedReaction(channelId, r)) continue;
			}
			binding.bind(r.getSignerEd25519PubKey(),
					r.getSignerMlDsaPubKey());
			held.remove(reactionKey(r));
			held.put(reactionKey(r), r);
		}
		binding.commit(heldAuthors(s));
		java.util.List<ChannelReaction> next =
				ChannelReactionPolicy.fitToCeilings(
						new ArrayList<>(held.values()));
		if (ChannelReactionPolicy.sameSet(next, stored)) return false;
		reactionStore.setReactions(channelId, next);
		fireEvent(channelId, ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		return true;
	}

	private static String reactionKey(ChannelReaction r) {
		return ChannelReactionStore.keyOf(r);
	}

	private boolean verifiedReaction(byte[] channelId, ChannelReaction r)
			throws DbException {
		byte[] sig = r.getSignature();
		if (sig == null || sig.length == 0) return false;
		byte[] signedInput = codec.reactionSignedInput(channelId,
				r.getPostSeqNum(), r.getEmoji(), r.getTimestampHourMs());
		org.zerionproject.core.api.crypto.PublicKey edPub;
		try {
			edPub = crypto.getSignatureKeyParser()
					.parsePublicKey(r.getSignerEd25519PubKey());
		} catch (GeneralSecurityException ex) {
			return false;
		}
		return signatures.verifyUserReaction(sig, signedInput, edPub,
				r.getSignerMlDsaPubKey())
				&& !subscriberStore.isBanned(channelId,
				r.getSignerEd25519PubKey());
	}

	private java.util.Set<Long> visiblePosts(byte[] channelId)
			throws DbException {
		ChannelPostStore.Meta m = store.posts().meta(channelId);
		java.util.Set<Long> posts = new java.util.HashSet<>();
		for (Long seq : m.heldSeqs()) {
			if (!m.withheld.contains(seq)) posts.add(seq);
		}
		return posts;
	}

	private ChannelPullCodec pullCodec() {
		return pullCodecInstance != null
				? pullCodecInstance : (pullCodecInstance =
				new ChannelPullCodec(readerFactory, writerFactory));
	}

	private ChannelHmacChallenge hmacChallenge() {
		return hmacChallengeInstance != null
				? hmacChallengeInstance
				: (hmacChallengeInstance =
				new ChannelHmacChallenge(crypto));
	}

	@Inject org.zerionproject.core.api.data.BdfReaderFactory
			readerFactory;
	@Inject org.zerionproject.core.api.data.BdfWriterFactory
			writerFactory;
	private volatile ChannelPullCodec pullCodecInstance;
	private volatile ChannelHmacChallenge hmacChallengeInstance;

	@Nullable
	@Override
	public ChannelState getChannel(byte[] channelId) throws DbException {
		return store.getChannel(channelId);
	}

	@Override
	public Collection<ChannelState> getChannels() throws DbException {
		return store.listChannels();
	}

	@Override
	public void deleteChannel(byte[] channelId) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState s = store.getChannel(channelId);
			byte[] tombstone = s == null || !s.weArePublisher() ? null
					: signedTombstoneOrNull(s);
			if (tombstone != null) {
				keepOnionsForTombstone(s, tombstone);
				removeChannelLocally(channelId, true);
			} else {
				store.markPendingRemoval(channelId, false);
				removeChannelLocally(channelId, false);
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void deleteChannelForEveryone(byte[] channelId)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null || !s.weArePublisher()) throw new DbException();
			byte[] tombstone = signedTombstoneOrNull(s);
			if (tombstone == null) {
				throw new org.zerionproject.app.api.channel
						.ChannelDeleteNotSignedException();
			}
			keepOnionsForTombstone(s, tombstone);
			removeChannelLocally(channelId, true);
		} finally {
			lock.unlock();
		}
	}

	@Nullable
	private byte[] signedTombstoneOrNull(ChannelState s) throws DbException {
		try {
			return signTombstone(s);
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Override
	public boolean hasDeletionNotice(byte[] channelId) throws DbException {
		byte[] tombstone = tombstoneStore.get(channelId);
		return tombstone != null && tombstone.length > 0;
	}

	private void keepOnionsForTombstone(ChannelState s, byte[] tombstone)
			throws DbException {
		byte[] channelId = s.getChannelId();
		long until = clock.currentTimeMillis()
				+ ChannelConstants.DELETED_CHANNEL_GRACE_DAYS * DAY_MS;
		ChannelOnionStore.Record r = store.onions().get(channelId);
		ChannelOnionStore.Record next = new ChannelOnionStore.Record();
		next.deleted = true;
		for (ChannelOnionStore.Retiring e : r.retiring) {
			next.retiring.add(new ChannelOnionStore.Retiring(e.onion,
					e.privateKey, Math.min(e.retireAtMs, until)));
		}
		String onion = s.getCurrentOnion();
		String key = s.getOnionPrivateKey();
		if (key != null && !onion.isEmpty()) {
			next.retiring.add(new ChannelOnionStore.Retiring(onion, key,
					until));
		}
		java.util.Map<String, org.zerionproject.core.api.settings.Settings>
				batch = new java.util.LinkedHashMap<>();
		batch.put(ChannelTombstoneStore.namespace(),
				ChannelTombstoneStore.encode(channelId, tombstone));
		batch.put(ChannelOnionStore.NS, store.onions().encode(channelId,
				next));
		batch.put(ChannelStore.pendingRemovalNamespace(),
				ChannelStore.pendingRemoval(channelId, true));
		store.settings().mergeSettings(batch);
		ChannelTransport.ChannelServer current =
				boundServers.remove(ChannelStore.hex(channelId));
		if (current != null && key != null
				&& onion.equals(current.getOnionAddress())) {
			retiringServers.put(onion, current);
		} else {
			closeQuietly(current);
			retireDueOnions(channelId, clock.currentTimeMillis());
		}
	}

	private void removeChannelLocally(byte[] channelId) throws DbException {
		store.markPendingRemoval(channelId, false);
		removeChannelLocally(channelId, false);
	}

	private void removeChannelLocally(byte[] channelId,
			boolean keepTombstoneOnions) throws DbException {
		String key = ChannelStore.hex(channelId);
		seenPullNonces.remove(key);
		lastApprovalPollMs.remove(key);
		inFlightPulls.remove(key);
		publisherVersions.remove(key);
		legacyPullAt.remove(key);
		polls.remove(key);
		onionFailures.remove(key);
		deniedMarks.remove(key);
		if (!keepTombstoneOnions) {
			closeQuietly(boundServers.remove(key));
			for (ChannelOnionStore.Retiring e
					: store.onions().get(channelId).retiring) {
				closeQuietly(retiringServers.remove(e.onion));
			}
			store.onions().remove(channelId);
			tombstoneStore.remove(channelId);
		}
		store.onions().forgetRemembered(channelId);
		store.removeChannel(channelId);
		blobStore.removeAllForChannel(channelId);
		reactionStore.removeAll(channelId);
		subscriberStore.removeAll(channelId);
		commentStore.removeAll(channelId);
		applicationStore.removeAll(channelId);
		store.pins().removeAll(channelId);
		myApplicationsStore.removeAll(channelId);
		forgetLinkCandidates(channelId);
		postTombstoneStore.removeAll(channelId);
		selfAnnounceStore.remove(channelId);
		discussionStore.remove(channelId);
		syncStatus().remove(channelId);
		outbox().removeAll(channelId);
		store.settings().deleteNamespaces(java.util.Collections.singletonList(
				NS_EDITOR_QUOTA_PREFIX + key));
		store.clearPendingRemoval(channelId);
		fireEvent(channelId, ChannelStateChangedEvent.Kind.LEFT);
	}

	@Nullable
	private byte[] signTombstone(ChannelState s) throws DbException {
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		byte[] signedInput = codec.tombstoneSignedInput(
				s.getChannelId(), ts);
		HybridSignaturePrivateKey priv = publisherKey(s.getChannelId());
		if (priv == null) return null;
		byte[] hybridSig;
		try {
			hybridSig = signatures.signTombstone(signedInput, priv);
		} catch (GeneralSecurityException e) {
			return null;
		} finally {
			priv.clear();
		}
		try {
			return pullCodec().encodeTombstone(s.getChannelId(), ts,
					hybridSig);
		} catch (IOException e) {
			return null;
		}
	}

	@Override
	public String exportInviteLink(byte[] channelId) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (s.weArePublisher()) {
			bindPublisherServer(channelId);
			ChannelState reconciled = store.getChannel(channelId);
			if (reconciled != null) s = reconciled;
			refreshChannelReachability(channelId);
		}
		if (s.getCurrentOnion() == null || s.getCurrentOnion().isEmpty()) {
			throw new DbException();
		}
		return codec.formatInviteLink(s.getChannelId(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(),
				s.isPublicChannel(),
				s.getJoinCapability(),
				s.getCurrentOnion(),
				s.requiresApproval());
	}

	@Nullable
	@Override
	public ChannelInviteLink parseInviteLink(String url) {
		return codec.parseInviteLink(url);
	}

	@Override
	public ChannelState joinChannel(ChannelInviteLink link)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock =
				lockFor(link.getChannelId());
		lock.lock();
		try {
			return joinChannelLocked(link);
		} finally {
			lock.unlock();
		}
	}

	private ChannelState joinChannelLocked(ChannelInviteLink link)
			throws DbException {
		ChannelState existing = store.getChannel(link.getChannelId());
		if (existing != null) return existing;
		byte[] mlDsaPub = link.getPublisherMlDsaPubKey();
		if (mlDsaPub == null) mlDsaPub = new byte[0];
		String onion = link.getOnionAddress();
		if (onion == null) onion = "";
		ChannelState provisional = new ChannelState(
				link.getChannelId(),
				new byte[ChannelConstants.CHANNEL_SALT_BYTES],
				link.getPublisherEd25519PubKey(),
				mlDsaPub,
				"",
				"",
				null,
				clock.currentTimeMillis() / HOUR_MS * HOUR_MS,
				link.isPublicChannel(),
				link.getJoinCapability(),
				onion,
				-1L,
				false,
				-1L,
				null,
				null,
				java.util.Collections.<ChannelDelegationCert>emptyList(),
				java.util.Collections.<Long>emptyList(),
				0L,
				null,
				ChannelState.NO_PINNED_POST,
				link.requiresApproval());
		store.putChannel(provisional);
		store.writePosts(link.getChannelId(), Collections.emptyList());
		fireEvent(link.getChannelId(),
				ChannelStateChangedEvent.Kind.JOINED);
		return provisional;
	}

	@Override
	public ChannelRejoinResult rejoinChannel(ChannelInviteLink link)
			throws DbException {
		byte[] channelId = link.getChannelId();
		ChannelState held = store.getChannel(channelId);
		if (held == null) throw new DbException();
		if (held.weArePublisher()) return ChannelRejoinResult.UNCHANGED;
		if (!sameOwner(held, link) || linkOnion(link) == null) {
			return ChannelRejoinResult.NOT_RESTORED;
		}
		boolean applies = appliesThrough(link);
		byte[] capability = held.getJoinCapability();
		if (applies && capability == null) {
			ChannelMyApplicationsStore.MyApplication my =
					myApplicationsStore.get(channelId);
			if (my != null && my.status == ApplicationStatus.PENDING
					&& my.confirmed) {
				return ChannelRejoinResult.UNCHANGED;
			}
		}
		if (held.getManifestSeq() >= 0L || capability != null) {
			HeldAccess access = heldAccess(channelId);
			if (access == HeldAccess.WORKS) {
				return ChannelRejoinResult.UNCHANGED;
			}
			if (access == HeldAccess.GONE) {
				return ChannelRejoinResult.DELETED;
			}
			if (bringsNothingNew(held, link) && access == HeldAccess.DARK) {
				return ChannelRejoinResult.TRY_LATER;
			}
			if (bringsNothingNew(held, link) && !applies) {
				return ChannelRejoinResult.NOT_RESTORED;
			}
			if (applies && capability != null
					&& access == HeldAccess.DARK) {
				return ChannelRejoinResult.APPLY_AGAIN_UNREACHABLE;
			}
		}
		return applies ? ChannelRejoinResult.APPLY_AGAIN
				: ChannelRejoinResult.CHECK_LINK;
	}

	@Override
	public ChannelRejoinResult rejoinChannelThroughLink(
			ChannelInviteLink link) throws DbException {
		byte[] channelId = link.getChannelId();
		ChannelState held = store.getChannel(channelId);
		if (held == null) throw new DbException();
		if (held.weArePublisher()) return ChannelRejoinResult.UNCHANGED;
		String onion = linkOnion(link);
		if (!sameOwner(held, link) || onion == null
				|| appliesThrough(link)) {
			return ChannelRejoinResult.NOT_RESTORED;
		}
		byte[] linkCapability = link.getJoinCapability();
		String heldOnion = held.getCurrentOnion();
		if (bringsNothingNew(held, link)) {
			switch (heldAccess(channelId)) {
				case WORKS:
					return ChannelRejoinResult.UNCHANGED;
				case GONE:
					return ChannelRejoinResult.DELETED;
				case DARK:
					return ChannelRejoinResult.TRY_LATER;
				default:
					return ChannelRejoinResult.NOT_RESTORED;
			}
		}
		if (held.getManifestSeq() >= 0L) {
			byte[] capability = linkCapability != null
					? linkCapability : held.getJoinCapability();
			byte[] reply = null;
			try {
				reply = transport.requestFromOnion(heldOnion,
						pullRequest(channelId, capability, sinceSeqOf(held),
								true));
			} catch (IOException ignored) {
			}
			if (reply != null && ChannelConstants.WIRE_TYPE_CHANNEL_TOMBSTONE
					.equals(pullCodec().peekType(reply))) {
				boolean[] gone = new boolean[1];
				byte[] tombstone = reply;
				underLock(channelId, () -> {
					ChannelState cur = store.getChannel(channelId);
					gone[0] = cur == null
							|| applyTombstoneIfValid(cur, tombstone);
				});
				if (gone[0]) return ChannelRejoinResult.DELETED;
			} else if (reply != null) {
				return adoptManifest(held, capability, reply, heldOnion,
						true);
			}
		}
		ChannelLinkCandidates.Candidate c = linkCandidates().add(channelId,
				random.nextLong(), onion, linkCapability, false);
		return c == null ? ChannelRejoinResult.TOO_MANY_LINKS
				: ChannelRejoinResult.TRYING;
	}

	private static boolean appliesThrough(ChannelInviteLink link) {
		return link.getJoinCapability() == null && link.requiresApproval();
	}

	private static boolean bringsNothingNew(ChannelState held,
			ChannelInviteLink link) {
		String onion = linkOnion(link);
		return onion != null
				&& onion.equals(bareOnion(held.getCurrentOnion()))
				&& (appliesThrough(link) || java.util.Arrays.equals(
				link.getJoinCapability(), held.getJoinCapability()));
	}

	@Nullable
	private static String linkOnion(ChannelInviteLink link) {
		String given = link.getOnionAddress();
		if (given == null) return null;
		String onion = bareOnion(given);
		return BARE_ONION_V3.matcher(onion).matches() ? onion : null;
	}

	private static boolean sameOwner(ChannelState held,
			ChannelInviteLink link) {
		if (held.weArePublisher() || !java.util.Arrays.equals(
				held.getPublisherEd25519PubKey(),
				link.getPublisherEd25519PubKey())) {
			return false;
		}
		byte[] linkMlDsa = link.getPublisherMlDsaPubKey();
		byte[] heldMlDsa = held.getPublisherMlDsaPubKey();
		return linkMlDsa == null || linkMlDsa.length == 0
				|| heldMlDsa == null || heldMlDsa.length == 0
				|| java.util.Arrays.equals(linkMlDsa, heldMlDsa);
	}

	private enum HeldAccess { WORKS, DARK, REFUSED, GONE }

	private HeldAccess heldAccess(byte[] channelId) throws DbException {
		try {
			refreshChannel(channelId);
		} catch (DbException e) {
			if (store.getChannel(channelId) == null) return HeldAccess.GONE;
			return e.getCause() instanceof IOException ? HeldAccess.DARK
					: HeldAccess.REFUSED;
		}
		return store.getChannel(channelId) == null ? HeldAccess.GONE
				: HeldAccess.WORKS;
	}

	private byte[] pullRequest(byte[] channelId, @Nullable byte[] capability,
			long sinceSeqNum, boolean asMember) throws DbException {
		try {
			return pullProtocol.buildRequest(channelId, sinceSeqNum,
					capability,
					capability == null ? null : hmacChallenge().freshNonce(),
					asMember ? reactionStore.sync().cursor(channelId) : null,
					asMember ? commentStore.sync().cursor(channelId) : null);
		} catch (IOException e) {
			throw new DbException(e);
		}
	}

	private enum CandidateOutcome { ADOPTED, RESOLVED, REACHED, FAILED, GONE }

	private volatile ChannelLinkCandidates linkCandidatesInstance;

	private ChannelLinkCandidates linkCandidates() {
		ChannelLinkCandidates c = linkCandidatesInstance;
		if (c != null) return c;
		synchronized (syncStatusLock) {
			if (linkCandidatesInstance == null) {
				linkCandidatesInstance = new ChannelLinkCandidates(
						store.settings(), store.readerFactory(),
						store.writerFactory());
			}
			return linkCandidatesInstance;
		}
	}

	private static String candidateKey(String channelHex, long id) {
		return channelHex + "#" + id;
	}

	private void forgetLinkCandidates(byte[] channelId) throws DbException {
		String hex = ChannelStore.hex(channelId);
		for (ChannelLinkCandidates.Candidate c
				: linkCandidates().list(channelId)) {
			forgetCandidatePoll(candidateKey(hex, c.id));
		}
		linkCandidates().removeAll(channelId);
	}

	private void forgetCandidatePoll(String key) {
		polls.remove(key);
		lastApprovalPollMs.remove(key);
	}

	private void resetCandidateBackoffs(byte[] channelId) {
		try {
			String hex = ChannelStore.hex(channelId);
			for (ChannelLinkCandidates.Candidate c
					: linkCandidates().list(channelId)) {
				PollState poll = polls.get(candidateKey(hex, c.id));
				if (poll == null) continue;
				synchronized (poll) {
					if (poll.failures == 0) continue;
					poll.failures = 0;
					poll.intervalMs = PULL_MIN_INTERVAL_MS;
				}
			}
		} catch (DbException | RuntimeException ignored) {
		}
	}

	private void pullLinkCandidatesSafely(byte[] channelId,
			boolean unlessResting) {
		long now = clock.currentTimeMillis();
		try {
			for (ChannelLinkCandidates.Candidate c
					: linkCandidates().list(channelId)) {
				if (unlessResting && now >= c.dormantAt()) continue;
				pullCandidate(channelId, c.id);
			}
		} catch (DbException | RuntimeException ignored) {
		}
	}

	private long scheduleLinkCandidates(long now, long nextWake) {
		long wake = nextWake;
		List<String> channels;
		try {
			channels = linkCandidates().channels();
		} catch (DbException e) {
			return wake;
		}
		for (String hex : channels) {
			byte[] channelId = ChannelOnionStore.unhex(hex);
			if (channelId == null) continue;
			List<ChannelLinkCandidates.Candidate> all;
			try {
				ChannelState held = store.getChannel(channelId);
				if (held == null || held.weArePublisher()) continue;
				all = linkCandidates().list(channelId);
			} catch (DbException e) {
				continue;
			}
			for (ChannelLinkCandidates.Candidate c : all) {
				String key = candidateKey(hex, c.id);
				PollState poll = polls.computeIfAbsent(key, k -> {
					PollState fresh = new PollState();
					fresh.nextDueAt = now;
					return fresh;
				});
				if (now < poll.nextDueAt) {
					wake = Math.min(wake, poll.nextDueAt);
					continue;
				}
				if (now >= c.dormantAt()) {
					synchronized (poll) {
						poll.resting = true;
						poll.nextDueAt = now + PULL_FAILURE_MAX_INTERVAL_MS;
					}
					wake = Math.min(wake, poll.nextDueAt);
					continue;
				}
				pullCandidateDue(channelId, c.id, key);
				wake = Math.min(wake, planNextPull(poll, now));
			}
		}
		return wake;
	}

	private void pullCandidateDue(byte[] channelId, long id, String key) {
		if (!inFlightPulls.add(key)) return;
		try {
			ioExecutor.execute(() -> {
				try {
					pullCandidate(channelId, id);
				} finally {
					inFlightPulls.remove(key);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException re) {
			inFlightPulls.remove(key);
		}
	}

	private CandidateOutcome pullCandidate(byte[] channelId, long id) {
		String key = candidateKey(ChannelStore.hex(channelId), id);
		try {
			ChannelState held = store.getChannel(channelId);
			ChannelLinkCandidates.Candidate c =
					linkCandidates().get(channelId, id);
			if (held == null || held.weArePublisher() || c == null) {
				return CandidateOutcome.GONE;
			}
			if (c.applied) {
				checkCandidateApproval(channelId, c, key);
				c = linkCandidates().get(channelId, id);
				if (c == null) return CandidateOutcome.GONE;
			}
			byte[] reply;
			try {
				reply = transport.requestFromOnion(c.onion,
						pullRequest(channelId, c.capability, -1L, false));
			} catch (IOException dark) {
				return candidateFailed(channelId, c, key, false, false);
			}
			CandidateOutcome outcome = takeCandidateReply(channelId, c,
					reply);
			if (outcome == CandidateOutcome.FAILED) {
				return candidateFailed(channelId, c, key, reply.length > 0,
						reply.length == 0);
			}
			if (outcome == CandidateOutcome.REACHED) {
				candidateReached(channelId, c, key);
			} else {
				forgetCandidatePoll(key);
			}
			return outcome;
		} catch (DbException | RuntimeException e) {
			return CandidateOutcome.FAILED;
		}
	}

	private void candidateReached(byte[] channelId,
			ChannelLinkCandidates.Candidate c, String key)
			throws DbException {
		long now = clock.currentTimeMillis();
		underLock(channelId, () -> {
			ChannelLinkCandidates.Candidate cur =
					linkCandidates().get(channelId, c.id);
			if (cur != null && !cur.reachedChangesNothing(now)) {
				linkCandidates().update(channelId, cur.reached(now));
			}
			notePullOutcome(key, true, false);
		});
	}

	private CandidateOutcome candidateFailed(byte[] channelId,
			ChannelLinkCandidates.Candidate c, String key, boolean refused,
			boolean empty) throws DbException {
		long now = clock.currentTimeMillis();
		underLock(channelId, () -> {
			ChannelLinkCandidates.Candidate cur =
					linkCandidates().get(channelId, c.id);
			if (cur == null) return;
			ChannelLinkCandidates.Candidate next = cur.failedAt(now);
			if (cur.approvedFor != null && (refused || (empty
					&& emptyRunOver(channelId, cur.approvedFor, now)))) {
				forgetApplicationFor(channelId, cur.approvedFor);
				next = next.withoutApproval();
			}
			if (next != cur) linkCandidates().update(channelId, next);
			notePullOutcome(key, false, true);
		});
		return CandidateOutcome.FAILED;
	}

	private boolean emptyRunOver(byte[] channelId, byte[] ephPub, long now)
			throws DbException {
		ChannelMyApplicationsStore.MyApplication held =
				myApplicationsStore.get(channelId);
		if (held == null || held.status != ApplicationStatus.PENDING
				|| !held.confirmed || !java.util.Arrays.equals(
				held.ephemeralAgreementPub, ephPub)) {
			return false;
		}
		long start = held.emptySinceMs;
		if (start < 0L || (now < start && !held.emptyClockReset)) {
			myApplicationsStore.put(channelId, held.emptySince(now,
					start >= 0L || held.emptyClockReset));
			return false;
		}
		return now - start >= EMPTY_VERIFY_RUN_MS;
	}

	private void endEmptyRun(byte[] channelId, byte[] ephPub)
			throws DbException {
		ChannelMyApplicationsStore.MyApplication held =
				myApplicationsStore.get(channelId);
		if (held != null && held.emptySinceMs >= 0L && java.util.Arrays.equals(
				held.ephemeralAgreementPub, ephPub)) {
			myApplicationsStore.put(channelId, held.emptySince(-1L, false));
		}
	}

	private void forgetApplicationFor(byte[] channelId, byte[] ephPub)
			throws DbException {
		ChannelMyApplicationsStore.MyApplication held =
				myApplicationsStore.get(channelId);
		if (held != null && held.status == ApplicationStatus.PENDING
				&& java.util.Arrays.equals(held.ephemeralAgreementPub,
				ephPub)) {
			forgetApplication(channelId);
		}
	}

	private CandidateOutcome takeCandidateReply(byte[] channelId,
			ChannelLinkCandidates.Candidate c, byte[] reply)
			throws DbException {
		boolean tombstone = ChannelConstants.WIRE_TYPE_CHANNEL_TOMBSTONE
				.equals(pullCodec().peekType(reply));
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState cur = store.getChannel(channelId);
			if (cur == null || cur.weArePublisher()) {
				return CandidateOutcome.GONE;
			}
			if (tombstone) {
				return applyTombstoneIfValid(cur, reply)
						? CandidateOutcome.GONE : CandidateOutcome.REACHED;
			}
			ChannelPullProtocol.ProcessResult r =
					pullProtocol.processSubscriberResponse(reply,
							asNewcomer(cur, c.capability), chainTip(cur),
							c.capability);
			ChannelState next = r.mergedState;
			if (!r.ok || next == null || !opensWithNamedKey(next)) {
				return CandidateOutcome.FAILED;
			}
			linkCandidates().remove(channelId, c.id);
			if (next.getManifestSeq() <= cur.getManifestSeq()) {
				if (c.approvedFor != null) endEmptyRun(channelId, c.approvedFor);
				return CandidateOutcome.RESOLVED;
			}
			storeAdopted(channelId, cur, next);
			if (c.approvedFor != null) {
				markApprovedIfPending(channelId, c.approvedFor);
			}
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			return CandidateOutcome.ADOPTED;
		} finally {
			lock.unlock();
		}
	}

	@Nullable
	private String confirmCandidate(ChannelState held,
			ChannelLinkCandidates.Candidate c) throws DbException {
		byte[] channelId = held.getChannelId();
		ChannelPullCodec.OnionConfirmed conf;
		try {
			conf = pullCodec().decodeOnionConfirmed(transport.requestFromOnion(
					c.onion, pullCodec().encodeConfirmOnionRequest(channelId,
							bareOnion(c.onion))));
		} catch (IOException e) {
			return null;
		}
		byte[] heldMlDsa = held.getPublisherMlDsaPubKey();
		if (!java.util.Arrays.equals(conf.channelId, channelId)
				|| !BARE_ONION_V3.matcher(conf.onion).matches()
				|| (heldMlDsa != null && heldMlDsa.length > 0
				&& !java.util.Arrays.equals(heldMlDsa, conf.publisherMlDsa))) {
			return null;
		}
		HybridSignaturePublicKey pub;
		try {
			pub = new HybridSignaturePublicKey(
					held.getPublisherEd25519PubKey(), conf.publisherMlDsa);
		} catch (RuntimeException e) {
			return null;
		}
		if (!java.util.Arrays.equals(channelId, crypto.hash(
				"org.zerionproject/CHANNEL_ID", pub.getEncoded(), conf.salt))
				|| !signatures.verifyOnionConfirmation(conf.signature,
				codec.onionConfirmationSignedInput(channelId, conf.onion),
				pub)) {
			return null;
		}
		if (!conf.onion.equals(bareOnion(c.onion))) {
			ChannelLinkCandidates.Candidate now =
					linkCandidates().get(channelId, c.id);
			if (now != null && now.onion.equals(c.onion)) {
				linkCandidates().update(channelId, now.withOnion(conf.onion));
			}
		}
		return conf.onion;
	}

	private void checkCandidateApproval(byte[] channelId,
			ChannelLinkCandidates.Candidate c, String key)
			throws DbException {
		ChannelMyApplicationsStore.MyApplication my =
				myApplicationsStore.get(channelId);
		long now = clock.currentTimeMillis();
		Long last = lastApprovalPollMs.get(key);
		if (last != null && now - last < APPROVAL_POLL_MIN_INTERVAL_MS) {
			return;
		}
		lastApprovalPollMs.put(key, now);
		ChannelState held = store.getChannel(channelId);
		if (held == null) return;
		String onion = confirmCandidate(held, c);
		if (onion == null) return;
		byte[] request;
		try {
			long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
			byte[] signedInput =
					codec.checkApprovalSignedInput(channelId, ts);
			KeyPair keys = memberKeys(channelId);
			HybridSignaturePublicKey pub =
					(HybridSignaturePublicKey) keys.getPublic();
			HybridSignaturePrivateKey priv =
					(HybridSignaturePrivateKey) keys.getPrivate();
			byte[] sig = signAndClear(priv, (ed, ml) ->
					signatures.signUserCheckApproval(signedInput, ed, ml));
			request = pullCodec().encodeCheckApprovalRequest(channelId, ts,
					pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), sig);
		} catch (GeneralSecurityException | IOException e) {
			return;
		}
		ChannelPullCodec.ApprovalResponse resp;
		try {
			resp = pullCodec().decodeApprovalResponse(
					transport.requestFromOnion(onion, request));
		} catch (IOException e) {
			return;
		}
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelLinkCandidates.Candidate cur =
					linkCandidates().get(channelId, c.id);
			if (cur == null || !cur.applied
					|| !bareOnion(cur.onion).equals(bareOnion(onion))) {
				return;
			}
			ChannelMyApplicationsStore.MyApplication record =
					myApplicationsStore.get(channelId);
			boolean pending = record != null && my != null
					&& record.status == ApplicationStatus.PENDING
					&& record.confirmed && java.util.Arrays.equals(
					record.ephemeralAgreementPub, my.ephemeralAgreementPub);
			if ("APPROVED".equals(resp.status)
					&& resp.kemCt != null && resp.envelope != null) {
				byte[] capability = pending ? openApproval(channelId, record,
						resp.kemCt, resp.envelope) : null;
				byte[] fromApproval = pending && capability != null
						? record.ephemeralAgreementPub : null;
				if (!pending) {
					ChannelState after = store.getChannel(channelId);
					capability = after == null ? null
							: after.getJoinCapability();
				}
				linkCandidates().update(channelId, capability == null
						? cur.applied(false)
						: cur.withCapability(capability, fromApproval));
			} else if ("DENIED".equals(resp.status)) {
				if (pending) {
					myApplicationsStore.put(channelId,
							new ChannelMyApplicationsStore.MyApplication(
									record.displayName, null,
									record.ephemeralAgreementPub,
									record.appliedAtHourMs,
									ApplicationStatus.DENIED,
									record.signedWithMemberKey));
					fireEvent(channelId,
							ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
				}
				linkCandidates().update(channelId, cur.applied(false));
			} else if ("UNKNOWN".equals(resp.status)) {
				if (pending) forgetApplication(channelId);
				linkCandidates().update(channelId, cur.applied(false));
			}
		} finally {
			lock.unlock();
		}
	}

	private static ChannelState asNewcomer(ChannelState s,
			@Nullable byte[] capability) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(), s.getPublisherMlDsaPubKey(),
				s.getName(), s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(), capability,
				s.getCurrentOnion(), -1L, s.weArePublisher(),
				s.getHighestKnownPostSeq(), s.getContentKeyHash(),
				s.getContentKey(), s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(), s.getNextDelegationSeq(),
				s.getOnionPrivateKey(), s.getPinnedPostSeq(),
				s.requiresApproval(), s.getRetiredDelegations());
	}

	private void storeAdopted(byte[] channelId, ChannelState cur,
			ChannelState next) throws DbException {
		byte[] heldKey = cur.getContentKey();
		boolean newKey = !java.util.Arrays.equals(heldKey,
				next.getContentKey());
		if (cur.getManifestSeq() >= 0L && !next.getCurrentOnion()
				.equals(cur.getCurrentOnion())) {
			store.onions().remember(channelId, cur.getCurrentOnion());
		}
		store.putChannel(next, heldKey == null || !newKey
				|| isRetired(channelId, heldKey)
				? java.util.Collections.<String,
						org.zerionproject.core.api.settings.Settings>
						emptyMap()
				: store.retireContentKey(channelId, heldKey));
		onionFailures.remove(ChannelStore.hex(channelId));
		withholdNewlyRevoked(channelId, cur, next);
		if (newKey) openNewlyReadable(next);
	}

	private ChannelRejoinResult adoptManifest(ChannelState before,
			@Nullable byte[] capability, byte[] reply, String dialled,
			boolean asMember) throws DbException {
		byte[] channelId = before.getChannelId();
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState cur = store.getChannel(channelId);
			if (cur == null || cur.weArePublisher()
					|| !java.util.Arrays.equals(cur.getJoinCapability(),
					before.getJoinCapability())
					|| !cur.getCurrentOnion().equals(before.getCurrentOnion())
					|| cur.getManifestSeq() != before.getManifestSeq()) {
				return ChannelRejoinResult.UNCHANGED;
			}
			ChannelPullProtocol.ProcessResult r =
					pullProtocol.processSubscriberResponse(reply,
							withCapability(cur, capability), chainTip(cur),
							capability);
			ChannelState next = r.mergedState;
			if (!r.ok || next == null) {
				return ChannelRejoinResult.NOT_RESTORED;
			}
			if (next.getManifestSeq() <= cur.getManifestSeq()) {
				return java.util.Arrays.equals(capability,
						cur.getJoinCapability())
						? ChannelRejoinResult.UNCHANGED
						: ChannelRejoinResult.NOT_RESTORED;
			}
			if (!opensWithNamedKey(next)) {
				return ChannelRejoinResult.NOT_RESTORED;
			}
			storeAdopted(channelId, cur, next);
			boolean signed = cur.getManifestSeq() >= 0L
					&& dialled.equals(cur.getCurrentOnion());
			if (asMember && (signed || bareOnion(dialled).equals(
					bareOnion(r.manifestOnion)))) {
				takeReply(channelId, r);
			}
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			return ChannelRejoinResult.UPDATED;
		} finally {
			lock.unlock();
		}
	}

	private boolean isRetired(byte[] channelId, byte[] key)
			throws DbException {
		for (byte[] k : store.getRetiredContentKeys(channelId)) {
			if (java.util.Arrays.equals(k, key)) return true;
		}
		return false;
	}

	private boolean opensWithNamedKey(ChannelState s) {
		if (s.isPublicChannel()) return true;
		byte[] key = s.getContentKey();
		byte[] named = s.getContentKeyHash();
		return key != null && named != null && java.util.Arrays.equals(
				named, contentKey.hashContentKey(key));
	}

	private static ChannelState withCapability(ChannelState s,
			@Nullable byte[] capability) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(), s.getPublisherMlDsaPubKey(),
				s.getName(), s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(), capability,
				s.getCurrentOnion(), s.getManifestSeq(), s.weArePublisher(),
				s.getHighestKnownPostSeq(), s.getContentKeyHash(),
				s.getContentKey(), s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(), s.getNextDelegationSeq(),
				s.getOnionPrivateKey(), s.getPinnedPostSeq(),
				s.requiresApproval(), s.getRetiredDelegations());
	}

	@Override
	public void leaveChannel(byte[] channelId) throws DbException {
		deleteChannel(channelId);
	}

	@Override
	public void publishPost(byte[] channelId, String body, long ttlSeconds)
			throws DbException {
		publishPostWithSeq(channelId, body, ttlSeconds);
	}

	@Override
	public long publishPostWithSeq(byte[] channelId, String body,
			long ttlSeconds) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) {
			return submitAsEditor(channelId, body, ttlSeconds);
		}
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			return publishPostLocked(channelId, body, ttlSeconds);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public long getPostsGivenUp(byte[] channelId) throws DbException {
		ChannelPostStore.Meta m = store.posts().meta(channelId);
		return m.pruned + m.skipped;
	}

	@Override
	public boolean canPost(byte[] channelId) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) return false;
		if (s.weArePublisher()) return true;
		return editorCertificate(s) != null;
	}

	@Nullable
	private ChannelDelegationCert editorCertificate(ChannelState s)
			throws DbException {
		if (s.getActiveDelegations().isEmpty()) return null;
		byte[] mine = publicKeyOf(memberKeys(s.getChannelId()))
				.getEd25519PublicKey();
		long nowHourMs = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		for (ChannelDelegationCert c : s.getActiveDelegations()) {
			if (!java.util.Arrays.equals(c.getDelegateeEd25519PubKey(), mine)
					|| !c.coversTimestamp(nowHourMs)
					|| s.getRevokedDelegationSeqs().contains(
					c.getDelegationSeq())) {
				continue;
			}
			return c;
		}
		return null;
	}

	private static final int MAX_SUBMIT_ATTEMPTS = 3;

	private long submitAsEditor(byte[] channelId, String body,
			long ttlSeconds) throws DbException {
		validatePostBody(body);
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (editorCertificate(s) == null) {
			throw new org.zerionproject.app.api.channel
					.ChannelRefusedException();
		}
		checkEditorBodyFits(s, channelId, body);
		ChannelChainTip tip = chainTip(s);
		return sendOrQueue(channelId, outboxEntry(random.nextLong(),
				ChannelQueuedItem.Kind.POST, -1L, body, ttlSeconds, false,
				tip == null ? -1L : tip.seqNum), false);
	}

	private byte[] handleSubmitPost(byte[] channelId, byte[] requestBytes) {
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null || !s.weArePublisher()) return new byte[0];
			ChannelPullCodec.SubmitPostRequest req =
					pullCodec().decodeSubmitPostRequest(requestBytes);
			ChannelPost post = req.post;
			ChannelChainTip tip = chainTip(s);
			long tipSeq = tip == null ? -1L : tip.seqNum;
			long nowHourMs = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
			byte[] editor = post.getDelegateSignerEd25519PubKey();
			if (!java.util.Arrays.equals(req.channelId, channelId)
					|| !post.signedByDelegate() || editor == null
					|| post.getFormatVersion() != ChannelPost.FORMAT_V2
					|| !post.getAttachments().isEmpty()
					|| post.getTimestampHourMs() > nowHourMs
					|| post.getTimestampHourMs() < nowHourMs - HOUR_MS
					|| subscriberStore.isBanned(channelId, editor)
					|| looksLikeDeletionMark(s, post)) {
				return submitAck(ChannelConstants.SUBMIT_STATUS_REFUSED,
						tipSeq);
			}
			if (validator.validateChain(post, tip, false)
					!= ChannelPostValidator.Result.OK) {
				return submitAck(ChannelConstants.SUBMIT_STATUS_STALE,
						tipSeq);
			}
			if (validator.validate(s, post, tip, false)
					!= ChannelPostValidator.Result.OK) {
				return submitAck(ChannelConstants.SUBMIT_STATUS_REFUSED,
						tipSeq);
			}
			long now = clock.currentTimeMillis();
			String budgetKey = "p:" + ChannelStore.hex(channelId) + ":"
					+ ChannelStore.hex(editor);
			ChannelPost stored = post.withFlags(true, false);
			long bytes = ChannelPostCeilings.storedBytes(stored);
			if (!knownWriteBudget.hasRoom(budgetKey, now)
					|| store.posts().meta(channelId).delegateBytes + bytes
					> editorPostBytesCap) {
				return submitAck(ChannelConstants.SUBMIT_STATUS_REFUSED,
						tipSeq);
			}
			org.zerionproject.core.api.settings.Settings quota =
					editorQuotaAfterPost(channelId, editor, now);
			if (quota == null) {
				return submitAck(ChannelConstants.SUBMIT_STATUS_REFUSED,
						tipSeq);
			}
			java.util.Map<String, org.zerionproject.core.api.settings
					.Settings> also = new java.util.LinkedHashMap<>();
			also.put(NS_EDITOR_QUOTA_PREFIX + ChannelStore.hex(channelId),
					quota);
			store.posts().append(channelId, stored,
					chainVerifier.hashOf(stored), also);
			store.putChannel(withSeq(s, post.getSeqNum()));
			knownWriteBudget.spend(budgetKey, bytes, now);
			eventBus.broadcast(new ChannelPostReceivedEvent(channelId,
					post.getSeqNum(), true));
			return submitAck(ChannelConstants.SUBMIT_STATUS_OK,
					post.getSeqNum());
		} catch (IOException | DbException e) {
			return new byte[0];
		}
	}

	private boolean looksLikeDeletionMark(ChannelState s, ChannelPost post) {
		String body = post.getBody();
		if (!s.isPublicChannel()) {
			byte[] kContent = s.getContentKey();
			if (kContent == null) return true;
			try {
				body = contentKey.decryptBodyOf(kContent, post,
						java.util.Base64.getDecoder().decode(body));
			} catch (GeneralSecurityException | IllegalArgumentException e) {
				return true;
			}
		}
		return ChannelPostMarkers.hasReservedShape(body);
	}

	@Nullable
	private org.zerionproject.core.api.settings.Settings editorQuotaAfterPost(
			byte[] channelId, byte[] editor, long now) throws DbException {
		String ns = NS_EDITOR_QUOTA_PREFIX + ChannelStore.hex(channelId);
		String key = ChannelStore.hex(editor);
		long hour = now / HOUR_MS;
		long day = now / DAY_MS;
		long hourCount = 0L;
		long dayCount = 0L;
		String stored = store.settings().getSetting(ns, key);
		if (stored != null) {
			String[] parts = stored.split(":");
			try {
				if (parts.length == 4) {
					if (Long.parseLong(parts[0]) == hour) {
						hourCount = Long.parseLong(parts[1]);
					}
					if (Long.parseLong(parts[2]) == day) {
						dayCount = Long.parseLong(parts[3]);
					}
				}
			} catch (NumberFormatException ignored) {
				hourCount = 0L;
				dayCount = 0L;
			}
		}
		if (hourCount >= ChannelConstants.MAX_EDITOR_POSTS_PER_HOUR
				|| dayCount >= ChannelConstants.MAX_EDITOR_POSTS_PER_DAY) {
			return null;
		}
		org.zerionproject.core.api.settings.Settings out =
				new org.zerionproject.core.api.settings.Settings();
		out.put(key, hour + ":" + (hourCount + 1L) + ":" + day + ":"
				+ (dayCount + 1L));
		return out;
	}

	private byte[] submitAck(String status, long tipSeq) {
		try {
			return pullCodec().encodeSubmitPostAck(status, tipSeq);
		} catch (IOException e) {
			return new byte[0];
		}
	}

	private long publishPostLocked(byte[] channelId, String body,
			long ttlSeconds) throws DbException {
		return publishPostLocked(channelId, body, ttlSeconds,
				Collections.<ChannelPost.ChannelAttachment>emptyList(),
				Collections.<String, byte[]>emptyMap());
	}

	private long publishPostLocked(byte[] channelId, String body,
			long ttlSeconds,
			List<ChannelPost.ChannelAttachment> attachments,
			java.util.Map<String, byte[]> blobsToStore)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		validatePostBody(body);
		HybridSignaturePrivateKey hybridPriv = publisherKey(channelId);
		if (hybridPriv == null) throw new DbException();
		ChannelPost post;
		try {
			post = buildPost(s, channelId, body, ttlSeconds, attachments,
					hybridPriv, null, null);
		} finally {
			hybridPriv.clear();
		}
		long nextSeq = post.getSeqNum();
		store.posts().append(channelId, post, chainVerifier.hashOf(post));
		for (java.util.Map.Entry<String, byte[]> entry
				: blobsToStore.entrySet()) {
			try {
				blobStore.put(channelId,
						java.util.Base64.getDecoder().decode(entry.getKey()),
						entry.getValue());
			} catch (IOException ignored) {
			}
		}
		ChannelState updated = withSeq(s, nextSeq);
		boolean recorded = true;
		try {
			store.putChannel(updated);
		} catch (DbException e) {
			recorded = false;
		}
		try {
			applyEditMark(updated, post);
		} catch (DbException e) {
			recorded = false;
		}
		eventBus.broadcast(new ChannelPostReceivedEvent(channelId, nextSeq,
				true));
		if (!recorded) {
			throw new org.zerionproject.app.api.channel
					.ChannelSentNotSavedException(nextSeq);
		}
		return nextSeq;
	}

	@Nullable
	ChannelChainTip chainTip(ChannelState s) throws DbException {
		byte[] channelId = s.getChannelId();
		ChannelChainTip tip = store.posts().tip(channelId);
		long remembered = s.getHighestKnownPostSeq();
		if (tip == null || tip.seqNum < remembered) {
			if (remembered < 0) return tip;
			ChannelPost held = store.posts().getPost(channelId, remembered);
			return new ChannelChainTip(remembered,
					held == null ? null : chainVerifier.hashOf(held));
		}
		if (tip.hash == null) {
			ChannelPost held = store.posts().getPost(channelId, tip.seqNum);
			if (held != null) {
				return new ChannelChainTip(tip.seqNum,
						chainVerifier.hashOf(held));
			}
		}
		return tip;
	}

	private ChannelPost buildPost(ChannelState s, byte[] channelId,
			String body, long ttlSeconds,
			List<ChannelPost.ChannelAttachment> attachments,
			HybridSignaturePrivateKey signer,
			@Nullable byte[] delegateEd, @Nullable byte[] delegateMl)
			throws DbException {
		ChannelChainTip tip = chainTip(s);
		long nextSeq = tip == null ? 0L : tip.seqNum + 1L;
		byte[] prevHash = tip == null || tip.hash == null
				? new byte[ChannelConstants.PREV_HASH_BYTES] : tip.hash;
		long nowHourMs =
				clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		long ttlMs = Math.max(0L, ttlSeconds) * 1000L;
		byte[] salt = freshBytes(ChannelConstants.POST_SALT_BYTES);
		String wireBody = body;
		if (!s.isPublicChannel()) {
			byte[] kContent = s.getContentKey();
			if (kContent == null) throw new DbException();
			try {
				byte[] ct = contentKey.encryptBodyV2(kContent, channelId,
						nextSeq, salt, body);
				wireBody = java.util.Base64.getEncoder()
						.withoutPadding().encodeToString(ct);
			} catch (GeneralSecurityException ex) {
				throw new DbException(ex);
			}
			if (wireBody.length() > ChannelConstants.MAX_POST_BODY_CHARS) {
				throw new org.zerionproject.app.api.channel
						.ChannelPostTooLongException();
			}
		}
		byte[] signedInput = codec.postSignedInputV2(channelId, nextSeq,
				prevHash, nowHourMs, ttlMs, salt,
				codec.bodyHashV2(salt, wireBody),
				codec.attachmentsHashV2(salt, attachments));
		byte[] sig;
		try {
			sig = signatures.signPostV2(signedInput, signer);
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		return new ChannelPost(channelId, nextSeq, prevHash, nowHourMs,
				wireBody, attachments, ttlMs, sig, delegateEd == null,
				delegateEd, delegateMl, false, ChannelPost.FORMAT_V2, salt);
	}

	private static final long MARKS_LOOKED_PAST = 16L;

	@Override
	public List<ChannelPost> getRecentPosts(byte[] channelId, long limit)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		long wanted = Math.max(0L, Math.min(limit, Integer.MAX_VALUE));
		List<ChannelPost> all = store.posts().getLatestPosts(channelId,
				(int) Math.min(Integer.MAX_VALUE, wanted + MARKS_LOOKED_PAST));
		List<byte[]> keys = s == null ? Collections.<byte[]>emptyList()
				: contentKeys(s);
		boolean encrypted = s != null && !s.isPublicChannel();
		List<byte[]> keyHashes = keyHashes(keys);
		java.util.Set<Long> pending = store.posts().meta(channelId).pending;

		String channelIdHex = ChannelStore.hex(channelId);
		java.util.Set<Long> deletedSeqs = new java.util.HashSet<>(
				postTombstoneStore.get(channelId));
		List<ChannelPost> decoded = new ArrayList<>(all.size());
		List<Long> newlyDeleted = new ArrayList<>();
		java.util.Map<Long, ChannelPost> bySeq = new java.util.HashMap<>();
		java.util.Map<Long, String> editedText = new java.util.HashMap<>();
		List<ChannelPost> unappliedEdits = new ArrayList<>();
		for (ChannelPost p : all) {
			ChannelPost view = p.isErased()
					? (s == null || readableUnder(s, keyHashes, p) ? p : null)
					: encrypted ? decryptForDisplay(p, keys) : p;
			if (view == null) continue;
			decoded.add(view);
			bySeq.put(view.getSeqNum(), view);
			if (view.signedByDelegate()) continue;
			Long target = parseTombstoneTarget(view.getBody(),
					channelIdHex);
			if (target != null && deletedSeqs.add(target)) {
				newlyDeleted.add(target);
			}
			ChannelPostMarkers.Edit edit =
					ChannelPostMarkers.edit(view.getBody(), channelIdHex);
			ChannelPost edited = edit == null ? null : bySeq.get(edit.target);
			if (edited != null && edit.target < view.getSeqNum()
					&& edited.isInEditWindowAt(view.getTimestampHourMs())) {
				boolean earlier = editedText.containsKey(edit.target);
				editedText.put(edit.target, edit.text);
				boolean erasable = edited.getFormatVersion()
						== ChannelPost.FORMAT_V2 && (!edited.isErased()
						|| pending.contains(edit.target));
				if (earlier || erasable) unappliedEdits.add(p);
			}
		}
		if (!newlyDeleted.isEmpty() || !unappliedEdits.isEmpty()) {
			try {
				underLock(channelId, () -> {
					ChannelState current = store.getChannel(channelId);
					if (current == null) return;
					for (Long target : newlyDeleted) {
						postTombstoneStore.add(channelId, target);
					}
					for (ChannelPost mark : unappliedEdits) {
						applyEditMark(current, mark);
					}
				});
			} catch (DbException ignored) {
			}
		}

		List<ChannelPost> visible = new ArrayList<>(decoded.size());
		for (ChannelPost p : decoded) {
			if (!p.signedByDelegate() && ChannelPostMarkers.isMarker(
					p.getBody(), channelIdHex)) {
				continue;
			}
			String text = editedText.get(p.getSeqNum());
			if (deletedSeqs.contains(p.getSeqNum())) {
				visible.add(withDeletedMarker(p));
			} else if (p.isErased() && text == null) {
				continue;
			} else if (text != null && !p.isWithheld()) {
				visible.add(p.asEdited(text));
			} else {
				visible.add(p);
			}
		}
		if (visible.size() <= limit) {
			return visible;
		}
		return new ArrayList<>(visible.subList(
				(int) (visible.size() - limit), visible.size()));
	}

	@Nullable
	private Long parseTombstoneTarget(String body, String channelIdHex) {
		return ChannelPostMarkers.deletionTarget(body, channelIdHex);
	}

	private ChannelPost withDeletedMarker(ChannelPost p) {
		return p.asShown(ChannelConstants.DELETED_POST_PLACEHOLDER,
				Collections.<ChannelPost.ChannelAttachment>emptyList());
	}

	@Nullable
	private ChannelPost decryptForDisplay(ChannelPost p, List<byte[]> keys) {
		byte[] ct;
		try {
			ct = java.util.Base64.getDecoder().decode(p.getBody());
		} catch (IllegalArgumentException ex) {
			return p;
		}
		String body = decryptWithAnyKey(keys, p, ct);
		if (body != null) return p.asShown(body, p.getAttachments());
		return looksLikeCiphertext(p.getBody(), ct) ? null : p;
	}

	private List<byte[]> keyHashes(List<byte[]> keys) {
		List<byte[]> out = new ArrayList<>(keys.size());
		for (byte[] k : keys) out.add(contentKey.hashContentKey(k));
		return out;
	}

	private static boolean readableUnder(ChannelState s,
			List<byte[]> keyHashes, ChannelPost erased) {
		if (s.isPublicChannel()) return true;
		byte[] under = erased.getReadableUnder();
		if (under == null) return s.weArePublisher();
		if (under.length == 0) return true;
		for (byte[] h : keyHashes) {
			if (java.util.Arrays.equals(h, under)) return true;
		}
		return false;
	}

	private static boolean servable(ChannelState s, List<byte[]> currentKey,
			boolean keyNeverRenewed, ChannelPost erased) {
		if (s.isPublicChannel()) return true;
		byte[] under = erased.getReadableUnder();
		if (under == null) return keyNeverRenewed;
		return readableUnder(s, currentKey, erased);
	}

	@Nullable
	private byte[] readKeyOf(ChannelState s, List<byte[]> keys,
			ChannelPost p) {
		if (s.isPublicChannel()) return null;
		byte[] ct;
		try {
			ct = java.util.Base64.getDecoder().decode(p.getBody());
		} catch (IllegalArgumentException ex) {
			return new byte[0];
		}
		for (byte[] k : keys) {
			try {
				contentKey.decryptBodyOf(k, p, ct);
				return contentKey.hashContentKey(k);
			} catch (GeneralSecurityException
					| IllegalArgumentException ignored) {
			}
		}
		return looksLikeCiphertext(p.getBody(), ct) ? null : new byte[0];
	}

	private static boolean looksLikeCiphertext(String encoded, byte[] decoded) {
		return decoded.length >= ChannelContentKey.MIN_CIPHERTEXT_BYTES
				&& java.util.Base64.getEncoder().withoutPadding()
					.encodeToString(decoded).equals(encoded);
	}

	private List<byte[]> contentKeys(ChannelState s) throws DbException {
		List<byte[]> keys = new ArrayList<>();
		byte[] current = s.getContentKey();
		if (s.isPublicChannel() || current == null) return keys;
		keys.add(current);
		for (byte[] k : store.getRetiredContentKeys(s.getChannelId())) {
			if (!java.util.Arrays.equals(k, current)) keys.add(k);
		}
		return keys;
	}

	@Nullable
	private String decryptWithAnyKey(List<byte[]> keys, ChannelPost p,
			byte[] ct) {
		for (byte[] k : keys) {
			try {
				return contentKey.decryptBodyOf(k, p, ct);
			} catch (GeneralSecurityException
					| IllegalArgumentException ignored) {
			}
		}
		return null;
	}

	@Nullable
	private byte[] unwrapWithAnyKey(List<byte[]> keys, byte[] channelId,
			byte[] wrapped) {
		for (byte[] k : keys) {
			try {
				return contentKey.unwrapContentKey(k, channelId, wrapped);
			} catch (GeneralSecurityException ignored) {
			}
		}
		return null;
	}

	@Override
	public int getUnreadCount(byte[] channelId) throws DbException {
		return store.getUnread(channelId);
	}

	@Override
	public void markChannelRead(byte[] channelId) throws DbException {
		if (store.getUnread(channelId) == 0) return;
		store.setUnread(channelId, 0);
		store.posts().markAllRead(channelId);
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.UNREAD_COUNT_CHANGED);
	}

	@Override
	public boolean isMirrorOptedIn(byte[] channelId) throws DbException {
		return store.isMirrorOptedIn(channelId);
	}

	@Override
	public void setMirrorOptedIn(byte[] channelId, boolean mirror)
			throws DbException {
		store.setMirrorOptedIn(channelId, mirror);
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.MIRROR_OPT_IN_TOGGLED);
	}

	@Override
	public void rotateJoinCapability(byte[] channelId) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			rotateJoinCapabilityLocked(channelId);
		} finally {
			lock.unlock();
		}
	}

	private void rotateJoinCapabilityLocked(byte[] channelId)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		if (s.isPublicChannel()) throw new DbException();
		byte[] newCap = freshBytes(
				ChannelConstants.JOIN_CAPABILITY_BYTES);
		byte[] newContentKey = contentKey.generateContentKey();
		byte[] newContentKeyHash =
				contentKey.hashContentKey(newContentKey);
		ChannelState updated = new ChannelState(s.getChannelId(),
				s.getSalt(), s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				newCap, s.getCurrentOnion(), s.getManifestSeq() + 1L,
				true, s.getHighestKnownPostSeq(),
				newContentKeyHash, newContentKey,
				s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(),
				s.getOnionPrivateKey(),
				s.getPinnedPostSeq(),
				s.requiresApproval(),
				s.getRetiredDelegations());
		byte[] oldContentKey = s.getContentKey();
		java.util.Map<String, org.zerionproject.core.api.settings.Settings>
				alsoWrite = new java.util.LinkedHashMap<>();
		if (oldContentKey != null) {
			alsoWrite.putAll(store.retireContentKey(channelId, oldContentKey));
		}
		byte[] oldCap = s.getJoinCapability();
		alsoWrite.putAll(applicationStore.legacyApprovalsRevoked(channelId,
				approvalMark(oldCap == null ? new byte[0] : oldCap)));
		revokeOnionsLocked(channelId, updated, alsoWrite);
	}

	@Override
	public ChannelDelegationCert delegatePublisher(byte[] channelId,
			byte[] delegateeEd25519PubKey, byte[] delegateeMlDsaPubKey,
			long validUntilHourMs) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			return delegatePublisherLocked(channelId,
					delegateeEd25519PubKey, delegateeMlDsaPubKey,
					validUntilHourMs);
		} finally {
			lock.unlock();
		}
	}

	private ChannelDelegationCert delegatePublisherLocked(byte[] channelId,
			byte[] delegateeEd25519PubKey, byte[] delegateeMlDsaPubKey,
			long validUntilHourMs) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		if (s.getActiveDelegations().size()
				>= ChannelConstants.MAX_ACTIVE_DELEGATIONS_PER_CHANNEL) {
			throw new DbException();
		}
		ChannelAuthorBinding.Session binding = bindings(s);
		if (!binding.admits(delegateeEd25519PubKey, delegateeMlDsaPubKey)) {
			throw new DbException();
		}
		long validFrom = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		long seq = s.getNextDelegationSeq();
		byte[] signedInput = codec.delegationSignedInput(channelId,
				delegateeEd25519PubKey, delegateeMlDsaPubKey,
				validFrom, validUntilHourMs, seq);
		HybridSignaturePrivateKey hybridPriv = publisherKey(channelId);
		if (hybridPriv == null) throw new DbException();
		byte[] sig;
		try {
			sig = signatures.signDelegation(signedInput, hybridPriv);
		} catch (java.security.GeneralSecurityException ex) {
			throw new DbException(ex);
		} finally {
			hybridPriv.clear();
		}
		ChannelDelegationCert cert = new ChannelDelegationCert(channelId,
				delegateeEd25519PubKey, delegateeMlDsaPubKey,
				validFrom, validUntilHourMs, seq, sig);
		java.util.List<ChannelDelegationCert> next =
				new java.util.ArrayList<>(s.getActiveDelegations());
		next.add(cert);
		ChannelState updated = withDelegations(s, next,
				s.getRevokedDelegationSeqs(), seq + 1L);
		binding.bind(delegateeEd25519PubKey, delegateeMlDsaPubKey);
		binding.commit(heldAuthors(s));
		store.putChannel(updated);
		fireEvent(channelId,
				org.zerionproject.app.api.channel.event
						.ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		return cert;
	}

	@Override
	public void revokeDelegation(byte[] channelId, long delegationSeq)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			revokeDelegationLocked(channelId, delegationSeq);
		} finally {
			lock.unlock();
		}
	}

	private void revokeDelegationLocked(byte[] channelId,
			long delegationSeq) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		java.util.List<ChannelDelegationCert> remaining =
				new java.util.ArrayList<>();
		boolean removed = false;
		for (ChannelDelegationCert c : s.getActiveDelegations()) {
			if (c.getDelegationSeq() == delegationSeq) {
				removed = true;
				continue;
			}
			remaining.add(c);
		}
		if (!removed) return;
		java.util.List<Long> revoked =
				new java.util.ArrayList<>(s.getRevokedDelegationSeqs());
		revoked.add(delegationSeq);
		ChannelState updated = withDelegations(s, remaining, revoked,
				s.getNextDelegationSeq());
		store.putChannel(updated);
		withholdRevoked(channelId, updated);
		fireEvent(channelId,
				org.zerionproject.app.api.channel.event
						.ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
	}

	@Override
	public java.util.List<ChannelDelegationCert> listActiveDelegations(
			byte[] channelId) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) return java.util.Collections.emptyList();
		return s.getActiveDelegations();
	}

	@Override
	public void pinPost(byte[] channelId, long seqNum) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			setPinnedPostSeqLocked(channelId, seqNum);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void unpinPost(byte[] channelId) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			setPinnedPostSeqLocked(channelId,
					ChannelState.NO_PINNED_POST);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void deletePost(byte[] channelId, long seqNum)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) {
			changeAsEditor(channelId, seqNum, ChannelConstants.CHANGE_DELETE,
					"", false);
			return;
		}
		underLock(channelId, () -> deletePostLocked(channelId, seqNum));
	}

	private void deletePostLocked(byte[] channelId, long seqNum)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		ChannelPost target = store.posts().getPost(channelId, seqNum);
		if (target != null && isMark(s, contentKeys(s), target)) {
			throw new DbException();
		}
		List<ChannelPost> edits = target == null
				? Collections.<ChannelPost>emptyList() : editsOf(s, target);
		boolean autoUnpin = s.getPinnedPostSeq() == seqNum;
		postTombstoneStore.add(channelId, seqNum);
		org.zerionproject.app.api.channel.ChannelSentNotSavedException
				unrecorded = null;
		try {
			publishPostLocked(channelId,
					ChannelPostMarkers.deletion(channelId, seqNum), 0L);
		} catch (org.zerionproject.app.api.channel
				.ChannelSentNotSavedException published) {
			unrecorded = published;
		}
		dropDeletedPost(channelId, seqNum, edits);
		if (autoUnpin) {
			setPinnedPostSeqLocked(channelId,
					ChannelState.NO_PINNED_POST);
		}
		if (unrecorded != null) throw unrecorded;
	}

	private void dropDeletedPost(byte[] channelId, long target,
			List<ChannelPost> edits) throws DbException {
		List<Long> seqs = new ArrayList<>();
		seqs.add(target);
		for (ChannelPost e : edits) seqs.add(e.getSeqNum());
		List<ChannelPost> gone = store.posts().remove(channelId, seqs, false);
		if (gone.isEmpty()) return;
		for (ChannelPost p : gone) {
			for (ChannelPost.ChannelAttachment a : p.getAttachments()) {
				blobStore.removeBlob(channelId, a.getBlobHash());
			}
		}
		java.util.Set<Long> held = visiblePosts(channelId);
		reactionStore.retainPosts(channelId, held);
		commentStore.retainPosts(channelId, held);
		store.setUnread(channelId, store.posts().meta(channelId).unread());
		announceRemoved(channelId, gone);
	}

	private void applyDeletionMark(ChannelState s, ChannelPost post)
			throws DbException {
		if (post.signedByDelegate()) return;
		String body = openedBody(s, contentKeys(s), post);
		if (body == null) return;
		Long target = parseTombstoneTarget(body,
				ChannelStore.hex(s.getChannelId()));
		if (target == null || target >= post.getSeqNum()) return;
		ChannelPost held = store.posts().getPost(s.getChannelId(), target);
		List<ChannelPost> edits = held == null
				? Collections.<ChannelPost>emptyList() : editsOf(s, held);
		postTombstoneStore.add(s.getChannelId(), target);
		dropDeletedPost(s.getChannelId(), target, edits);
	}

	private void applyEditMark(ChannelState s, ChannelPost post)
			throws DbException {
		List<byte[]> keys = contentKeys(s);
		ChannelPostMarkers.Edit edit = editOf(s, keys, post);
		if (edit == null || edit.target >= post.getSeqNum()) return;
		byte[] channelId = s.getChannelId();
		ChannelPost target = store.posts().getPost(channelId, edit.target);
		if (target == null || isMark(s, keys, target)
				|| !target.isInEditWindowAt(post.getTimestampHourMs())) {
			return;
		}
		List<Long> superseded = new ArrayList<>();
		for (ChannelPost earlier : editsOf(s, target)) {
			if (earlier.getSeqNum() < post.getSeqNum()) {
				superseded.add(earlier.getSeqNum());
			}
		}
		if (!superseded.isEmpty()) {
			announceRemoved(channelId,
					store.posts().remove(channelId, superseded, false));
		}
		if (target.getFormatVersion() != ChannelPost.FORMAT_V2) return;
		boolean readable = s.isPublicChannel() || (target.isErased()
				? readableUnder(s, keyHashes(keys), target)
				: openedBody(s, keys, target) != null);
		long seq = target.getSeqNum();
		if (!readable && store.posts().meta(channelId).pending.contains(seq)
				&& !store.posts().admitsUnreadable(channelId, seq)) {
			dropUnproven(channelId, Collections.singletonList(seq));
			return;
		}
		if (!target.isErased() && store.posts().erase(channelId, seq,
				codec.bodyHashOf(target), readKeyOf(s, keys, target))) {
			eventBus.broadcast(new PurgeRequestedEvent());
		}
		if (store.posts().prove(channelId, seq, readable,
				!target.isWithheld())) {
			store.setUnread(channelId,
					store.posts().meta(channelId).unread());
			announceArrival(channelId, seq);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.UNREAD_COUNT_CHANGED);
		}
	}

	@Nullable
	private String openedBody(ChannelState s, List<byte[]> keys,
			ChannelPost post) {
		if (s.isPublicChannel()) return post.getBody();
		if (keys.isEmpty()) return null;
		try {
			return decryptWithAnyKey(keys, post,
					java.util.Base64.getDecoder().decode(post.getBody()));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	@Nullable
	private ChannelPostMarkers.Edit editOf(ChannelState s, List<byte[]> keys,
			ChannelPost post) {
		if (post.signedByDelegate()) return null;
		String body = openedBody(s, keys, post);
		return body == null ? null
				: ChannelPostMarkers.edit(body, ChannelStore.hex(
						s.getChannelId()));
	}

	private boolean isMark(ChannelState s, List<byte[]> keys,
			ChannelPost post) {
		if (post.signedByDelegate()) return false;
		String body = openedBody(s, keys, post);
		return body != null && ChannelPostMarkers.isMarker(body,
				ChannelStore.hex(s.getChannelId()));
	}

	private List<ChannelPost> editsOf(ChannelState s, ChannelPost target)
			throws DbException {
		byte[] channelId = s.getChannelId();
		List<byte[]> keys = contentKeys(s);
		List<ChannelPost> out = new ArrayList<>();
		long after = target.getSeqNum();
		int page = (int) ChannelConstants.PULL_BATCH_MAX_POSTS;
		while (true) {
			List<ChannelPost> next =
					store.posts().getPostsAfter(channelId, after, page);
			if (next.isEmpty()) return out;
			for (ChannelPost p : next) {
				after = p.getSeqNum();
				if (!target.isInEditWindowAt(p.getTimestampHourMs())) {
					return out;
				}
				ChannelPostMarkers.Edit edit = editOf(s, keys, p);
				if (edit != null && edit.target == target.getSeqNum()) {
					out.add(p);
				}
			}
		}
	}

	private long currentVersionOf(ChannelState s, ChannelPost target)
			throws DbException {
		long head = target.getSeqNum();
		for (ChannelPost e : editsOf(s, target)) {
			head = Math.max(head, e.getSeqNum());
		}
		return head;
	}

	private ChannelPost changeTarget(ChannelState s, long seqNum)
			throws DbException {
		ChannelPost target = store.posts().getPost(s.getChannelId(), seqNum);
		if (target == null || target.isWithheld()
				|| postTombstoneStore.get(s.getChannelId()).contains(seqNum)
				|| isMark(s, contentKeys(s), target)) {
			throw new DbException();
		}
		return target;
	}

	@Override
	public void editPost(byte[] channelId, long seqNum, String body)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		String text = body.trim();
		if (text.isEmpty()) throw new DbException();
		if (!s.weArePublisher()) {
			changeAsEditor(channelId, seqNum, ChannelConstants.CHANGE_EDIT,
					text, false);
			return;
		}
		underLock(channelId, () -> editPostLocked(channelId, seqNum, text));
	}

	private void editPostLocked(byte[] channelId, long seqNum, String text)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		ChannelPost target = changeTarget(s, seqNum);
		long now = clock.currentTimeMillis();
		if (!target.isEditableAt(now)) throw new DbException();
		String body = ChannelPostMarkers.edit(channelId, seqNum, text);
		if (body.length() > ChannelConstants.MAX_POST_BODY_CHARS) {
			throw new org.zerionproject.app.api.channel
					.ChannelPostTooLongException();
		}
		long ttlSeconds = 0L;
		if (target.getTtlMs() > 0L) {
			long left = target.getTimestampHourMs() + target.getTtlMs()
					- now / HOUR_MS * HOUR_MS;
			if (left <= 0L) throw new DbException();
			ttlSeconds = Math.max(1L, left / 1000L);
		}
		publishPostLocked(channelId, body, ttlSeconds);
	}

	@Override
	public boolean areCommentsEnabled(byte[] channelId, long postSeqNum)
			throws DbException {
		return discussionStore.isEnabledFor(channelId, postSeqNum);
	}

	@Override
	public java.util.Set<Long> getCommentExceptions(byte[] channelId)
			throws DbException {
		return discussionStore.exceptions(channelId);
	}

	@Override
	public void setPostCommentsEnabled(byte[] channelId, long postSeqNum,
			boolean enabled) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) {
			changeAsEditor(channelId, postSeqNum,
					ChannelConstants.CHANGE_COMMENTS, "", enabled);
			return;
		}
		underLock(channelId, () ->
				setPostCommentsLocked(channelId, postSeqNum, enabled));
	}

	private void setPostCommentsLocked(byte[] channelId, long postSeqNum,
			boolean enabled) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		changeTarget(s, postSeqNum);
		if (!discussionStore.setPost(channelId, postSeqNum, enabled,
				visiblePosts(channelId))) {
			throw new DbException();
		}
		fireEvent(channelId, ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
	}

	private void changeAsEditor(byte[] channelId, long seqNum, String op,
			String body, boolean commentsOn) throws DbException {
		if (ChannelConstants.CHANGE_EDIT.equals(op)) {
			String marker = ChannelPostMarkers.edit(channelId, seqNum, body);
			if (marker.length() > ChannelConstants.MAX_POST_BODY_CHARS) {
				throw new org.zerionproject.app.api.channel
						.ChannelPostTooLongException();
			}
		}
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (editorCertificate(s) == null) throw new DbException();
		ChannelPost target = changeTarget(s, seqNum);
		if (!ownDelegatePost(channelId, target)) throw new DbException();
		ChannelQueuedItem.Kind kind = changeKind(op);
		sendOrQueue(channelId, outboxEntry(random.nextLong(), kind, seqNum,
				body, 0L, commentsOn, -1L),
				kind != ChannelQueuedItem.Kind.DELETE);
	}

	private byte[] handleChangePost(byte[] channelId, byte[] requestBytes) {
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null || !s.weArePublisher()) return new byte[0];
			ChannelPullCodec.ChangePostRequest req =
					pullCodec().decodeChangePostRequest(requestBytes);
			long now = clock.currentTimeMillis();
			long nowHourMs = now / HOUR_MS * HOUR_MS;
			byte[] editor = req.signerEd25519;
			if (!java.util.Arrays.equals(req.channelId, channelId)
					|| !ChannelAuthorBinding.wellFormed(editor,
					req.signerMlDsa, req.signature)
					|| req.timestampHourMs > nowHourMs
					|| req.timestampHourMs < nowHourMs - HOUR_MS
					|| subscriberStore.isBanned(channelId, editor)) {
				return changeAck(ChannelConstants.SUBMIT_STATUS_REFUSED);
			}
			ChannelDelegationCert cert = activeCertificate(s, editor,
					nowHourMs);
			if (cert == null || !java.util.Arrays.equals(
					cert.getDelegateeMlDsaPubKey(), req.signerMlDsa)) {
				return changeAck(ChannelConstants.SUBMIT_STATUS_REFUSED);
			}
			byte[] signedInput = codec.postChangeSignedInput(channelId,
					req.targetSeqNum, req.op, req.base, req.commentsOn,
					req.body, req.timestampHourMs);
			org.zerionproject.core.api.crypto.PublicKey edPub;
			try {
				edPub = crypto.getSignatureKeyParser().parsePublicKey(editor);
			} catch (GeneralSecurityException e) {
				return changeAck(ChannelConstants.SUBMIT_STATUS_REFUSED);
			}
			if (!signatures.verifyUserPostChange(req.signature, signedInput,
					edPub, req.signerMlDsa)) {
				return changeAck(ChannelConstants.SUBMIT_STATUS_REFUSED);
			}
			ChannelPost target;
			try {
				target = changeTarget(s, req.targetSeqNum);
			} catch (DbException e) {
				return changeAck(ChannelConstants.SUBMIT_STATUS_REFUSED);
			}
			if (!target.signedByDelegate() || !java.util.Arrays.equals(
					target.getDelegateSignerEd25519PubKey(), editor)) {
				return changeAck(ChannelConstants.SUBMIT_STATUS_REFUSED);
			}
			boolean edit = ChannelConstants.CHANGE_EDIT.equals(req.op);
			if (!edit && !ChannelConstants.CHANGE_DELETE.equals(req.op)
					&& !ChannelConstants.CHANGE_COMMENTS.equals(req.op)) {
				return changeAck(ChannelConstants.SUBMIT_STATUS_REFUSED);
			}
			if (edit && (!target.isEditableAt(now)
					|| req.body.trim().isEmpty()
					|| ChannelPostMarkers.edit(channelId, req.targetSeqNum,
					req.body.trim()).length()
					> ChannelConstants.MAX_POST_BODY_CHARS)) {
				return changeAck(ChannelConstants.SUBMIT_STATUS_REFUSED);
			}
			if (edit && req.base != currentVersionOf(s, target)) {
				return changeAck(ChannelConstants.SUBMIT_STATUS_STALE);
			}
			String budgetKey = "p:" + ChannelStore.hex(channelId) + ":"
					+ ChannelStore.hex(editor);
			long bytes = 4096L + 4L * req.body.length();
			if (!knownWriteBudget.hasRoom(budgetKey, now)) {
				return changeAck(ChannelConstants.SUBMIT_STATUS_REFUSED);
			}
			org.zerionproject.core.api.settings.Settings quota =
					editorQuotaAfterPost(channelId, editor, now);
			if (quota == null) {
				return changeAck(ChannelConstants.SUBMIT_STATUS_REFUSED);
			}
			store.settings().mergeSettings(quota,
					NS_EDITOR_QUOTA_PREFIX + ChannelStore.hex(channelId));
			knownWriteBudget.spend(budgetKey, bytes, now);
			if (edit) {
				editPostLocked(channelId, req.targetSeqNum, req.body.trim());
			} else if (ChannelConstants.CHANGE_DELETE.equals(req.op)) {
				deletePostLocked(channelId, req.targetSeqNum);
			} else {
				setPostCommentsLocked(channelId, req.targetSeqNum,
						req.commentsOn);
			}
			return changeAck(ChannelConstants.SUBMIT_STATUS_OK);
		} catch (IOException | DbException e) {
			return new byte[0];
		}
	}

	@Nullable
	private ChannelDelegationCert activeCertificate(ChannelState s,
			byte[] editor, long nowHourMs) {
		for (ChannelDelegationCert c : s.getActiveDelegations()) {
			if (java.util.Arrays.equals(c.getDelegateeEd25519PubKey(), editor)
					&& c.coversTimestamp(nowHourMs)
					&& !s.getRevokedDelegationSeqs().contains(
					c.getDelegationSeq())) {
				return c;
			}
		}
		return null;
	}

	private byte[] changeAck(String status) {
		try {
			return pullCodec().encodeChangePostAck(status);
		} catch (IOException e) {
			return new byte[0];
		}
	}

	private static final class SealedAttachment {

		final byte[] blobHash;
		final long sizeBytes;
		final String mimeType;
		final byte[] perAttachmentKey;
		@Nullable
		final String caption;
		@Nullable
		final byte[] thumbnail;

		SealedAttachment(byte[] blobHash, long sizeBytes, String mimeType,
				byte[] perAttachmentKey, @Nullable String caption,
				@Nullable byte[] thumbnail) {
			this.blobHash = blobHash;
			this.sizeBytes = sizeBytes;
			this.mimeType = mimeType;
			this.perAttachmentKey = perAttachmentKey;
			this.caption = caption;
			this.thumbnail = thumbnail;
		}
	}

	@Override
	public void publishPostWithAttachments(byte[] channelId, String body,
			long ttlSeconds,
			java.util.List<org.zerionproject.app.api.channel
					.AttachmentSpec> attachments) throws DbException {
		if (attachments.size()
				> ChannelConstants.MAX_ATTACHMENTS_PER_POST) {
			throw new DbException();
		}
		java.util.List<SealedAttachment> sealed =
				new ArrayList<>(attachments.size());
		java.util.Map<String, byte[]> blobsToStore =
				new java.util.LinkedHashMap<>();
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		if (!s.isPublicChannel() && s.getContentKey() == null) {
			throw new DbException();
		}
		for (org.zerionproject.app.api.channel.AttachmentSpec spec
				: attachments) {
			if (spec.getPlaintextBytes().length
					> ChannelConstants.MAX_ATTACHMENT_BYTES) {
				throw new DbException();
			}
			byte[] perAttKey = contentKey.generateAttachmentKey();
			byte[] encryptedBlob;
			try {
				encryptedBlob = contentKey.encryptBlob(perAttKey,
						channelId, spec.getMimeType(),
						spec.getPlaintextBytes().length,
						spec.getPlaintextBytes());
			} catch (GeneralSecurityException ex) {
				throw new DbException(ex);
			}
			byte[] blobHash = crypto.hash(
					"org.zerionproject/CHANNEL_ATTACHMENT_BLOB",
					encryptedBlob);
			String caption = spec.getCaptionUtf8();
			if (caption != null && caption.getBytes(
					java.nio.charset.StandardCharsets.UTF_8).length
					> ChannelConstants.MAX_ATTACHMENT_CAPTION_BYTES) {
				throw new DbException();
			}
			byte[] thumbWire = null;
			byte[] thumbPlain = spec.getPlaintextThumbnail();
			if (thumbPlain != null && thumbPlain.length
					> ChannelConstants.MAX_ATTACHMENT_THUMBNAIL_BYTES) {
				thumbPlain = null;
			}
			if (thumbPlain != null) {
				try {
					thumbWire = contentKey.encryptBlob(perAttKey,
							channelId, "image/jpeg",
							thumbPlain.length, thumbPlain);
				} catch (GeneralSecurityException ignored) {
					thumbWire = null;
				}
			}
			sealed.add(new SealedAttachment(blobHash,
					spec.getPlaintextBytes().length, spec.getMimeType(),
					perAttKey, caption, thumbWire));
			blobsToStore.put(
					java.util.Base64.getEncoder().withoutPadding()
							.encodeToString(blobHash),
					encryptedBlob);
		}
		underLock(channelId, () -> {
			ChannelState current = store.getChannel(channelId);
			if (current == null || !current.weArePublisher()) {
				throw new DbException();
			}
			boolean closed = !current.isPublicChannel();
			byte[] kContent = current.getContentKey();
			if (closed && kContent == null) throw new DbException();
			java.util.List<ChannelPost.ChannelAttachment> wireAttachments =
					new ArrayList<>(sealed.size());
			for (SealedAttachment a : sealed) {
				byte[] wrappedKey;
				if (closed) {
					try {
						wrappedKey = contentKey.wrapContentKey(kContent,
								channelId, a.perAttachmentKey);
					} catch (GeneralSecurityException ex) {
						throw new DbException(ex);
					}
				} else {
					wrappedKey = a.perAttachmentKey;
				}
				wireAttachments.add(new ChannelPost.ChannelAttachment(
						a.blobHash, a.sizeBytes, a.mimeType, wrappedKey,
						a.caption, a.thumbnail));
			}
			publishPostLocked(channelId, body, ttlSeconds, wireAttachments,
					blobsToStore);
		});
	}

	@Override
	@Nullable
	public org.zerionproject.app.api.channel.AttachmentBlob
			fetchAttachment(byte[] channelId, long postSeqNum,
					byte[] blobHash)
					throws DbException, IOException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		ChannelPost.ChannelAttachment target =
				findAttachment(channelId, postSeqNum, blobHash);
		if (target == null) return null;
		byte[] cachedBlob = blobStore.get(channelId, blobHash);
		boolean closed = !s.isPublicChannel();
		byte[] perAttKey;
		if (closed) {
			byte[] unwrapped = unwrapWithAnyKey(contentKeys(s), channelId,
					target.getPerAttachmentKey());
			if (unwrapped == null) return null;
			perAttKey = unwrapped;
		} else {
			perAttKey = target.getPerAttachmentKey();
		}
		byte[] blob = cachedBlob;
		if (blob == null) {
			byte[][] ch = buildChallenge(s, channelId);
			byte[] reqBytes = pullCodec().encodeAttachmentRequest(
					channelId, blobHash,
					ch == null ? null : ch[0], ch == null ? null : ch[1]);
			if (ch != null) reqBytes = proveV2(s, channelId, reqBytes, ch[0]);
			byte[] respBytes = transport.requestFromOnion(
					confirmedOnion(s), reqBytes);
			ChannelPullCodec.AttachmentResponse resp =
					pullCodec().decodeAttachmentResponse(respBytes);
			if (resp.blob.length == 0) return null;
			if (!java.util.Arrays.equals(resp.blobHash, blobHash)) {
				return null;
			}
			byte[] derived = crypto.hash(
					"org.zerionproject/CHANNEL_ATTACHMENT_BLOB",
					resp.blob);
			if (!java.util.Arrays.equals(derived, blobHash)) return null;
			blob = resp.blob;
			if (blobStore.totalBytes(channelId) + blob.length
					<= ChannelConstants
					.MAX_SUBSCRIBER_ATTACHMENT_BYTES_PER_CHANNEL) {
				blobStore.put(channelId, blobHash, blob);
			}
		}
		byte[] plaintext;
		try {
			plaintext = contentKey.decryptBlob(perAttKey, channelId,
					target.getMimeType(), target.getSizeBytes(), blob);
		} catch (GeneralSecurityException ex) {
			return null;
		}
		return new org.zerionproject.app.api.channel.AttachmentBlob(
				plaintext, target.getMimeType());
	}

	@Nullable
	private ChannelPost.ChannelAttachment findAttachment(byte[] channelId,
			long postSeqNum, byte[] blobHash) throws DbException {
		ChannelPost p = store.posts().getPost(channelId, postSeqNum);
		if (p == null || p.isWithheld()) return null;
		for (ChannelPost.ChannelAttachment a : p.getAttachments()) {
			if (java.util.Arrays.equals(a.getBlobHash(), blobHash)) return a;
		}
		return null;
	}

	@Override
	public void postComment(byte[] channelId, long parentPostSeqNum,
			String body) throws DbException {
		postCommentWithId(channelId, parentPostSeqNum, body);
	}

	@Override
	public long postCommentWithId(byte[] channelId, long parentPostSeqNum,
			String body) throws DbException {
		String trimmed = body.trim();
		if (trimmed.isEmpty()
				|| trimmed.length()
						> ChannelConstants.MAX_COMMENT_BODY_CHARS) {
			throw new DbException();
		}
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!discussionStore.isEnabledFor(channelId, parentPostSeqNum)) {
			throw new org.zerionproject.app.api.channel
					.ChannelRefusedException();
		}
		long commentId = random.nextLong();
		if (!s.weArePublisher()) {
			sendOrQueue(channelId, outboxEntry(commentId,
					ChannelQueuedItem.Kind.COMMENT, parentPostSeqNum, trimmed,
					0L, false, -1L), false);
			return commentId;
		}
		KeyPair keys = signingKeys(s);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) keys.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keys.getPrivate();
		byte[] signerEd = pub.getEd25519PublicKey();
		byte[] signerMl = pub.getMlDsaPublicKey();
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		String authorName;
		byte[] sig;
		try {
			authorName = boundedName(pickAuthorName(channelId, signerEd));
			byte[] signedInput = codec.commentSignedInput(channelId,
					parentPostSeqNum, commentId, trimmed, authorName, ts);
			sig = signAndClear(priv, (ed, ml) ->
					signatures.signUserComment(signedInput, ed, ml));
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		} finally {
			priv.clear();
		}
		ChannelComment row =
				new ChannelComment(
						parentPostSeqNum, commentId, trimmed, authorName,
						signerEd, signerMl, ts, sig);
		underLock(channelId, () -> {
			if (store.getChannel(channelId) == null) {
				throw new DbException();
			}
			commentStore.putComment(channelId, row);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		});
		return commentId;
	}

	static String boundedName(String name) {
		int max = ChannelConstants.MAX_COMMENT_AUTHOR_NAME_CHARS;
		if (name.length() <= max) return name;
		int end = Character.isHighSurrogate(name.charAt(max - 1))
				? max - 1 : max;
		return name.substring(0, end);
	}

	private String pickAuthorName(byte[] channelId, byte[] signerEd)
			throws DbException {
		for (ChannelSubscriber sub
				: subscriberStore.getSubscribers(channelId)) {
			if (java.util.Arrays.equals(sub.getEd25519PubKey(), signerEd)) {
				return sub.getDisplayName();
			}
		}
		return "";
	}

	private KeyPair signingKeys(ChannelState s) throws DbException {
		if (s.weArePublisher()) {
			HybridSignaturePrivateKey priv = publisherKey(s.getChannelId());
			if (priv == null) throw new DbException();
			return new KeyPair(new HybridSignaturePublicKey(
					s.getPublisherEd25519PubKey(),
					s.getPublisherMlDsaPubKey()), priv);
		}
		return memberKeys(s.getChannelId());
	}

	private KeyPair memberKeys(byte[] channelId) throws DbException {
		LocalAuthor me = identityManager.getLocalAuthor();
		return ChannelMemberKeys.derive(crypto,
				me.getPrivateKey().getEncoded(), channelId);
	}

	private static HybridSignaturePublicKey publicKeyOf(KeyPair keys) {
		((HybridSignaturePrivateKey) keys.getPrivate()).clear();
		return (HybridSignaturePublicKey) keys.getPublic();
	}

	@Override
	public byte[] getMyChannelPublicKey(byte[] channelId) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		return publicKeyOf(signingKeys(s)).getEncoded();
	}

	@Override
	public java.util.List<org.zerionproject.app.api.channel
			.ChannelComment> getComments(byte[] channelId,
					long parentPostSeqNum) throws DbException {
		java.util.List<org.zerionproject.app.api.channel
				.ChannelComment> all =
				commentStore.getComments(channelId);
		java.util.List<org.zerionproject.app.api.channel
				.ChannelComment> out = new ArrayList<>();
		for (ChannelComment c : all) {
			if (c.getParentPostSeqNum() == parentPostSeqNum) out.add(c);
		}
		return out;
	}

	@Override
	public boolean areDiscussionsEnabled(byte[] channelId)
			throws DbException {
		return discussionStore.isEnabled(channelId);
	}

	@Override
	public void setDiscussionsEnabled(byte[] channelId, boolean enabled)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (!s.weArePublisher()) throw new DbException();
			discussionStore.setState(channelId, enabled,
					Collections.<Long>emptyList());
			if (ChannelConstants.DISCUSSIONS_IN_MANIFEST) {
				store.putChannel(bumpManifestSeq(s));
			}
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} finally {
			lock.unlock();
		}
	}

	private byte[] handleCommentRequest(byte[] channelId,
			byte[] requestBytes) {
		try {
			ChannelPullCodec.CommentRequest req = pullCodec()
					.decodeCommentRequest(requestBytes);
			if (!java.util.Arrays.equals(req.channelId, channelId)
					|| !ChannelAuthorBinding.wellFormed(req.signerEd25519,
					req.signerMlDsa, req.signature)) {
				return safeCommentAck(false);
			}
			if (!discussionStore.isEnabledFor(channelId,
					req.parentPostSeqNum)) {
				return safeCommentAck(false);
			}
			if (!ChannelCommentPolicy.validFields(req.body, req.authorName)) {
				return safeCommentAck(false);
			}
			java.util.Set<Long> posts = visiblePosts(channelId);
			if (!posts.contains(req.parentPostSeqNum)) {
				return safeCommentAck(false);
			}
			long now = clock.currentTimeMillis();
			if (!fresh(req.timestampHourMs, now)) {
				return safeCommentAck(false);
			}
			ChannelState state = store.getChannel(channelId);
			if (state == null) return safeCommentAck(false);
			java.util.Set<String> known = knownSigners(state);
			boolean isKnown = known.contains(
					org.zerionproject.core.util.StringUtils.toHexString(
							req.signerEd25519));
			ChannelWriteBudget budget =
					isKnown ? knownWriteBudget : writeBudget;
			String budgetKey = isKnown
					? "c:k:" + ChannelStore.hex(channelId) + ":"
					+ ChannelStore.hex(req.signerEd25519)
					: "c:" + ChannelStore.hex(channelId);
			if (!budget.hasRoom(budgetKey, now)) {
				return safeCommentAck(false);
			}
			ChannelAuthorBinding.Session binding = bindings(state);
			if (!binding.admits(req.signerEd25519, req.signerMlDsa)) {
				return safeCommentAck(false);
			}
			ChannelComment candidate =
					new ChannelComment(
							req.parentPostSeqNum, req.commentId,
							req.body, req.authorName,
							req.signerEd25519, req.signerMlDsa,
							req.timestampHourMs, req.signature);
			if (!verifiedComment(channelId, candidate)) {
				return safeCommentAck(false);
			}
			java.util.List<ChannelComment> stored =
					commentStore.getComments(channelId);
			java.util.List<ChannelComment> current =
					ChannelCommentPolicy.retainPosts(stored, posts);
			java.util.List<ChannelComment> next =
					ChannelCommentPolicy.withAdmitted(current, candidate,
							known);
			if (next == null) return safeCommentAck(false);
			binding.bind(req.signerEd25519, req.signerMlDsa);
			binding.commit(heldAuthors(state));
			if (next != stored) {
				commentStore.setComments(channelId, next);
				budget.spend(budgetKey,
						ChannelCommentPolicy.storedBytes(next) * 4 / 3, now);
			}
			if (next != current) {
				eventBus.broadcast(new ChannelCommentReceivedEvent(channelId,
						req.parentPostSeqNum));
			}
			return safeCommentAck(true);
		} catch (IOException | DbException ex) {
			return safeCommentAck(false);
		}
	}

	private byte[] safeCommentAck(boolean ok) {
		try {
			return pullCodec().encodeCommentAck(ok);
		} catch (IOException ex) {
			return new byte[0];
		}
	}

	@Override
	public void setRequiresApproval(byte[] channelId, boolean required)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (!s.weArePublisher()) throw new DbException();
			if (s.isPublicChannel() && required) throw new DbException();
			if (s.requiresApproval() == required) return;
			ChannelState updated = new ChannelState(s.getChannelId(),
					s.getSalt(), s.getPublisherEd25519PubKey(),
					s.getPublisherMlDsaPubKey(), s.getName(),
					s.getDescription(), s.getAvatarHash(),
					s.getCreatedAtHourMs(), s.isPublicChannel(),
					s.getJoinCapability(), s.getCurrentOnion(),
					s.getManifestSeq() + 1L, true,
					s.getHighestKnownPostSeq(),
					s.getContentKeyHash(), s.getContentKey(),
					s.getActiveDelegations(),
					s.getRevokedDelegationSeqs(),
					s.getNextDelegationSeq(),
					s.getOnionPrivateKey(),
					s.getPinnedPostSeq(),
					required,
				s.getRetiredDelegations());
			store.putChannel(updated);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void applyToJoin(byte[] channelId, String displayName)
			throws DbException {
		applyToJoin(channelId, displayName, null);
	}

	@Override
	public void applyToJoin(ChannelInviteLink link, String displayName)
			throws DbException {
		ChannelState held = store.getChannel(link.getChannelId());
		if (held == null || !sameOwner(held, link)
				|| linkOnion(link) == null) {
			throw new DbException();
		}
		applyToJoin(link.getChannelId(), displayName, link);
	}

	private void applyToJoin(byte[] channelId, String displayName,
			@Nullable ChannelInviteLink link) throws DbException {
		String trimmed = displayName.trim();
		if (trimmed.isEmpty()
				|| trimmed.getBytes(
						java.nio.charset.StandardCharsets.UTF_8).length
						> ChannelConstants.MAX_DISPLAY_NAME_BYTES) {
			throw new DbException();
		}
		ChannelState before = store.getChannel(channelId);
		if (before == null || before.weArePublisher()) {
			throw new DbException();
		}
		ChannelMyApplicationsStore.MyApplication held =
				myApplicationsStore.get(channelId);
		if (link == null && held != null
				&& held.status == ApplicationStatus.PENDING && held.confirmed) {
			return;
		}
		String onion = null;
		try {
			onion = confirmedOnion(before);
		} catch (DbException e) {
			if (link == null || !(e.getCause() instanceof IOException)) {
				throw new org.zerionproject.app.api.channel
						.ChannelApplicationUnconfirmedException();
			}
		}
		byte[] request = null;
		byte[] ephPub;
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (s.weArePublisher()) throw new DbException();
			ChannelMyApplicationsStore.MyApplication existing =
					myApplicationsStore.get(channelId);
			if (link == null && existing != null
					&& existing.status == ApplicationStatus.PENDING
					&& existing.confirmed) {
				return;
			}
			boolean unconfirmed = existing != null
					&& existing.status == ApplicationStatus.PENDING
					&& !existing.confirmed;
			boolean again = unconfirmed && existing.signedWithMemberKey
					&& existing.ephemeralAgreementPriv != null
					&& existing.displayName.equals(trimmed);
			String name = trimmed;
			long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
			if (again) {
				ts = existing.appliedAtHourMs;
			} else {
				long floor = Math.max(
						myApplicationsStore.getHourFloor(channelId),
						existing == null ? -1L : existing.appliedAtHourMs);
				if (floor >= 0L) ts = Math.max(ts, floor + HOUR_MS);
			}
			byte[] ephPriv = null;
			if (again) {
				ephPub = existing.ephemeralAgreementPub;
			} else {
				KeyPair ephKp = crypto.generateHybridAgreementKeyPair();
				ephPub = ephKp.getPublic().getEncoded();
				ephPriv = ephKp.getPrivate().getEncoded();
			}
			KeyPair keys = memberKeys(channelId);
			HybridSignaturePublicKey pub =
					(HybridSignaturePublicKey) keys.getPublic();
			HybridSignaturePrivateKey priv =
					(HybridSignaturePrivateKey) keys.getPrivate();
			byte[] signerEd = pub.getEd25519PublicKey();
			byte[] signerMl = pub.getMlDsaPublicKey();
			byte[] signedInput = codec.applicationSignedInput(channelId,
					name, ts, ephPub);
			byte[] sig;
			try {
				sig = signAndClear(priv, (ed, ml) ->
						signatures.signUserApplication(signedInput, ed, ml));
			} catch (GeneralSecurityException ex) {
				throw new DbException(ex);
			}
			if (!again) {
				myApplicationsStore.put(channelId,
						new ChannelMyApplicationsStore.MyApplication(
								name, ephPriv, ephPub, ts,
								ApplicationStatus.PENDING, true, false));
			}
			try {
				request = pullCodec().encodeApplyRequest(channelId,
						name, ts, signerEd, signerMl, ephPub, sig);
			} catch (IOException ignored) {
			}
		} finally {
			lock.unlock();
		}
		if (request == null) throw new DbException();
		byte[] reply = null;
		if (onion != null) {
			try {
				reply = transport.requestFromOnion(onion, request);
			} catch (IOException dark) {
				onion = null;
			}
		}
		ChannelLinkCandidates.Candidate through = null;
		if (onion == null && link != null) {
			String given = linkOnion(link);
			through = given == null ? null : linkCandidates().add(channelId,
					random.nextLong(), given, null, true);
			String confirmed = through == null ? null
					: confirmCandidate(before, through);
			if (confirmed != null) {
				try {
					reply = transport.requestFromOnion(confirmed, request);
				} catch (IOException ignored) {
				}
			}
		}
		Boolean accepted = null;
		if (reply != null) {
			try {
				accepted = pullCodec().decodeApplyAck(reply);
			} catch (IOException ignored) {
			}
		}
		if (accepted == null || !settleApplication(channelId, ephPub,
				accepted)) {
			throw new org.zerionproject.app.api.channel
					.ChannelApplicationUnconfirmedException();
		}
		if (through != null && accepted) {
			ChannelLinkCandidates.Candidate now =
					linkCandidates().get(channelId, through.id);
			if (now != null) {
				linkCandidates().update(channelId, now.applied(true));
			}
		}
	}

	private boolean settleApplication(byte[] channelId, byte[] ephPub,
			boolean accepted) throws DbException {
		boolean kept;
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelMyApplicationsStore.MyApplication held =
					myApplicationsStore.get(channelId);
			if (held == null || !java.util.Arrays.equals(
					held.ephemeralAgreementPub, ephPub)) {
				return false;
			}
			boolean open = held.status == ApplicationStatus.PENDING
					&& !held.confirmed;
			if (open && accepted) {
				myApplicationsStore.put(channelId, held.confirmed());
			} else if (open) {
				myApplicationsStore.remove(channelId);
			}
			kept = accepted || !open;
		} finally {
			lock.unlock();
		}
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		return kept;
	}

	@Override
	public java.util.List<ChannelApplication> listPendingApplications(
			byte[] channelId) throws DbException {
		java.util.List<ChannelApplication> all =
				applicationStore.getApplications(channelId);
		java.util.List<ChannelApplication> out = new ArrayList<>();
		for (ChannelApplication a : all) {
			if (a.getStatus() == ChannelApplication.Status.PENDING) {
				out.add(a);
			}
		}
		return out;
	}

	@Override
	public java.util.List<ChannelApplication> listAllApplications(
			byte[] channelId) throws DbException {
		ChannelState s = store.getChannel(channelId);
		java.util.List<ChannelApplication> out = new ArrayList<>();
		for (ChannelApplication a
				: applicationStore.getApplications(channelId)) {
			if (a.getStatus() != ChannelApplication.Status.APPROVED
					|| (s != null && approvalListed(s, a))) {
				out.add(a);
			}
		}
		return out;
	}

	@Override
	public void approveApplication(byte[] channelId,
			byte[] applicantEd25519) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (!s.weArePublisher()) throw new DbException();
			byte[] capability = s.getJoinCapability();
			if (capability == null) throw new DbException();
			ChannelApplication app = applicationStore.findByApplicant(
					channelId, applicantEd25519);
			if (app == null) throw new DbException();
			try {
				applicationStore.putApplication(channelId,
						approved(channelId, app, capability));
			} catch (GeneralSecurityException ex) {
				throw new DbException(ex);
			}
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.APPLICANT_APPROVED);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} finally {
			lock.unlock();
		}
	}

	private ChannelApplication approved(byte[] channelId,
			ChannelApplication app, byte[] capability)
			throws GeneralSecurityException {
		byte[] ephPub = app.getApplicantEphemeralAgreementPub();
		org.zerionproject.core.api.crypto.HybridEncapsulationResult encap =
				crypto.hybridEncapsulate(crypto.getHybridAgreementKeyParser()
						.parsePublicKey(ephPub));
		byte[] sharedSecretCopy = encap.getSharedSecret();
		try {
			byte[] envelope = wrapApprovalCapability(channelId,
					sharedSecretCopy, capability);
			return new ChannelApplication(app.getDisplayName(),
					app.getApplicantEd25519(), app.getApplicantMlDsa(),
					ephPub, app.getAppliedAtHourMs(),
					ChannelApplication.Status.APPROVED,
					encap.getCiphertext(), envelope,
					approvalMark(capability));
		} finally {
			java.util.Arrays.fill(sharedSecretCopy, (byte) 0);
			encap.clearSecret();
		}
	}

	private boolean approvalCounts(ChannelState s, ChannelApplication a) {
		byte[] capability = s.getJoinCapability();
		return a.getStatus() == ChannelApplication.Status.APPROVED
				&& capability != null && java.util.Arrays.equals(
				a.getApprovedUnder(), approvalMark(capability));
	}

	private static boolean legacyApproval(ChannelApplication a) {
		return a.getStatus() == ChannelApplication.Status.APPROVED
				&& a.getApprovedUnder() == null;
	}

	private boolean approvalListed(ChannelState s, ChannelApplication a) {
		return legacyApproval(a) || approvalCounts(s, a);
	}

	private static boolean servesStoredApproval(ChannelApplication a) {
		return legacyApproval(a) && a.getKemCiphertext() != null
				&& a.getEnvelope() != null;
	}

	private byte[] approvalMark(byte[] capability) {
		return crypto.hash("org.zerionproject/CHANNEL_APPROVAL_CAPABILITY",
				capability);
	}

	@Override
	public void denyApplication(byte[] channelId, byte[] applicantEd25519)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (!s.weArePublisher()) throw new DbException();
			ChannelApplication app = applicationStore.findByApplicant(
					channelId, applicantEd25519);
			if (app == null) return;
			applicationStore.putApplication(channelId,
					new ChannelApplication(app.getDisplayName(),
							app.getApplicantEd25519(),
							app.getApplicantMlDsa(),
							app.getApplicantEphemeralAgreementPub(),
							app.getAppliedAtHourMs(),
							ChannelApplication.Status.DENIED,
							null, null));
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.APPLICANT_DENIED);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public ApplicationStatus getMyApplicationStatus(byte[] channelId)
			throws DbException {
		ChannelMyApplicationsStore.MyApplication app =
				myApplicationsStore.get(channelId);
		if (app == null || !app.confirmed) {
			return ApplicationStatus.NOT_APPLIED;
		}
		return app.status;
	}

	private byte[] wrapApprovalCapability(byte[] channelId,
			byte[] sharedSecret, byte[] capability)
			throws GeneralSecurityException {
		org.zerionproject.core.api.crypto.SecretKey wrap =
				crypto.deriveKey(ChannelConstants.APPROVAL_WRAP_LABEL,
						new org.zerionproject.core.api.crypto.SecretKey(
								sharedSecret),
						channelId);
		byte[] wrapBytes = wrap.getBytes();
		try {
			byte[] nonce = new byte[12];
			random.nextBytes(nonce);
			javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(
					"AES/GCM/NoPadding");
			cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
					new javax.crypto.spec.SecretKeySpec(wrapBytes, "AES"),
					new javax.crypto.spec.GCMParameterSpec(128, nonce));
			byte[] ct = cipher.doFinal(capability);
			java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(
					nonce.length + ct.length);
			out.put(nonce);
			out.put(ct);
			return out.array();
		} finally {
			java.util.Arrays.fill(wrapBytes, (byte) 0);
		}
	}

	private byte[] unwrapApprovalCapability(byte[] channelId,
			byte[] sharedSecret, byte[] envelope)
			throws GeneralSecurityException {
		if (envelope.length < 12 + 16) {
			throw new GeneralSecurityException("envelope too short");
		}
		org.zerionproject.core.api.crypto.SecretKey wrap =
				crypto.deriveKey(ChannelConstants.APPROVAL_WRAP_LABEL,
						new org.zerionproject.core.api.crypto.SecretKey(
								sharedSecret),
						channelId);
		byte[] wrapBytes = wrap.getBytes();
		try {
			byte[] nonce = java.util.Arrays.copyOfRange(envelope, 0, 12);
			byte[] ct = java.util.Arrays.copyOfRange(envelope, 12,
					envelope.length);
			javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(
					"AES/GCM/NoPadding");
			cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
					new javax.crypto.spec.SecretKeySpec(wrapBytes, "AES"),
					new javax.crypto.spec.GCMParameterSpec(128, nonce));
			return cipher.doFinal(ct);
		} finally {
			java.util.Arrays.fill(wrapBytes, (byte) 0);
		}
	}

	static boolean boundedUserFields(String displayName, byte[] signerEd,
			byte[] signerMl, byte[] signature) {
		int nameBytes = displayName.getBytes(
				java.nio.charset.StandardCharsets.UTF_8).length;
		return nameBytes > 0
				&& nameBytes <= ChannelConstants.MAX_DISPLAY_NAME_BYTES
				&& signerEd.length == ED25519_PUBLIC_KEY_BYTES
				&& signerMl.length == org.zerionproject.core.api.crypto
				.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES
				&& signature.length == org.zerionproject.core.api.crypto
				.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
	}

	private byte[] handleApplyRequest(byte[] channelId,
			byte[] requestBytes) {
		try {
			ChannelPullCodec.ApplyRequest req = pullCodec()
					.decodeApplyRequest(requestBytes);
			if (!java.util.Arrays.equals(req.channelId, channelId)) {
				return safeApplyAck(false);
			}
			if (!boundedUserFields(req.displayName, req.signerEd25519,
					req.signerMlDsa, req.signature)
					|| req.ephemeralAgreementPub.length
					!= org.zerionproject.core.api.crypto.PostQuantumConstants
					.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES) {
				return safeApplyAck(false);
			}
			ChannelState s = store.getChannel(channelId);
			if (s == null) return safeApplyAck(false);
			if (!s.requiresApproval()) return safeApplyAck(false);
			String budgetKey = "a:" + ChannelStore.hex(channelId);
			long now = clock.currentTimeMillis();
			boolean room = writeBudget.hasRoom(budgetKey, now);
			if (!room && !holdsApplication(channelId, req.signerEd25519,
					req.ephemeralAgreementPub)) {
				return safeApplyAck(false);
			}
			if (subscriberStore.isBanned(channelId, req.signerEd25519)) {
				return safeApplyAck(false);
			}
			ChannelAuthorBinding.Session binding = bindings(s);
			if (!binding.admits(req.signerEd25519, req.signerMlDsa)) {
				return safeApplyAck(false);
			}
			byte[] signedInput = codec.applicationSignedInput(channelId,
					req.displayName, req.timestampHourMs,
					req.ephemeralAgreementPub);
			org.zerionproject.core.api.crypto.PublicKey edPub;
			try {
				edPub = crypto.getSignatureKeyParser()
						.parsePublicKey(req.signerEd25519);
			} catch (GeneralSecurityException ex) {
				return safeApplyAck(false);
			}
			if (!signatures.verifyUserApplication(req.signature, signedInput,
					edPub, req.signerMlDsa)) {
				return safeApplyAck(false);
			}
			java.util.List<ChannelApplication> existing =
					applicationStore.getApplications(channelId);
			int pendingCount = 0;
			ChannelApplication mine = null;
			for (ChannelApplication a : existing) {
				if (a.getStatus() == ChannelApplication.Status.PENDING) {
					pendingCount++;
				}
				if (java.util.Arrays.equals(a.getApplicantEd25519(),
						req.signerEd25519)) {
					mine = a;
				}
			}
			if (mine != null
					&& mine.getStatus() != ChannelApplication.Status.DENIED) {
				if (java.util.Arrays.equals(
						mine.getApplicantEphemeralAgreementPub(),
						req.ephemeralAgreementPub)) {
					return safeApplyAck(true);
				}
				if (req.timestampHourMs < mine.getAppliedAtHourMs()) {
					return safeApplyAck(false);
				}
			}
			if (!room) return safeApplyAck(false);
			if (mine != null && approvalCounts(s, mine)) {
				ChannelApplication again;
				try {
					again = approved(channelId, new ChannelApplication(
							req.displayName, req.signerEd25519,
							req.signerMlDsa, req.ephemeralAgreementPub,
							req.timestampHourMs,
							ChannelApplication.Status.PENDING, null, null),
							s.getJoinCapability());
				} catch (GeneralSecurityException ex) {
					return safeApplyAck(false);
				}
				binding.bind(req.signerEd25519, req.signerMlDsa);
				binding.commit(heldAuthors(s));
				long rewritten = applicationStore.putApplication(channelId,
						again);
				if (rewritten > 0) {
					writeBudget.spend(budgetKey, rewritten, now);
					fireEvent(channelId,
							ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
				}
				return safeApplyAck(true);
			}
			boolean addsPending = mine == null
					|| mine.getStatus() == ChannelApplication.Status.APPROVED;
			if (addsPending && pendingCount
					>= ChannelConstants.MAX_PENDING_APPLICATIONS) {
				return safeApplyAck(false);
			}
			binding.bind(req.signerEd25519, req.signerMlDsa);
			binding.commit(heldAuthors(s));
			long written = applicationStore.putApplication(channelId,
					new ChannelApplication(req.displayName,
							req.signerEd25519, req.signerMlDsa,
							req.ephemeralAgreementPub,
							req.timestampHourMs,
							ChannelApplication.Status.PENDING,
							null, null));
			if (written > 0) {
				writeBudget.spend(budgetKey, written, now);
				fireEvent(channelId,
						ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			}
			return safeApplyAck(true);
		} catch (IOException | DbException ex) {
			return safeApplyAck(false);
		}
	}

	private byte[] safeApplyAck(boolean ok) {
		try {
			return pullCodec().encodeApplyAck(ok);
		} catch (IOException ex) {
			return new byte[0];
		}
	}

	private byte[] handleCheckApprovalRequest(byte[] channelId,
			byte[] requestBytes) {
		try {
			ChannelPullCodec.CheckApprovalRequest req = pullCodec()
					.decodeCheckApprovalRequest(requestBytes);
			if (!java.util.Arrays.equals(req.channelId, channelId)
					|| !ChannelAuthorBinding.wellFormed(req.signerEd25519,
					req.signerMlDsa, req.signature)) {
				return safeApprovalResponse("DENIED", null, null);
			}
			ChannelState s = store.getChannel(channelId);
			if (s == null || !bindings(s).admits(req.signerEd25519,
					req.signerMlDsa)) {
				return safeApprovalResponse("DENIED", null, null);
			}
			byte[] signedInput = codec.checkApprovalSignedInput(channelId,
					req.timestampHourMs);
			org.zerionproject.core.api.crypto.PublicKey edPub;
			try {
				edPub = crypto.getSignatureKeyParser()
						.parsePublicKey(req.signerEd25519);
			} catch (GeneralSecurityException ex) {
				return safeApprovalResponse("DENIED", null, null);
			}
			if (!signatures.verifyUserCheckApproval(req.signature,
					signedInput, edPub, req.signerMlDsa)) {
				return safeApprovalResponse("DENIED", null, null);
			}
			ChannelApplication app = applicationStore.findByApplicant(
					channelId, req.signerEd25519);
			if (app == null) {
				boolean knowsUnknown = pullCodec().peekVersion(requestBytes)
						>= ChannelConstants.PROTOCOL_VERSION_POST_CHANGES;
				return safeApprovalResponse(
						knowsUnknown ? "UNKNOWN" : "DENIED", null, null);
			}
			if (app.getStatus() == ChannelApplication.Status.APPROVED
					&& !approvalCounts(s, app)
					&& !servesStoredApproval(app)) {
				boolean knowsUnknown = pullCodec().peekVersion(requestBytes)
						>= ChannelConstants.PROTOCOL_VERSION_POST_CHANGES;
				return safeApprovalResponse(
						knowsUnknown ? "UNKNOWN" : "DENIED", null, null);
			}
			switch (app.getStatus()) {
				case APPROVED:
					return safeApprovalResponse("APPROVED",
							app.getKemCiphertext(), app.getEnvelope());
				case DENIED:
					return safeApprovalResponse("DENIED", null, null);
				case PENDING:
				default:
					return safeApprovalResponse("PENDING", null, null);
			}
		} catch (DbException ex) {
			return safeApprovalResponse("PENDING", null, null);
		} catch (IOException ex) {
			return safeApprovalResponse("DENIED", null, null);
		}
	}

	private byte[] safeApprovalResponse(String status,
			@Nullable byte[] kemCt, @Nullable byte[] envelope) {
		try {
			return pullCodec().encodeApprovalResponse(status, kemCt,
					envelope);
		} catch (IOException ex) {
			return new byte[0];
		}
	}

	private void pollApprovalStatusIfPending(byte[] channelId) {
		ChannelMyApplicationsStore.MyApplication my;
		try {
			my = myApplicationsStore.get(channelId);
		} catch (DbException e) {
			return;
		}
		if (my == null) return;
		if (my.status != ApplicationStatus.PENDING || !my.confirmed) return;
		String key = ChannelStore.hex(channelId);
		long now = clock.currentTimeMillis();
		Long last = lastApprovalPollMs.get(key);
		if (last != null
				&& now - last < APPROVAL_POLL_MIN_INTERVAL_MS) {
			return;
		}
		lastApprovalPollMs.put(key, now);
		byte[] toVerify = null;
		String verifyAt = null;
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) return;
			String onion = confirmedOnion(s);
			verifyAt = onion;
			byte[] signerEd;
			byte[] signerMl;
			byte[] sig;
			long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
			byte[] signedInput =
					codec.checkApprovalSignedInput(channelId, ts);
			try {
				if (my.signedWithMemberKey) {
					KeyPair keys = memberKeys(channelId);
					HybridSignaturePublicKey pub =
							(HybridSignaturePublicKey) keys.getPublic();
					HybridSignaturePrivateKey priv =
							(HybridSignaturePrivateKey) keys.getPrivate();
					signerEd = pub.getEd25519PublicKey();
					signerMl = pub.getMlDsaPublicKey();
					sig = signAndClear(priv, (ed, ml) ->
							signatures.signUserCheckApproval(signedInput,
									ed, ml));
				} else {
					LocalAuthor me = identityManager.getLocalAuthor();
					signerEd = me.getPublicKey().getEncoded();
					byte[] mlDsaPub =
							identityManager.getLocalMlDsaSigPublicKey();
					signerMl = mlDsaPub == null ? new byte[0] : mlDsaPub;
					byte[] mlDsaPriv =
							identityManager.getLocalMlDsaSigPrivateKey();
					try {
						sig = signatures.signUserCheckApproval(signedInput,
								me.getPrivateKey(), mlDsaPriv);
					} finally {
						if (mlDsaPriv != null) {
							java.util.Arrays.fill(mlDsaPriv, (byte) 0);
						}
					}
				}
			} catch (GeneralSecurityException ex) {
				return;
			}
			byte[] reqBytes = pullCodec().encodeCheckApprovalRequest(
					channelId, ts, signerEd, signerMl, sig);
			byte[] respBytes;
			try {
				respBytes = transport.requestFromOnion(onion, reqBytes);
			} catch (IOException e) {
				return;
			}
			ChannelPullCodec.ApprovalResponse resp =
					pullCodec().decodeApprovalResponse(respBytes);
			java.util.concurrent.locks.ReentrantLock lock =
					lockFor(channelId);
			lock.lock();
			try {
				ChannelMyApplicationsStore.MyApplication current =
						myApplicationsStore.get(channelId);
				if (current == null
						|| current.status != ApplicationStatus.PENDING
						|| !current.confirmed
						|| !java.util.Arrays.equals(
								current.ephemeralAgreementPub,
								my.ephemeralAgreementPub)) {
					return;
				}
				if ("APPROVED".equals(resp.status)
						&& resp.kemCt != null && resp.envelope != null) {
					toVerify = acceptApproval(channelId, current,
							resp.kemCt, resp.envelope);
				} else if ("DENIED".equals(resp.status)) {
					myApplicationsStore.put(channelId,
							new ChannelMyApplicationsStore.MyApplication(
									current.displayName, null,
									current.ephemeralAgreementPub,
									current.appliedAtHourMs,
									ApplicationStatus.DENIED,
									current.signedWithMemberKey));
					fireEvent(channelId,
							ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
				} else if ("UNKNOWN".equals(resp.status)) {
					myApplicationsStore.remove(channelId);
					fireEvent(channelId,
							ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
				}
			} finally {
				lock.unlock();
			}
		} catch (IOException | DbException ignored) {
		}
		if (toVerify != null && verifyAt != null) {
			verifyApproval(channelId, toVerify, verifyAt,
					my.ephemeralAgreementPub);
		}
	}

	@Nullable
	private byte[] acceptApproval(byte[] channelId,
			ChannelMyApplicationsStore.MyApplication my, byte[] kemCt,
			byte[] envelope) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) return null;
		byte[] held = s.getJoinCapability();
		if (my.ephemeralAgreementPriv == null) {
			if (held == null) forgetApplication(channelId);
			else markApprovedStatusOnly(channelId, my);
			return null;
		}
		byte[] capability = openApproval(channelId, my, kemCt, envelope);
		if (capability == null) return null;
		if (held != null && !java.util.Arrays.equals(held, capability)) {
			return capability;
		}
		if (held == null) store.putChannel(withCapability(s, capability));
		markApprovedStatusOnly(channelId, my);
		return null;
	}

	@Nullable
	private byte[] openApproval(byte[] channelId,
			ChannelMyApplicationsStore.MyApplication my, byte[] kemCt,
			byte[] envelope) throws DbException {
		byte[] ephPriv = my.ephemeralAgreementPriv;
		if (ephPriv == null) return null;
		org.zerionproject.core.api.crypto.KeyParser parser =
				crypto.getHybridAgreementKeyParser();
		byte[] sharedSecret = null;
		try {
			org.zerionproject.core.api.crypto.KeyPair kp =
					new org.zerionproject.core.api.crypto.KeyPair(
							parser.parsePublicKey(my.ephemeralAgreementPub),
							parser.parsePrivateKey(ephPriv));
			sharedSecret = crypto.hybridDecapsulate(kp, kemCt);
			return unwrapApprovalCapability(channelId, sharedSecret,
					envelope);
		} catch (GeneralSecurityException ex) {
			forgetApplication(channelId);
			return null;
		} finally {
			if (sharedSecret != null) {
				java.util.Arrays.fill(sharedSecret, (byte) 0);
			}
			java.util.Arrays.fill(ephPriv, (byte) 0);
		}
	}

	private void verifyApproval(byte[] channelId, byte[] capability,
			String onion, byte[] ephPub) {
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) return;
			byte[] reply = transport.requestFromOnion(onion,
					pullRequest(channelId, capability, sinceSeqOf(s), true));
			if (reply.length == 0) {
				long now = clock.currentTimeMillis();
				underLock(channelId, () -> {
					if (emptyRunOver(channelId, ephPub, now)) {
						forgetApplicationFor(channelId, ephPub);
					}
				});
				return;
			}
			ChannelRejoinResult r = adoptManifest(s, capability, reply, onion,
					true);
			if (r == ChannelRejoinResult.UPDATED) {
				markApprovedIfPending(channelId, ephPub);
			} else if (r == ChannelRejoinResult.NOT_RESTORED) {
				underLock(channelId, () -> forgetApplicationFor(channelId,
						ephPub));
			}
		} catch (IOException | DbException ignored) {
		}
	}

	private void markApprovedIfPending(byte[] channelId,
			@Nullable byte[] ephPub) throws DbException {
		underLock(channelId, () -> {
			ChannelMyApplicationsStore.MyApplication held =
					myApplicationsStore.get(channelId);
			if (held != null && held.status == ApplicationStatus.PENDING
					&& held.confirmed && (ephPub == null
					|| java.util.Arrays.equals(held.ephemeralAgreementPub,
					ephPub))) {
				markApprovedStatusOnly(channelId, held);
			}
		});
	}

	private boolean holdsApplication(byte[] channelId, byte[] signerEd,
			byte[] ephPub) throws DbException {
		for (ChannelApplication a
				: applicationStore.getApplications(channelId)) {
			if (a.getStatus() != ChannelApplication.Status.DENIED
					&& java.util.Arrays.equals(a.getApplicantEd25519(),
							signerEd)
					&& java.util.Arrays.equals(
							a.getApplicantEphemeralAgreementPub(), ephPub)) {
				return true;
			}
		}
		return false;
	}

	private void forgetApplication(byte[] channelId) throws DbException {
		myApplicationsStore.remove(channelId);
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
	}

	private void markApprovedStatusOnly(byte[] channelId,
			ChannelMyApplicationsStore.MyApplication my)
			throws DbException {
		if (my.status == ApplicationStatus.APPROVED) {
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			return;
		}
		myApplicationsStore.put(channelId, my.approved());
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
	}

	@Override
	public void announceMyself(byte[] channelId, String displayName)
			throws DbException {
		String trimmed = displayName.trim();
		if (trimmed.isEmpty()
				|| trimmed.getBytes(
						java.nio.charset.StandardCharsets.UTF_8).length
						> ChannelConstants.MAX_DISPLAY_NAME_BYTES) {
			throw new DbException();
		}
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) {
			sendOrQueue(channelId, outboxEntry(random.nextLong(),
					ChannelQueuedItem.Kind.ANNOUNCE, -1L, trimmed, 0L, false,
					-1L), true);
			return;
		}
		KeyPair keys = signingKeys(s);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) keys.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keys.getPrivate();
		byte[] signerEd = pub.getEd25519PublicKey();
		byte[] signerMl = pub.getMlDsaPublicKey();
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		byte[] signedInput = codec.announceSignedInput(channelId,
				trimmed, ts);
		byte[] sig;
		try {
			sig = signAndClear(priv, (ed, ml) ->
					signatures.signUserAnnounce(signedInput, ed, ml));
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		ChannelSubscriber row = new ChannelSubscriber(trimmed, signerEd,
				signerMl, ts, false);
		underLock(channelId, () -> {
			if (store.getChannel(channelId) == null) throw new DbException();
			subscriberStore.putSubscriber(channelId, row);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		});
	}

	@Override
	public java.util.List<ChannelSubscriber> getAnnouncedSubscribers(
			byte[] channelId) throws DbException {
		return subscriberStore.getSubscribers(channelId);
	}

	@Override
	public void banSubscriber(byte[] channelId, byte[] ed25519PubKey)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (!s.weArePublisher()) throw new DbException();
			subscriberStore.setBanned(channelId, ed25519PubKey, true);
			dropItemsBy(channelId, ed25519PubKey);
			for (ChannelDelegationCert c : new ArrayList<>(
					s.getActiveDelegations())) {
				if (java.util.Arrays.equals(c.getDelegateeEd25519PubKey(),
						ed25519PubKey)) {
					revokeDelegationLocked(channelId, c.getDelegationSeq());
				}
			}
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void unbanSubscriber(byte[] channelId, byte[] ed25519PubKey)
			throws DbException {
		underLock(channelId, () -> {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (!s.weArePublisher()) throw new DbException();
			if (!subscriberStore.isBanned(channelId, ed25519PubKey)) return;
			subscriberStore.setBanned(channelId, ed25519PubKey, false);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		});
	}

	@Override
	public List<byte[]> getBannedKeys(byte[] channelId) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null || !s.weArePublisher()) {
			return Collections.<byte[]>emptyList();
		}
		return subscriberStore.getBans(channelId);
	}

	@Override
	public void setSubscriberTrusted(byte[] channelId, byte[] ed25519PubKey,
			boolean trusted) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (!s.weArePublisher()) throw new DbException();
			if (trusted && subscriberStore.isBanned(channelId, ed25519PubKey)) {
				throw new DbException();
			}
			subscriberStore.setTrusted(channelId, ed25519PubKey, trusted);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} finally {
			lock.unlock();
		}
	}

	private void dropItemsBy(byte[] channelId, byte[] ed25519PubKey)
			throws DbException {
		List<ChannelReaction> reactions = new ArrayList<>();
		for (ChannelReaction r : reactionStore.getReactions(channelId)) {
			if (!java.util.Arrays.equals(r.getSignerEd25519PubKey(),
					ed25519PubKey)) {
				reactions.add(r);
			}
		}
		reactionStore.setReactions(channelId, reactions);
		List<ChannelComment> comments = new ArrayList<>();
		for (ChannelComment c : commentStore.getComments(channelId)) {
			if (!java.util.Arrays.equals(c.getAuthorEd25519PubKey(),
					ed25519PubKey)) {
				comments.add(c);
			}
		}
		commentStore.setComments(channelId, comments);
	}

	private ChannelAuthorBinding.Session bindings(ChannelState s)
			throws DbException {
		byte[] channelId = s.getChannelId();
		if (!store.pins().seeded(channelId)) {
			java.util.Map<String, byte[]> first =
					new java.util.LinkedHashMap<>();
			java.util.List<ChannelDelegationCert> certs =
					new ArrayList<>(s.getActiveDelegations());
			certs.addAll(s.getRetiredDelegations());
			for (ChannelDelegationCert c : certs) {
				seedPin(s, first, c.getDelegateeEd25519PubKey(),
						c.getDelegateeMlDsaPubKey());
			}
			if (s.weArePublisher()) {
				for (ChannelApplication a
						: applicationStore.getApplications(channelId)) {
					seedPin(s, first, a.getApplicantEd25519(),
							a.getApplicantMlDsa());
				}
				for (ChannelSubscriber sub
						: subscriberStore.getSubscribers(channelId)) {
					seedPin(s, first, sub.getEd25519PubKey(),
							sub.getMlDsaPubKey());
				}
			}
			for (ChannelComment c : commentStore.getComments(channelId)) {
				seedPin(s, first, c.getAuthorEd25519PubKey(),
						c.getAuthorMlDsaPubKey());
			}
			for (ChannelReaction r : reactionStore.getReactions(channelId)) {
				seedPin(s, first, r.getSignerEd25519PubKey(),
						r.getSignerMlDsaPubKey());
			}
			store.pins().seed(channelId, first, heldAuthors(s));
		}
		return new ChannelAuthorBinding.Session(crypto, store.pins(), s);
	}

	private void seedPin(ChannelState s, java.util.Map<String, byte[]> first,
			byte[] ed25519PubKey, @Nullable byte[] mlDsaPubKey) {
		if (mlDsaPubKey == null
				|| !ChannelAuthorBinding.wellFormed(ed25519PubKey, mlDsaPubKey)
				|| java.util.Arrays.equals(ed25519PubKey,
				s.getPublisherEd25519PubKey())) {
			return;
		}
		String key = ChannelStore.hex(ed25519PubKey);
		if (!first.containsKey(key)) {
			first.put(key, ChannelAuthorBinding.keyHash(crypto, mlDsaPubKey));
		}
	}

	private ChannelAuthorPins.Held heldAuthors(ChannelState s) {
		byte[] channelId = s.getChannelId();
		boolean publisher = s.weArePublisher();
		return () -> {
			java.util.Set<String> out = new java.util.HashSet<>();
			for (ChannelComment c : commentStore.getComments(channelId)) {
				out.add(ChannelStore.hex(c.getAuthorEd25519PubKey()));
			}
			for (ChannelReaction r : reactionStore.getReactions(channelId)) {
				out.add(ChannelStore.hex(r.getSignerEd25519PubKey()));
			}
			if (publisher) {
				for (ChannelSubscriber sub
						: subscriberStore.getSubscribers(channelId)) {
					out.add(ChannelStore.hex(sub.getEd25519PubKey()));
				}
				for (ChannelApplication a
						: applicationStore.getApplications(channelId)) {
					out.add(ChannelStore.hex(a.getApplicantEd25519()));
				}
			}
			return out;
		};
	}

	private void bindOwnKey(ChannelState s, byte[] ed25519PubKey,
			byte[] mlDsaPubKey) throws DbException {
		ChannelAuthorBinding.Session binding = bindings(s);
		binding.bind(ed25519PubKey, mlDsaPubKey);
		binding.commit(heldAuthors(s));
	}

	private java.util.Set<String> knownSigners(ChannelState s)
			throws DbException {
		byte[] channelId = s.getChannelId();
		java.util.Set<String> out = new java.util.HashSet<>();
		out.add(org.zerionproject.core.util.StringUtils.toHexString(
				s.getPublisherEd25519PubKey()));
		for (byte[] trusted : subscriberStore.getTrusted(channelId)) {
			out.add(org.zerionproject.core.util.StringUtils.toHexString(
					trusted));
		}
		for (ChannelApplication a
				: applicationStore.getApplications(channelId)) {
			if (approvalListed(s, a)) {
				out.add(org.zerionproject.core.util.StringUtils.toHexString(
						a.getApplicantEd25519()));
			}
		}
		for (ChannelDelegationCert c : s.getActiveDelegations()) {
			out.add(org.zerionproject.core.util.StringUtils.toHexString(
					c.getDelegateeEd25519PubKey()));
		}
		for (byte[] banned : subscriberStore.getBans(channelId)) {
			out.remove(org.zerionproject.core.util.StringUtils.toHexString(
					banned));
		}
		return out;
	}

	private static boolean fresh(long timestampHourMs, long now) {
		return timestampHourMs <= now + ChannelConstants.ITEM_MAX_FUTURE_MS
				&& timestampHourMs >= now - ChannelConstants.ITEM_MAX_AGE_MS;
	}

	private byte[] handleAnnounceRequest(byte[] channelId,
			byte[] requestBytes) {
		try {
			ChannelPullCodec.AnnounceRequest req = pullCodec()
					.decodeAnnounceRequest(requestBytes);
			if (!java.util.Arrays.equals(req.channelId, channelId)) {
				return safeAnnounceAck(false);
			}
			if (!boundedUserFields(req.displayName, req.signerEd25519,
					req.signerMlDsa, req.signature)) {
				return safeAnnounceAck(false);
			}
			String budgetKey = "s:" + ChannelStore.hex(channelId);
			long now = clock.currentTimeMillis();
			if (!writeBudget.hasRoom(budgetKey, now)) {
				return safeAnnounceAck(false);
			}
			ChannelState state = store.getChannel(channelId);
			if (state == null) return safeAnnounceAck(false);
			ChannelAuthorBinding.Session binding = bindings(state);
			if (!binding.admits(req.signerEd25519, req.signerMlDsa)) {
				return safeAnnounceAck(false);
			}
			byte[] signedInput = codec.announceSignedInput(channelId,
					req.displayName, req.timestampHourMs);
			org.zerionproject.core.api.crypto.PublicKey edPub;
			try {
				edPub = crypto.getSignatureKeyParser()
						.parsePublicKey(req.signerEd25519);
			} catch (GeneralSecurityException ex) {
				return safeAnnounceAck(false);
			}
			if (!signatures.verifyUserAnnounce(req.signature, signedInput,
					edPub, req.signerMlDsa)) {
				return safeAnnounceAck(false);
			}
			if (subscriberStore.isBanned(channelId, req.signerEd25519)) {
				return safeAnnounceAck(false);
			}
			java.util.List<ChannelSubscriber> existing =
					subscriberStore.getSubscribers(channelId);
			boolean known = false;
			for (ChannelSubscriber sub : existing) {
				if (java.util.Arrays.equals(sub.getEd25519PubKey(),
						req.signerEd25519)) {
					known = true;
					break;
				}
			}
			if (!known && existing.size()
					>= ChannelConstants.MAX_ANNOUNCED_SUBSCRIBERS) {
				return safeAnnounceAck(false);
			}
			binding.bind(req.signerEd25519, req.signerMlDsa);
			binding.commit(heldAuthors(state));
			long written = subscriberStore.putSubscriber(channelId,
					new ChannelSubscriber(req.displayName,
							req.signerEd25519, req.signerMlDsa,
							req.timestampHourMs, false));
			if (written > 0) writeBudget.spend(budgetKey, written, now);
			return safeAnnounceAck(true);
		} catch (IOException | DbException ex) {
			return safeAnnounceAck(false);
		}
	}

	private byte[] safeAnnounceAck(boolean ok) {
		try {
			return pullCodec().encodeAnnounceAck(ok);
		} catch (IOException ex) {
			return new byte[0];
		}
	}

	@Override
	public void reactToPost(byte[] channelId, long postSeqNum,
			String emoji) throws DbException {
		if (emoji.isEmpty() || emoji.getBytes(
				java.nio.charset.StandardCharsets.UTF_8).length
				> ChannelConstants.MAX_REACTION_EMOJI_BYTES) {
			throw new DbException();
		}
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) {
			sendOrQueue(channelId, outboxEntry(random.nextLong(),
					ChannelQueuedItem.Kind.REACTION, postSeqNum, emoji, 0L,
					false, -1L), true);
			return;
		}
		KeyPair keys = signingKeys(s);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) keys.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keys.getPrivate();
		byte[] signerEd = pub.getEd25519PublicKey();
		byte[] signerMl = pub.getMlDsaPublicKey();
		long ts;
		byte[] sig;
		try {
			ChannelReaction mine = null;
			for (ChannelReaction r : reactionStore.getReactions(channelId)) {
				if (r.getPostSeqNum() == postSeqNum
						&& java.util.Arrays.equals(
						r.getSignerEd25519PubKey(), signerEd)) {
					mine = r;
				}
			}
			ts = ChannelReactionPolicy.nextTimestamp(mine,
					clock.currentTimeMillis());
			if (ts < 0L) {
				throw new org.zerionproject.app.api.channel
						.ChannelReactionTooSoonException();
			}
			byte[] signedInput = codec.reactionSignedInput(channelId,
					postSeqNum, emoji, ts);
			sig = signAndClear(priv, (ed, ml) ->
					signatures.signUserReaction(signedInput, ed, ml));
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		} finally {
			priv.clear();
		}
		ChannelReaction reaction = new ChannelReaction(postSeqNum, emoji,
				signerEd, signerMl, ts, sig);
		underLock(channelId, () -> {
			if (store.getChannel(channelId) == null) {
				throw new DbException();
			}
			reactionStore.putReaction(channelId, reaction);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		});
	}

	@Override
	public java.util.List<org.zerionproject.app.api.channel
			.ChannelReaction> getReactions(byte[] channelId,
					long postSeqNum) throws DbException {
		java.util.List<org.zerionproject.app.api.channel
				.ChannelReaction> all =
				reactionStore.getReactions(channelId);
		java.util.List<org.zerionproject.app.api.channel
				.ChannelReaction> out = new ArrayList<>();
		for (ChannelReaction r : all) {
			if (r.getPostSeqNum() == postSeqNum) out.add(r);
		}
		return out;
	}

	@Override
	public java.util.List<org.zerionproject.app.api.channel
			.ChannelReaction> getAllReactions(byte[] channelId)
			throws DbException {
		return reactionStore.getReactions(channelId);
	}

	@Override
	public java.util.List<org.zerionproject.app.api.channel
			.ChannelComment> getAllComments(byte[] channelId)
			throws DbException {
		return commentStore.getComments(channelId);
	}

	private byte[] handleReactionRequest(byte[] channelId,
			byte[] requestBytes) {
		try {
			ChannelPullCodec.ReactionRequest req = pullCodec()
					.decodeReactionRequest(requestBytes);
			if (!java.util.Arrays.equals(req.channelId, channelId)
					|| !ChannelAuthorBinding.wellFormed(req.signerEd25519,
					req.signerMlDsa, req.signature)) {
				return safeAck(false);
			}
			if (req.emoji.isEmpty()
					|| req.emoji.getBytes(
							java.nio.charset.StandardCharsets.UTF_8).length
					> ChannelConstants.MAX_REACTION_EMOJI_BYTES) {
				return safeAck(false);
			}
			java.util.Set<Long> posts = visiblePosts(channelId);
			if (!posts.contains(req.postSeqNum)) return safeAck(false);
			long now = clock.currentTimeMillis();
			if (!fresh(req.timestampHourMs, now)) return safeAck(false);
			ChannelState state = store.getChannel(channelId);
			if (state == null) return safeAck(false);
			java.util.Set<String> known = knownSigners(state);
			boolean isKnown = known.contains(
					org.zerionproject.core.util.StringUtils.toHexString(
							req.signerEd25519));
			ChannelWriteBudget budget =
					isKnown ? knownWriteBudget : writeBudget;
			String budgetKey = isKnown
					? "r:k:" + ChannelStore.hex(channelId) + ":"
					+ ChannelStore.hex(req.signerEd25519)
					: "r:" + ChannelStore.hex(channelId);
			if (!budget.hasRoom(budgetKey, now)) return safeAck(false);
			ChannelAuthorBinding.Session binding = bindings(state);
			if (!binding.admits(req.signerEd25519, req.signerMlDsa)) {
				return safeAck(false);
			}
			ChannelReaction candidate =
					new ChannelReaction(
							req.postSeqNum, req.emoji, req.signerEd25519,
							req.signerMlDsa, req.timestampHourMs,
							req.signature);
			if (!verifiedReaction(channelId, candidate)) {
				return safeAck(false);
			}
			java.util.List<ChannelReaction> stored =
					reactionStore.getReactions(channelId);
			java.util.List<ChannelReaction> current =
					ChannelReactionPolicy.retainPosts(stored, posts);
			java.util.List<ChannelReaction> next =
					ChannelReactionPolicy.withAdmitted(current, candidate,
							known);
			if (next == null) return safeAck(false);
			binding.bind(req.signerEd25519, req.signerMlDsa);
			binding.commit(heldAuthors(state));
			if (next != stored) {
				reactionStore.setReactions(channelId, next);
				budget.spend(budgetKey,
						ChannelReactionPolicy.storedBytes(next) * 4 / 3, now);
			}
			return safeAck(true);
		} catch (IOException | DbException ex) {
			return safeAck(false);
		}
	}

	private byte[] safeAck(boolean ok) {
		try {
			return pullCodec().encodeReactionAck(ok);
		} catch (IOException ex) {
			return new byte[0];
		}
	}

	@Override
	@Nullable
	public byte[] decryptAttachmentThumbnail(byte[] channelId,
			long postSeqNum, byte[] blobHash) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		ChannelPost.ChannelAttachment target =
				findAttachment(channelId, postSeqNum, blobHash);
		if (target == null) return null;
		byte[] thumbCt = target.getThumbnail();
		if (thumbCt == null) return null;
		boolean closed = !s.isPublicChannel();
		byte[] perAttKey;
		if (closed) {
			byte[] unwrapped = unwrapWithAnyKey(contentKeys(s), channelId,
					target.getPerAttachmentKey());
			if (unwrapped == null) return null;
			perAttKey = unwrapped;
		} else {
			perAttKey = target.getPerAttachmentKey();
		}
		try {
			return contentKey.decryptBlob(perAttKey, channelId,
					"image/jpeg", thumbCt.length - 28, thumbCt);
		} catch (GeneralSecurityException ex) {
			return null;
		}
	}

	private void setPinnedPostSeqLocked(byte[] channelId, long seqNum)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		if (s.getPinnedPostSeq() == seqNum) return;
		ChannelState updated = new ChannelState(s.getChannelId(),
				s.getSalt(), s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), s.getCurrentOnion(),
				s.getManifestSeq() + 1L, true,
				s.getHighestKnownPostSeq(),
				s.getContentKeyHash(), s.getContentKey(),
				s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(),
				s.getOnionPrivateKey(),
				seqNum,
				s.requiresApproval(),
				s.getRetiredDelegations());
		store.putChannel(updated);
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
	}

	@Override
	public void purgeExpiredPosts() throws DbException {
		long now = clock.currentTimeMillis();
		DbException firstFailure = null;
		for (ChannelState s : store.listChannels()) {
			byte[] channelId = s.getChannelId();
			java.util.concurrent.locks.ReentrantLock lock =
					lockFor(channelId);
			lock.lock();
			try {
				purgeChannel(channelId, now);
			} catch (DbException e) {
				if (firstFailure == null) firstFailure = e;
			} finally {
				lock.unlock();
			}
		}
		if (firstFailure != null) throw firstFailure;
	}

	private static final long FULL_SCAN_INTERVAL_MS =
			7L * 24L * 60L * 60L * 1000L;

	private void purgeChannel(byte[] channelId, long now)
			throws DbException {
		ChannelPostStore.Meta meta = store.posts().meta(channelId);
		boolean due = meta.minExpiry <= now;
		boolean scanDue = now - meta.lastScanMs >= FULL_SCAN_INTERVAL_MS;
		if (!due && !scanDue) return;
		List<Long> expired = new ArrayList<>();
		java.util.List<byte[]> referenced = new java.util.ArrayList<>();
		long minExpiry = Long.MAX_VALUE;
		ChannelState s = scanDue ? store.getChannel(channelId) : null;
		List<byte[]> keys = s == null ? Collections.<byte[]>emptyList()
				: contentKeys(s);
		List<ChannelPost> edits = new ArrayList<>();
		java.util.Map<Long, ChannelPost> kept = new java.util.HashMap<>();
		java.util.Map<Long, Integer> marksPer = new java.util.HashMap<>();
		for (ChannelPost p : store.getPosts(channelId)) {
			if (p.getTtlMs() > 0) {
				long expiry = p.getTimestampHourMs() + p.getTtlMs();
				if (now > expiry) {
					expired.add(p.getSeqNum());
					continue;
				}
				minExpiry = Math.min(minExpiry, expiry);
			}
			for (ChannelPost.ChannelAttachment a : p.getAttachments()) {
				referenced.add(a.getBlobHash());
			}
			if (s == null) continue;
			kept.put(p.getSeqNum(), p);
			ChannelPostMarkers.Edit edit = editOf(s, keys, p);
			if (edit != null) {
				edits.add(p);
				marksPer.merge(edit.target, 1, Integer::sum);
			}
		}
		if (!expired.isEmpty()) {
			List<ChannelPost> purged = store.posts().remove(channelId, expired,
					false);
			for (ChannelPost gone : purged) {
				for (ChannelPost.ChannelAttachment a : gone.getAttachments()) {
					blobStore.removeBlob(channelId, a.getBlobHash());
				}
			}
			java.util.Set<Long> held = visiblePosts(channelId);
			reactionStore.retainPosts(channelId, held);
			commentStore.retainPosts(channelId, held);
			store.setUnread(channelId,
					store.posts().meta(channelId).unread());
			announceRemoved(channelId, purged);
		}
		if (s != null) {
			java.util.Set<Long> waiting = store.posts().meta(channelId).pending;
			for (ChannelPost mark : edits) {
				ChannelPostMarkers.Edit edit = editOf(s, keys, mark);
				if (edit == null) continue;
				ChannelPost target = kept.get(edit.target);
				boolean needed = marksPer.get(edit.target) > 1
						|| waiting.contains(edit.target)
						|| (target != null && !target.isErased()
						&& target.getFormatVersion() == ChannelPost.FORMAT_V2);
				if (needed) applyEditMark(s, mark);
			}
		}
		store.posts().recordScan(channelId, minExpiry, now);
		blobStore.pruneOrphans(channelId, referenced, BLOB_ORPHAN_GRACE_MS);
	}

	private ChannelState withDelegations(ChannelState s,
			java.util.List<ChannelDelegationCert> active,
			java.util.List<Long> revoked, long nextSeq) {
		java.util.List<ChannelDelegationCert> leaving =
				new java.util.ArrayList<>();
		for (ChannelDelegationCert c : s.getActiveDelegations()) {
			boolean still = false;
			for (ChannelDelegationCert a : active) {
				if (a.getDelegationSeq() == c.getDelegationSeq()) {
					still = true;
					break;
				}
			}
			if (!still) leaving.add(c);
		}
		java.util.List<ChannelDelegationCert> retired =
				ChannelState.retire(s.getRetiredDelegations(), leaving);
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), s.getCurrentOnion(),
				s.getManifestSeq() + 1L, s.weArePublisher(),
				s.getHighestKnownPostSeq(),
				s.getContentKeyHash(), s.getContentKey(),
				active, revoked, nextSeq, s.getOnionPrivateKey(),
				s.getPinnedPostSeq(),
				s.requiresApproval(),
				retired);
	}

	void acceptIncomingPost(byte[] channelId, ChannelPost incoming)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			acceptIncomingPostLocked(channelId, incoming);
		} finally {
			lock.unlock();
		}
	}

	private void acceptIncomingPostLocked(byte[] channelId,
			ChannelPost incoming) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		ChannelChainTip tip = chainTip(s);
		ChannelPostValidator.Result vr =
				validator.validate(s, incoming, tip, true);
		byte[] hash = chainVerifier.hashOf(incoming);
		ChannelPost stored = incoming;
		byte[] erasedHash = incoming.getErasedBodyHash();
		if (erasedHash != null && !s.isPublicChannel()) {
			stored = incoming.erased(erasedHash, servedUnder(s));
		}
		if (incoming.isWithheld()) {
			boolean chainOk = validator.validateChain(incoming, tip, true)
					== ChannelPostValidator.Result.OK;
			boolean signerState = vr == ChannelPostValidator.Result.OK
					|| vr == ChannelPostValidator.Result.DELEGATION_REVOKED
					|| vr == ChannelPostValidator.Result.DELEGATION_NOT_FOUND;
			if (!chainOk || !signerState || !incoming.signedByDelegate()) {
				throw new DbException();
			}
			keepWithinWindow(s, stored, hash, false, false);
			store.putChannel(withSeq(s, incoming.getSeqNum()));
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.UNREAD_COUNT_CHANGED);
			return;
		}
		if (vr != ChannelPostValidator.Result.OK) {
			throw new DbException();
		}
		List<byte[]> keys = contentKeys(s);
		boolean mark = isMark(s, keys, incoming);
		boolean waiting = incoming.isErased();
		boolean unreadable = !waiting && !s.isPublicChannel()
				&& openedBody(s, keys, incoming) == null;
		boolean kept = (!unreadable || store.posts().admitsUnreadable(
				channelId, incoming.getSeqNum()))
				? keepWithinWindow(s,
						mark ? stored.withFlags(false, true) : stored, hash,
						waiting, unreadable)
				: passOver(channelId, incoming.getSeqNum(), hash);
		ChannelState updated = withSeq(s, incoming.getSeqNum());
		store.putChannel(updated);
		if (!kept) return;
		applyDeletionMark(updated, incoming);
		applyEditMark(updated, incoming);
		store.setUnread(channelId, store.posts().meta(channelId).unread());
		if (!mark && !waiting && !unreadable) {
			eventBus.broadcast(new ChannelPostReceivedEvent(channelId,
					incoming.getSeqNum(), false));
		}
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.UNREAD_COUNT_CHANGED);
	}

	private boolean passOver(byte[] channelId, long seq, byte[] hash)
			throws DbException {
		store.posts().passOver(channelId, seq, hash);
		return false;
	}

	@Nullable
	private byte[] servedUnder(ChannelState s) {
		byte[] named = s.getContentKeyHash();
		if (named != null) return named;
		byte[] current = s.getContentKey();
		return current == null ? null : contentKey.hashContentKey(current);
	}

	private boolean keepWithinWindow(ChannelState s, ChannelPost post,
			byte[] hash, boolean waiting, boolean unreadable)
			throws DbException {
		byte[] channelId = s.getChannelId();
		long b = ChannelPostCeilings.storedBytes(post);
		if (b > ChannelConstants.MAX_SUBSCRIBER_BYTES_PER_POST) {
			return passOver(channelId, post.getSeqNum(), hash);
		}
		ChannelPostStore.Meta m = store.posts().meta(channelId);
		long count = m.count;
		long bytes = m.bytes;
		List<Long> victims = new ArrayList<>();
		boolean fits = count + 1L
				<= ChannelConstants.MAX_SUBSCRIBER_POSTS_PER_CHANNEL
				&& bytes + b
				<= ChannelConstants.MAX_SUBSCRIBER_POST_BYTES_PER_CHANNEL;
		long pinnedSeq = s.getPinnedPostSeq();
		ChannelPost pinned = fits || pinnedSeq < 0L ? null
				: store.posts().getPost(channelId, pinnedSeq);
		List<byte[]> keys = pinned == null ? Collections.<byte[]>emptyList()
				: contentKeys(s);
		ChannelPost pinnedText = null;
		for (Long seq : fits ? Collections.<Long>emptyList()
				: m.heldSeqs()) {
			if (count + 1L <= ChannelConstants.MAX_SUBSCRIBER_POSTS_PER_CHANNEL
					&& bytes + b <= ChannelConstants
					.MAX_SUBSCRIBER_POST_BYTES_PER_CHANNEL) {
				break;
			}
			if (seq == pinnedSeq) continue;
			ChannelPost old = store.posts().getPost(channelId, seq);
			ChannelPost victim = old;
			if (old != null && givesText(s, keys, pinned, old)) {
				victim = pinnedText;
				pinnedText = old;
				if (victim == null) continue;
			}
			victims.add(victim == null ? seq : victim.getSeqNum());
			count--;
			if (victim != null) {
				bytes -= ChannelPostCeilings.storedBytes(victim);
			}
		}
		if (waiting) {
			java.util.TreeSet<Long> held = new java.util.TreeSet<>(
					store.posts().meta(channelId).pending);
			held.removeAll(victims);
			while (held.size()
					>= ChannelPostCeilings.MAX_WAITING_ERASED_POSTS) {
				victims.add(held.pollFirst());
			}
		}
		if (!victims.isEmpty()) {
			List<ChannelPost> givenUp = store.posts().remove(channelId,
					victims, true);
			for (ChannelPost gone : givenUp) {
				for (ChannelPost.ChannelAttachment a : gone.getAttachments()) {
					blobStore.removeBlob(channelId, a.getBlobHash());
				}
			}
			announceRemoved(channelId, givenUp);
		}
		store.posts().append(channelId, post, hash,
				Collections.<String, org.zerionproject.core.api.settings
						.Settings>emptyMap(), waiting, unreadable);
		return true;
	}

	private boolean givesText(ChannelState s, List<byte[]> keys,
			@Nullable ChannelPost target, ChannelPost mark) {
		if (target == null || mark.getSeqNum() <= target.getSeqNum()
				|| !target.isInEditWindowAt(mark.getTimestampHourMs())) {
			return false;
		}
		ChannelPostMarkers.Edit edit = editOf(s, keys, mark);
		return edit != null && edit.target == target.getSeqNum();
	}

	private void withholdNewlyRevoked(byte[] channelId, ChannelState before,
			ChannelState after) throws DbException {
		for (Long seq : after.getRevokedDelegationSeqs()) {
			if (seq != null && !before.getRevokedDelegationSeqs()
					.contains(seq)) {
				withholdRevoked(channelId, after);
				return;
			}
		}
	}

	private void withholdRevoked(byte[] channelId, ChannelState state)
			throws DbException {
		List<ChannelPost> posts = store.getPosts(channelId);
		List<Long> seqs = ChannelWithholding.newlyRevoked(validator, state,
				posts);
		if (seqs.isEmpty()) return;
		store.posts().setWithheld(channelId, seqs);
		store.setUnread(channelId, store.posts().meta(channelId).unread());
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.UNREAD_COUNT_CHANGED);
	}

	private enum Sent {
		SENT,
		REFUSED,
		NO_ACCESS,
		WAIT,
		LATER,
		UNREACHABLE
	}

	private enum Flow {
		CLEAR,
		UNANSWERED,
		WAITING,
		NO_ACCESS
	}

	private static final int MAX_REMEMBERED_SENT = 512;

	private final java.util.Map<String,
			java.util.concurrent.locks.ReentrantLock> sendLocks =
					new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> cancelledItems =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final java.util.Map<String, String> deniedMarks =
			new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String, Boolean> sentItems =
			Collections.synchronizedMap(
					new java.util.LinkedHashMap<String, Boolean>() {
						@Override
						protected boolean removeEldestEntry(
								java.util.Map.Entry<String, Boolean> eldest) {
							return size() > MAX_REMEMBERED_SENT;
						}
					});
	private final java.util.Map<Long, Long> acceptedPostSeqs =
			new java.util.concurrent.ConcurrentHashMap<>();
	private volatile ChannelOutboxStore outboxInstance;

	private ChannelOutboxStore outbox() {
		ChannelOutboxStore o = outboxInstance;
		if (o != null) return o;
		synchronized (sendLocks) {
			if (outboxInstance == null) {
				outboxInstance = new ChannelOutboxStore(store.settings());
			}
			return outboxInstance;
		}
	}

	private java.util.concurrent.locks.ReentrantLock sendLockFor(
			byte[] channelId) {
		return sendLocks.computeIfAbsent(ChannelStore.hex(channelId),
				k -> new java.util.concurrent.locks.ReentrantLock());
	}

	private ChannelOutboxStore.Entry outboxEntry(long id,
			ChannelQueuedItem.Kind kind, long postSeq, String text,
			long ttlSeconds, boolean commentsOn, long afterSeq) {
		return new ChannelOutboxStore.Entry(id, kind, postSeq, text,
				ttlSeconds, commentsOn, afterSeq, clock.currentTimeMillis(),
				0L, false);
	}

	@Override
	public List<ChannelQueuedItem> getQueuedItems(byte[] channelId)
			throws DbException {
		reviveIfAccessChanged(channelId);
		outbox().age(channelId, clock.currentTimeMillis());
		List<ChannelQueuedItem> out = new ArrayList<>();
		for (ChannelOutboxStore.Entry e : outbox().list(channelId)) {
			out.add(e.toItem());
		}
		return out;
	}

	@Override
	public void retryQueuedItem(byte[] channelId, long itemId)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock =
				sendLockFor(channelId);
		lock.lock();
		try {
			if (store.getChannel(channelId) == null
					|| !outbox().retry(channelId, itemId,
					clock.currentTimeMillis())) {
				throw new DbException();
			}
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			ownerAnswers(channelId);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public ChannelQueuedItem.DeleteResult deleteQueuedItem(byte[] channelId,
			long itemId) throws DbException {
		String key = cancelKey(channelId, itemId);
		cancelledItems.add(key);
		try {
			java.util.concurrent.locks.ReentrantLock lock =
					sendLockFor(channelId);
			lock.lock();
			try {
				if (outbox().remove(channelId, itemId)) {
					fireEvent(channelId,
							ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
					return ChannelQueuedItem.DeleteResult.DELETED;
				}
				return sentItems.containsKey(key)
						? ChannelQueuedItem.DeleteResult.SENT
						: ChannelQueuedItem.DeleteResult.GONE;
			} finally {
				lock.unlock();
			}
		} finally {
			cancelledItems.remove(key);
		}
	}

	private static String cancelKey(byte[] channelId, long itemId) {
		return ChannelStore.hex(channelId) + ":" + itemId;
	}

	private long sendOrQueue(byte[] channelId,
			ChannelOutboxStore.Entry entry, boolean replaces)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock =
				sendLockFor(channelId);
		lock.lock();
		try {
			if (entry.kind == ChannelQueuedItem.Kind.REACTION
					&& reactionTooSoon(channelId, entry)) {
				throw new org.zerionproject.app.api.channel
						.ChannelReactionTooSoonException();
			}
			ChannelOutboxStore.Entry fresh =
					entry.kind == ChannelQueuedItem.Kind.DELETE
					? withTargetExpiry(channelId, entry) : entry;
			if (replaces && takeOver(channelId, fresh)) {
				settleTakenOver(channelId, fresh);
				return -1L;
			}
			return sendNowOrKeep(channelId, fresh, replaces);
		} finally {
			lock.unlock();
		}
	}

	private ChannelOutboxStore.Entry withTargetExpiry(byte[] channelId,
			ChannelOutboxStore.Entry entry) throws DbException {
		ChannelPost target = store.posts().getPost(channelId, entry.postSeq);
		if (target == null || target.getTtlMs() <= 0L) return entry;
		return entry.withTargetExpiry(target.getTimestampHourMs()
				+ target.getTtlMs());
	}

	private boolean takeOver(byte[] channelId,
			ChannelOutboxStore.Entry entry) throws DbException {
		boolean[] took = new boolean[1];
		underLock(channelId, () -> {
			if (store.getChannel(channelId) == null) {
				throw new DbException();
			}
			took[0] = outbox().takeOver(channelId, entry);
		});
		if (took[0]) {
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		}
		return took[0];
	}

	private void settleTakenOver(byte[] channelId,
			ChannelOutboxStore.Entry entry) throws DbException {
		Flow flow = ownerAnswers(channelId);
		if (sentItems.containsKey(cancelKey(channelId, entry.id))) return;
		ChannelOutboxStore.Entry now = outbox().get(channelId, entry.id);
		if (now == null) throw new DbException();
		if (now.deniedAccess()) {
			throw new org.zerionproject.app.api.channel
					.ChannelNoAccessException();
		}
		boolean refused = now.notSent() || (now.failures > 0 && !now.tried);
		boolean tooSoon = !now.notSent() && now.failures == 0
				&& now.nextTryAtMs > 0L;
		if (refused || tooSoon) {
			if (outbox().remove(channelId, entry.id)) {
				fireEvent(channelId,
						ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			}
			if (tooSoon) {
				throw new org.zerionproject.app.api.channel
						.ChannelReactionTooSoonException();
			}
			throw new DbException();
		}
		boolean untouched = now.failures == 0 && !now.tried;
		throw new org.zerionproject.app.api.channel
				.ChannelRequestQueuedException(untouched
				&& flow == Flow.WAITING, entry.id);
	}

	private long sendNowOrKeep(byte[] channelId,
			ChannelOutboxStore.Entry entry, boolean replaces)
			throws DbException {
		Flow flow = ownerAnswers(channelId);
		if (flow == Flow.NO_ACCESS) {
			throw new org.zerionproject.app.api.channel
					.ChannelNoAccessException();
		}
		Sent sent = flow == Flow.CLEAR ? send(channelId, entry)
				: Sent.UNREACHABLE;
		Long accepted = acceptedPostSeqs.remove(entry.id);
		if (sent == Sent.SENT) {
			if (replaces && outbox().removeLike(channelId, entry.kind,
					entry.postSeq)) {
				fireEvent(channelId,
						ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			}
			return accepted == null ? -1L : accepted;
		}
		if (sent == Sent.WAIT) {
			throw new org.zerionproject.app.api.channel
					.ChannelReactionTooSoonException();
		}
		if (sent == Sent.NO_ACCESS) {
			throw new org.zerionproject.app.api.channel
					.ChannelNoAccessException();
		}
		if (sent == Sent.REFUSED) {
			throw new org.zerionproject.app.api.channel
					.ChannelRefusedException();
		}
		if (sent != Sent.UNREACHABLE) throw new DbException();
		keep(channelId, flow == Flow.CLEAR ? entry.markedTried() : entry,
				replaces, flow == Flow.WAITING);
		return -1L;
	}

	private boolean reactionTooSoon(byte[] channelId,
			ChannelOutboxStore.Entry e) throws DbException {
		return ChannelReactionPolicy.nextTimestamp(heldReaction(channelId,
				e.postSeq, myMemberKey(channelId)),
				clock.currentTimeMillis()) < 0L;
	}

	private Flow ownerAnswers(byte[] channelId) {
		try {
			reviveIfAccessChanged(channelId);
			if (!outbox().hasWaiting(channelId)) return Flow.CLEAR;
			try {
				pullAndApply(channelId, false);
			} catch (DbException e) {
				if (!answeredWithoutAccess(channelId, e)) {
					return Flow.UNANSWERED;
				}
			}
			return flushLocked(channelId);
		} catch (DbException e) {
			return Flow.UNANSWERED;
		}
	}

	private boolean answeredWithoutAccess(byte[] channelId, DbException e)
			throws DbException {
		if (e.getCause() instanceof IOException) return false;
		ChannelState s = store.getChannel(channelId);
		return s != null && needsAccess(s);
	}

	private static boolean needsAccess(ChannelState s) {
		return !s.isPublicChannel() && s.getJoinCapability() != null;
	}

	private void keep(byte[] channelId, ChannelOutboxStore.Entry entry,
			boolean replaces, boolean behind) throws DbException {
		boolean[] kept = new boolean[1];
		underLock(channelId, () -> {
			if (store.getChannel(channelId) == null) {
				throw new DbException();
			}
			if (replaces) {
				outbox().removeLike(channelId, entry.kind, entry.postSeq);
			}
			kept[0] = outbox().add(channelId, entry,
					clock.currentTimeMillis());
		});
		fireEvent(channelId, ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		if (!kept[0]) throw new DbException();
		throw new org.zerionproject.app.api.channel
				.ChannelRequestQueuedException(behind, entry.id);
	}

	private void flushOutboxSafely(byte[] channelId) {
		java.util.concurrent.locks.ReentrantLock lock =
				sendLockFor(channelId);
		try {
			reviveIfAccessChanged(channelId);
			if (!outbox().hasWaiting(channelId) || !lock.tryLock()) return;
		} catch (DbException e) {
			return;
		}
		try {
			flushLocked(channelId);
		} catch (DbException | RuntimeException ignored) {
		} finally {
			lock.unlock();
		}
	}

	private Flow flushLocked(byte[] channelId) throws DbException {
		boolean changed = outbox().age(channelId, clock.currentTimeMillis());
		try {
			List<ChannelOutboxStore.Entry> listedAll = outbox().list(channelId);
			for (ChannelOutboxStore.Entry listed : listedAll) {
				String key = cancelKey(channelId, listed.id);
				if (listed.notSent() || cancelledItems.contains(key)) {
					continue;
				}
				ChannelOutboxStore.Entry e = outbox().get(channelId,
						listed.id);
				if (e == null || e.notSent()) continue;
				if (notDue(e)) return Flow.WAITING;
				if (deleteOutlived(channelId, e)) {
					changed |= outbox().remove(channelId, e.id);
					continue;
				}
				Sent sent = delivered(channelId, e) ? Sent.SENT
						: resend(channelId, e);
				if (sent == Sent.REFUSED && refusedCopyHeld(channelId, e)) {
					sent = Sent.SENT;
				}
				long now = clock.currentTimeMillis();
				if (sent == Sent.SENT) {
					if (e.kind == ChannelQueuedItem.Kind.POST) {
						settleLaterPosts(channelId, e);
					}
					sentItems.put(key, Boolean.TRUE);
					changed |= outbox().remove(channelId, e.id);
				} else if (sent == Sent.REFUSED) {
					changed |= outbox().markNotSent(channelId, e.id, now,
							ChannelQueuedItem.Reason.REFUSED);
				} else if (sent == Sent.NO_ACCESS) {
					String proven = deniedMarks.remove(
							ChannelStore.hex(channelId));
					outbox().markWaitingNotSent(channelId, now,
							ChannelQueuedItem.Reason.NO_ACCESS,
							proven == null ? "" : proven,
							deletingIds(channelId, listedAll));
					changed = true;
					return Flow.NO_ACCESS;
				} else if (sent == Sent.WAIT) {
					outbox().defer(channelId, e.id,
							reactionReadyAt(channelId, e, now));
					return Flow.WAITING;
				} else {
					changed = true;
					if (!outbox().fail(channelId, e.id, now,
							sent == Sent.UNREACHABLE)) {
						return sent == Sent.UNREACHABLE ? Flow.UNANSWERED
								: Flow.WAITING;
					}
				}
			}
			return Flow.CLEAR;
		} finally {
			if (changed) {
				fireEvent(channelId,
						ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			}
		}
	}

	private java.util.Set<Long> deletingIds(byte[] channelId,
			List<ChannelOutboxStore.Entry> listed) {
		java.util.Set<Long> ids = new java.util.HashSet<>();
		for (ChannelOutboxStore.Entry e : listed) {
			if (cancelledItems.contains(cancelKey(channelId, e.id))) {
				ids.add(e.id);
			}
		}
		return ids;
	}

	private boolean deleteOutlived(byte[] channelId,
			ChannelOutboxStore.Entry e) throws DbException {
		return e.kind == ChannelQueuedItem.Kind.DELETE
				&& e.targetExpiresAtMs > 0L
				&& clock.currentTimeMillis() >= e.targetExpiresAtMs
				&& !postTombstoneStore.get(channelId).contains(e.postSeq)
				&& store.posts().getPost(channelId, e.postSeq) == null;
	}

	private String accessMarkOf(byte[] channelId) throws DbException {
		ChannelState s = store.getChannel(channelId);
		return accessMark(s == null ? null : s.getJoinCapability());
	}

	private String accessMark(@Nullable byte[] capability) {
		if (capability == null) return "";
		return org.zerionproject.core.util.StringUtils.toHexString(
				crypto.hash("org.zerionproject/CHANNEL_OUTBOX_ACCESS",
						capability));
	}

	private void reviveIfAccessChanged(byte[] channelId)
			throws DbException {
		if (!outbox().hasDeniedAccess(channelId)) return;
		String mark = accessMarkOf(channelId);
		if (mark.isEmpty()) return;
		if (outbox().reviveAccess(channelId, mark)) {
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		}
	}

	private boolean notDue(ChannelOutboxStore.Entry e) {
		long ahead = e.nextTryAtMs - clock.currentTimeMillis();
		return ahead > 0L && ahead <= ChannelOutboxStore.MAX_DEFER_MS;
	}

	private boolean refusedCopyHeld(byte[] channelId,
			ChannelOutboxStore.Entry e) throws DbException {
		if (e.kind != ChannelQueuedItem.Kind.COMMENT || !e.tried) {
			return false;
		}
		commentStore.sync().setCursor(channelId, null);
		return pullQuietly(channelId) && delivered(channelId, e);
	}

	private void settleLaterPosts(byte[] channelId,
			ChannelOutboxStore.Entry e) throws DbException {
		Long accepted = acceptedPostSeqs.remove(e.id);
		long seq = accepted != null ? accepted
				: matchedPostSeq(channelId, e);
		if (seq >= 0L) outbox().raiseAfterSeq(channelId, e.id, seq);
	}

	private long reactionReadyAt(byte[] channelId,
			ChannelOutboxStore.Entry e, long now) throws DbException {
		ChannelReaction held = heldReaction(channelId, e.postSeq,
				myMemberKey(channelId));
		long at = held == null ? now + ChannelOutboxStore.RETRY_BASE_MS
				: held.getTimestampHourMs() - HOUR_MS;
		return Math.max(now + 1L, at);
	}

	private Sent resend(byte[] channelId, ChannelOutboxStore.Entry e)
			throws DbException {
		try {
			return send(channelId, e);
		} catch (org.zerionproject.app.api.channel
				.ChannelPostTooLongException tooLong) {
			return Sent.REFUSED;
		} catch (org.zerionproject.app.api.channel
				.ChannelSentNotSavedException sentAnyway) {
			return Sent.SENT;
		}
	}

	private Sent send(byte[] channelId, ChannelOutboxStore.Entry e)
			throws DbException {
		switch (e.kind) {
			case COMMENT:
				return sendComment(channelId, e);
			case REACTION:
				return sendReaction(channelId, e);
			case ANNOUNCE:
				return sendAnnounce(channelId, e);
			case POST:
				return sendEditorPost(channelId, e);
			default:
				return sendChange(channelId, e);
		}
	}

	@Nullable
	private byte[] askOwner(ChannelState s, byte[] request)
			throws DbException {
		String onion;
		try {
			onion = confirmedOnion(s);
		} catch (DbException e) {
			if (e.getCause() instanceof IOException) return null;
			throw e;
		}
		try {
			return transport.requestFromOnion(onion, request);
		} catch (IOException e) {
			return null;
		}
	}

	private Sent unanswered(byte[] channelId, ChannelState s,
			ChannelOutboxStore.Entry e, @Nullable byte[] reply)
			throws DbException {
		if (reply == null || !needsAccess(s)) return Sent.UNREACHABLE;
		try {
			pullAndApply(channelId, false);
			return delivered(channelId, e) ? Sent.SENT : Sent.UNREACHABLE;
		} catch (DbException ex) {
			if (ex.getCause() instanceof IOException) {
				return Sent.UNREACHABLE;
			}
		}
		ChannelState trusted = store.getChannel(channelId);
		if (trusted == null || !needsAccess(trusted)
				|| !ownerConfirmsOnion(trusted)) {
			return Sent.UNREACHABLE;
		}
		deniedMarks.put(ChannelStore.hex(channelId),
				accessMark(s.getJoinCapability()));
		return Sent.NO_ACCESS;
	}

	private boolean ownerConfirmsOnion(ChannelState s) {
		String onion = s.getCurrentOnion();
		if (onion.isEmpty()) return false;
		ChannelPullCodec.OnionConfirmed c;
		try {
			c = pullCodec().decodeOnionConfirmed(transport.requestFromOnion(
					onion, pullCodec().encodeConfirmOnionRequest(
							s.getChannelId(), bareOnion(onion))));
		} catch (IOException | RuntimeException e) {
			return false;
		}
		return c.onion.equals(bareOnion(onion))
				&& ownerSignedConfirmation(s, c);
	}

	private boolean ownerSignedConfirmation(ChannelState s,
			ChannelPullCodec.OnionConfirmed c) {
		byte[] channelId = s.getChannelId();
		byte[] localMlDsa = s.getPublisherMlDsaPubKey();
		if (!java.util.Arrays.equals(c.channelId, channelId)
				|| !BARE_ONION_V3.matcher(c.onion).matches()
				|| (localMlDsa != null && localMlDsa.length > 0
				&& !java.util.Arrays.equals(localMlDsa, c.publisherMlDsa))) {
			return false;
		}
		try {
			HybridSignaturePublicKey pub = new HybridSignaturePublicKey(
					s.getPublisherEd25519PubKey(), c.publisherMlDsa);
			return java.util.Arrays.equals(channelId, crypto.hash(
					"org.zerionproject/CHANNEL_ID", pub.getEncoded(), c.salt))
					&& signatures.verifyOnionConfirmation(c.signature,
					codec.onionConfirmationSignedInput(channelId, c.onion),
					pub);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private Sent sendComment(byte[] channelId, ChannelOutboxStore.Entry e)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!discussionStore.isEnabledFor(channelId, e.postSeq)) {
			return Sent.REFUSED;
		}
		KeyPair keys = signingKeys(s);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) keys.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keys.getPrivate();
		byte[] signerEd = pub.getEd25519PublicKey();
		byte[] signerMl = pub.getMlDsaPublicKey();
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		String authorName;
		byte[] sig;
		try {
			authorName = boundedName(pickAuthorName(channelId, signerEd));
			byte[] signedInput = codec.commentSignedInput(channelId,
					e.postSeq, e.id, e.text, authorName, ts);
			sig = signAndClear(priv, (ed, ml) ->
					signatures.signUserComment(signedInput, ed, ml));
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		} finally {
			priv.clear();
		}
		byte[] request;
		try {
			byte[][] ch = buildChallenge(s, channelId);
			request = pullCodec().encodeCommentRequest(channelId, e.postSeq,
					e.id, e.text, authorName, ts, signerEd, signerMl, sig,
					ch == null ? null : ch[0], ch == null ? null : ch[1]);
			if (ch != null) request = proveV2(s, channelId, request, ch[0]);
		} catch (IOException ex) {
			throw new DbException(ex);
		}
		byte[] reply = askOwner(s, request);
		if (reply == null || reply.length == 0) {
			return unanswered(channelId, s, e, reply);
		}
		try {
			if (!pullCodec().decodeCommentAck(reply)) return Sent.REFUSED;
		} catch (IOException ex) {
			return Sent.UNREACHABLE;
		}
		ChannelComment row = new ChannelComment(e.postSeq, e.id, e.text,
				authorName, signerEd, signerMl, ts, sig);
		try {
			underLock(channelId, () -> {
				if (store.getChannel(channelId) == null) {
					throw new DbException();
				}
				bindOwnKey(s, signerEd, signerMl);
				commentStore.putComment(channelId, row);
				fireEvent(channelId,
						ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			});
		} catch (DbException stored) {
			throw new org.zerionproject.app.api.channel
					.ChannelSentNotSavedException(e.id);
		}
		return Sent.SENT;
	}

	private Sent sendReaction(byte[] channelId, ChannelOutboxStore.Entry e)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		KeyPair keys = signingKeys(s);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) keys.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keys.getPrivate();
		byte[] signerEd = pub.getEd25519PublicKey();
		byte[] signerMl = pub.getMlDsaPublicKey();
		long ts;
		byte[] sig;
		try {
			ts = ChannelReactionPolicy.nextTimestamp(
					heldReaction(channelId, e.postSeq, signerEd),
					clock.currentTimeMillis());
			if (ts < 0L) return Sent.WAIT;
			byte[] signedInput = codec.reactionSignedInput(channelId,
					e.postSeq, e.text, ts);
			sig = signAndClear(priv, (ed, ml) ->
					signatures.signUserReaction(signedInput, ed, ml));
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		} finally {
			priv.clear();
		}
		byte[] request;
		try {
			byte[][] ch = buildChallenge(s, channelId);
			request = pullCodec().encodeReactionRequest(channelId, e.postSeq,
					e.text, ts, signerEd, signerMl, sig,
					ch == null ? null : ch[0], ch == null ? null : ch[1]);
			if (ch != null) request = proveV2(s, channelId, request, ch[0]);
		} catch (IOException ex) {
			throw new DbException(ex);
		}
		byte[] reply = askOwner(s, request);
		if (reply == null || reply.length == 0) {
			return unanswered(channelId, s, e, reply);
		}
		try {
			if (!pullCodec().decodeReactionAck(reply)) return Sent.REFUSED;
		} catch (IOException ex) {
			return Sent.UNREACHABLE;
		}
		ChannelReaction reaction = new ChannelReaction(e.postSeq, e.text,
				signerEd, signerMl, ts, sig);
		underLock(channelId, () -> {
			if (store.getChannel(channelId) == null) throw new DbException();
			bindOwnKey(s, signerEd, signerMl);
			reactionStore.putReaction(channelId, reaction);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		});
		return Sent.SENT;
	}

	@Nullable
	private ChannelReaction heldReaction(byte[] channelId, long postSeq,
			byte[] signerEd) throws DbException {
		ChannelReaction mine = null;
		for (ChannelReaction r : reactionStore.getReactions(channelId)) {
			if (r.getPostSeqNum() == postSeq && java.util.Arrays.equals(
					r.getSignerEd25519PubKey(), signerEd)) {
				mine = r;
			}
		}
		return mine;
	}

	private Sent sendAnnounce(byte[] channelId, ChannelOutboxStore.Entry e)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		KeyPair keys = signingKeys(s);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) keys.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keys.getPrivate();
		byte[] signerEd = pub.getEd25519PublicKey();
		byte[] signerMl = pub.getMlDsaPublicKey();
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		byte[] signedInput = codec.announceSignedInput(channelId, e.text,
				ts);
		byte[] sig;
		try {
			sig = signAndClear(priv, (ed, ml) ->
					signatures.signUserAnnounce(signedInput, ed, ml));
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		} finally {
			priv.clear();
		}
		byte[] request;
		try {
			byte[][] ch = buildChallenge(s, channelId);
			request = pullCodec().encodeAnnounceRequest(channelId, e.text,
					ts, signerEd, signerMl, sig,
					ch == null ? null : ch[0], ch == null ? null : ch[1]);
			if (ch != null) request = proveV2(s, channelId, request, ch[0]);
		} catch (IOException ex) {
			throw new DbException(ex);
		}
		byte[] reply = askOwner(s, request);
		if (reply == null || reply.length == 0) {
			return unanswered(channelId, s, e, reply);
		}
		try {
			if (!pullCodec().decodeAnnounceAck(reply)) return Sent.REFUSED;
		} catch (IOException ex) {
			return Sent.UNREACHABLE;
		}
		ChannelSubscriber row = new ChannelSubscriber(e.text, signerEd,
				signerMl, ts, false);
		underLock(channelId, () -> {
			if (store.getChannel(channelId) == null) throw new DbException();
			subscriberStore.putSubscriber(channelId, row);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		});
		return Sent.SENT;
	}

	private Sent sendEditorPost(byte[] channelId,
			ChannelOutboxStore.Entry e) throws DbException {
		for (int attempt = 0; attempt < MAX_SUBMIT_ATTEMPTS; attempt++) {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (editorCertificate(s) == null) return Sent.REFUSED;
			KeyPair keys = memberKeys(channelId);
			HybridSignaturePublicKey pub =
					(HybridSignaturePublicKey) keys.getPublic();
			HybridSignaturePrivateKey memberPriv =
					(HybridSignaturePrivateKey) keys.getPrivate();
			ChannelPost post;
			try {
				post = buildPost(s, channelId, e.text, e.ttlSeconds,
						Collections.<ChannelPost.ChannelAttachment>emptyList(),
						memberPriv, pub.getEd25519PublicKey(),
						pub.getMlDsaPublicKey());
			} finally {
				memberPriv.clear();
			}
			byte[] request;
			try {
				byte[][] ch = buildChallenge(s, channelId);
				request = pullCodec().encodeSubmitPostRequest(channelId,
						post, ch == null ? null : ch[0],
						ch == null ? null : ch[1]);
				if (ch != null) {
					request = proveV2(s, channelId, request, ch[0]);
				}
			} catch (IOException ex) {
				throw new DbException(ex);
			}
			byte[] reply = askOwner(s, request);
			if (reply == null) return Sent.UNREACHABLE;
			String status = pullCodec().decodeSubmitPostAck(reply);
			if (ChannelConstants.SUBMIT_STATUS_OK.equals(status)) {
				acceptedPostSeqs.put(e.id, post.getSeqNum());
				pullQuietly(channelId);
				return Sent.SENT;
			}
			if (!ChannelConstants.SUBMIT_STATUS_STALE.equals(status)) {
				return Sent.REFUSED;
			}
			if (!pullQuietly(channelId)) return Sent.UNREACHABLE;
		}
		return Sent.LATER;
	}

	private Sent sendChange(byte[] channelId, ChannelOutboxStore.Entry e)
			throws DbException {
		String op = changeOp(e.kind);
		for (int attempt = 0; attempt < MAX_SUBMIT_ATTEMPTS; attempt++) {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (editorCertificate(s) == null) return Sent.REFUSED;
			ChannelPost target = null;
			if (e.kind != ChannelQueuedItem.Kind.DELETE
					|| store.posts().getPost(channelId, e.postSeq) != null) {
				try {
					target = changeTarget(s, e.postSeq);
				} catch (DbException ex) {
					return Sent.REFUSED;
				}
				if (!ownDelegatePost(channelId, target)) return Sent.REFUSED;
			}
			KeyPair keys = memberKeys(channelId);
			HybridSignaturePublicKey pub =
					(HybridSignaturePublicKey) keys.getPublic();
			HybridSignaturePrivateKey priv =
					(HybridSignaturePrivateKey) keys.getPrivate();
			byte[] signerEd = pub.getEd25519PublicKey();
			byte[] signerMl = pub.getMlDsaPublicKey();
			long base;
			byte[] sig;
			long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
			try {
				base = ChannelConstants.CHANGE_EDIT.equals(op)
						&& target != null ? currentVersionOf(s, target) : -1L;
				byte[] signedInput = codec.postChangeSignedInput(channelId,
						e.postSeq, op, base, e.commentsOn, e.text, ts);
				sig = signAndClear(priv, (ed, ml) ->
						signatures.signUserPostChange(signedInput, ed, ml));
			} catch (GeneralSecurityException ex) {
				throw new DbException(ex);
			} finally {
				priv.clear();
			}
			byte[] request;
			try {
				byte[][] ch = buildChallenge(s, channelId);
				request = pullCodec().encodeChangePostRequest(channelId,
						e.postSeq, op, base, e.commentsOn, e.text, ts,
						signerEd, signerMl, sig, ch == null ? null : ch[0],
						ch == null ? null : ch[1]);
				if (ch != null) {
					request = proveV2(s, channelId, request, ch[0]);
				}
			} catch (IOException ex) {
				throw new DbException(ex);
			}
			byte[] reply = askOwner(s, request);
			if (reply == null) return Sent.UNREACHABLE;
			String status = pullCodec().decodeChangePostAck(reply);
			if (ChannelConstants.SUBMIT_STATUS_OK.equals(status)) {
				pullQuietly(channelId);
				return Sent.SENT;
			}
			if (!ChannelConstants.SUBMIT_STATUS_STALE.equals(status)) {
				return Sent.REFUSED;
			}
			if (!pullQuietly(channelId)) return Sent.UNREACHABLE;
		}
		return Sent.LATER;
	}

	private boolean pullQuietly(byte[] channelId) {
		try {
			pullAndApply(channelId, false);
			return true;
		} catch (DbException e) {
			return false;
		}
	}

	private boolean ownDelegatePost(byte[] channelId, ChannelPost target)
			throws DbException {
		return target.signedByDelegate() && java.util.Arrays.equals(
				target.getDelegateSignerEd25519PubKey(),
				myMemberKey(channelId));
	}

	private byte[] myMemberKey(byte[] channelId) throws DbException {
		return publicKeyOf(memberKeys(channelId)).getEd25519PublicKey();
	}

	private static ChannelQueuedItem.Kind changeKind(String op) {
		if (ChannelConstants.CHANGE_EDIT.equals(op)) {
			return ChannelQueuedItem.Kind.EDIT;
		}
		if (ChannelConstants.CHANGE_DELETE.equals(op)) {
			return ChannelQueuedItem.Kind.DELETE;
		}
		return ChannelQueuedItem.Kind.COMMENTS_SETTING;
	}

	private static String changeOp(ChannelQueuedItem.Kind kind) {
		if (kind == ChannelQueuedItem.Kind.EDIT) {
			return ChannelConstants.CHANGE_EDIT;
		}
		if (kind == ChannelQueuedItem.Kind.DELETE) {
			return ChannelConstants.CHANGE_DELETE;
		}
		return ChannelConstants.CHANGE_COMMENTS;
	}

	private void checkEditorBodyFits(ChannelState s, byte[] channelId,
			String body) throws DbException {
		if (s.isPublicChannel()) return;
		byte[] kContent = s.getContentKey();
		if (kContent == null) throw new DbException();
		byte[] ct;
		try {
			ct = contentKey.encryptBodyV2(kContent, channelId, 0L,
					freshBytes(ChannelConstants.POST_SALT_BYTES), body);
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		if (java.util.Base64.getEncoder().withoutPadding()
				.encodeToString(ct).length()
				> ChannelConstants.MAX_POST_BODY_CHARS) {
			throw new org.zerionproject.app.api.channel
					.ChannelPostTooLongException();
		}
	}

	private boolean delivered(byte[] channelId, ChannelOutboxStore.Entry e)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		switch (e.kind) {
			case COMMENT: {
				byte[] me = myMemberKey(channelId);
				for (ChannelComment c : commentStore.getComments(channelId)) {
					if (c.getCommentId() == e.id && java.util.Arrays.equals(
							c.getAuthorEd25519PubKey(), me)) {
						return true;
					}
				}
				return false;
			}
			case REACTION: {
				ChannelReaction held = heldReaction(channelId, e.postSeq,
						myMemberKey(channelId));
				return held != null && held.getEmoji().equals(e.text);
			}
			case POST:
				return e.tried && matchedPostSeq(channelId, e) >= 0L;
			case EDIT: {
				ChannelPost target =
						store.posts().getPost(channelId, e.postSeq);
				if (target == null) return false;
				List<ChannelPost> edits = editsOf(s, target);
				if (edits.isEmpty()) return false;
				ChannelPostMarkers.Edit last = editOf(s, contentKeys(s),
						edits.get(edits.size() - 1));
				return last != null && e.text.equals(last.text);
			}
			case DELETE:
				return postTombstoneStore.get(channelId).contains(e.postSeq);
			case COMMENTS_SETTING:
				return discussionStore.isEnabledFor(channelId, e.postSeq)
						== e.commentsOn;
			default:
				return false;
		}
	}

	private long matchedPostSeq(byte[] channelId,
			ChannelOutboxStore.Entry e) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) return -1L;
		byte[] me = myMemberKey(channelId);
		List<byte[]> keys = contentKeys(s);
		long after = e.afterSeq;
		int page = (int) ChannelConstants.PULL_BATCH_MAX_POSTS;
		for (int round = 0; round < MAX_PULL_ROUNDS; round++) {
			List<ChannelPost> next =
					store.posts().getPostsAfter(channelId, after, page);
			if (next.isEmpty()) return -1L;
			for (ChannelPost p : next) {
				after = p.getSeqNum();
				if (p.signedByDelegate() && java.util.Arrays.equals(
						p.getDelegateSignerEd25519PubKey(), me)
						&& e.text.equals(openedBody(s, keys, p))) {
					return p.getSeqNum();
				}
			}
		}
		return -1L;
	}

	private void fireEvent(byte[] channelId,
			ChannelStateChangedEvent.Kind kind) {
		eventBus.broadcast(new ChannelStateChangedEvent(channelId, kind));
	}

	private void validateNameAndDescription(String name,
			String description) throws DbException {
		if (name.isEmpty()
				|| name.length() > ChannelConstants.MAX_CHANNEL_NAME_CHARS
				|| description.length()
				> ChannelConstants.MAX_CHANNEL_DESCRIPTION_CHARS) {
			throw new DbException();
		}
	}

	private void validatePostBody(String body) throws DbException {
		if (body.isEmpty()
				|| body.length() > ChannelConstants.MAX_POST_BODY_CHARS) {
			throw new DbException();
		}
	}

	private byte[] freshBytes(int len) {
		byte[] b = new byte[len];
		random.nextBytes(b);
		return b;
	}

	private ChannelState bumpManifestSeq(ChannelState s) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), s.getCurrentOnion(),
				s.getManifestSeq() + 1L, s.weArePublisher(),
				s.getHighestKnownPostSeq(),
				s.getContentKeyHash(), s.getContentKey(),
				s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(), s.getOnionPrivateKey(),
				s.getPinnedPostSeq(),
				s.requiresApproval(),
				s.getRetiredDelegations());
	}

	private ChannelState withSeq(ChannelState s, long newHighSeq) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), s.getCurrentOnion(),
				s.getManifestSeq(), s.weArePublisher(), newHighSeq,
				s.getContentKeyHash(), s.getContentKey(),
				s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(),
				s.getOnionPrivateKey(),
				s.getPinnedPostSeq(),
				s.requiresApproval(),
				s.getRetiredDelegations());
	}

	private void clearReturned(byte[] b) {
		java.util.Arrays.fill(b, (byte) 0);
	}
}
