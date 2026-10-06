package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
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
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.app.api.channel.event.ChannelCommentReceivedEvent;
import org.zerionproject.app.api.channel.event.ChannelPostReceivedEvent;
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

	private static final class PollState {
		long nextDueAt;
		long intervalMs = PULL_MIN_INTERVAL_MS;
		volatile long lastActivityAt;
		volatile long failingSince;
		volatile int nextFallback;
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
			pullDue(channelId, pullKey);
			boolean active = clock.currentTimeMillis() - poll.lastActivityAt
					< PULL_ACTIVE_WINDOW_MS;
			poll.intervalMs = active ? PULL_MIN_INTERVAL_MS
					: Math.min(PULL_MAX_INTERVAL_MS,
					poll.intervalMs + PULL_BACKOFF_STEP_MS);
			poll.nextDueAt = now + (long) (poll.intervalMs
					* (0.5 + random.nextDouble()));
			nextWake = Math.min(nextWake, poll.nextDueAt);
		}
		return nextWake;
	}

	long pollIntervalMs(byte[] channelId) {
		PollState p = polls.get(ChannelStore.hex(channelId));
		return p == null ? PULL_MIN_INTERVAL_MS : p.intervalMs;
	}

	private void pullDue(byte[] channelId, String pullKey) {
		if (!inFlightPulls.add(pullKey)) return;
		try {
			ioExecutor.execute(() -> {
				try {
					pullAndApply(channelId, false);
				} catch (DbException ignored) {
				} finally {
					inFlightPulls.remove(pullKey);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException re) {
			inFlightPulls.remove(pullKey);
		}
	}

	private void scheduleNextRefresh(long delayMs) {
		try {
			taskScheduler.schedule(this::refreshAndReschedule, ioExecutor,
					delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
		}
	}

	private void refreshAndReschedule() {
		long nextWake = clock.currentTimeMillis() + PULL_MAX_INTERVAL_MS;
		try {
			nextWake = refreshAllSubscriptionsSafely();
		} finally {
			scheduleNextRefresh(Math.max(MIN_REFRESH_TICK_MS,
					nextWake - clock.currentTimeMillis()));
		}
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
					rotateOnionLocked(channelId, false);
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

	private void rotateOnionLocked(byte[] channelId, boolean revocation)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null || !s.weArePublisher()) return;
		String key = ChannelStore.hex(channelId);
		if (revocation) {
			ChannelOnionStore.Record r = store.onions().get(channelId);
			for (ChannelOnionStore.Retiring e : r.retiring) {
				closeQuietly(retiringServers.remove(e.onion));
			}
			r.retiring.clear();
			r.nextRotationMs = clock.currentTimeMillis() + rotationInterval();
			ChannelTransport.ChannelServer current = boundServers.remove(key);
			store.putChannel(withoutOnion(s), onionRecordWrite(channelId, r));
			closeQuietly(current);
			bindPublisherServer(channelId);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			return;
		}
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
	}

	@Override
	public void eventOccurred(Event e) {
		try {
			if (e instanceof B4OwnRotationCompletedEvent) {
				ioExecutor.execute(this::ensurePublisherServersBound);
			} else if (e instanceof TransportActiveEvent) {
				TransportActiveEvent t = (TransportActiveEvent) e;
				if (TorConstants.ID.equals(t.getTransportId())) {
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
		if (ChannelConstants.WIRE_TYPE_SUBMIT_POST.equals(wireType)) {
			java.util.concurrent.locks.ReentrantLock l = lockFor(channelId);
			l.lock();
			try {
				return handleSubmitPost(channelId, requestBytes);
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
		boolean legacy = req.version < ChannelConstants.PROTOCOL_VERSION;
		java.util.List<ChannelPost> toSend = store.posts().getPostsAfter(
				channelId, req.sinceSeqNum,
				(int) ChannelConstants.PULL_BATCH_MAX_POSTS);
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
		byte[] manifestSig = signLatestManifest(s, discussions);
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
					s.getPublisherMlDsaPubKey(), manifestSig,
					discussions, toSend, envelope,
					java.util.Collections.<String>emptyList(),
					reactions, comments, null, null);
		}
		ChannelItemSync.Delta rd = reactionStore.sync().since(channelId,
				req.reactionsCursor);
		ChannelItemSync.Delta cd = commentStore.sync().since(channelId,
				req.commentsCursor);
		java.util.List<ChannelReaction> sendReactions = new ArrayList<>();
		for (ChannelReaction r : reactions) {
			if (rd.full || rd.changed.contains(
					ChannelReactionStore.keyOf(r))) {
				sendReactions.add(r);
			}
		}
		java.util.List<ChannelComment> sendComments = new ArrayList<>();
		for (ChannelComment c : comments) {
			if (cd.full || cd.changed.contains(
					ChannelCommentStore.keyOf(c))) {
				sendComments.add(c);
			}
		}
		return pullProtocol.buildResponseAsPublisher(s,
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), manifestSig,
				discussions, toSend, envelope,
				java.util.Collections.<String>emptyList(),
				sendReactions, sendComments,
				new ChannelPullCodec.ItemSync(rd.cursor(), rd.full, rd.removed),
				new ChannelPullCodec.ItemSync(cd.cursor(), cd.full,
						cd.removed));
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
			boolean discussionsEnabled) {
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
			byte[] privEncoded = store.getPublisherPrivKey(
					s.getChannelId());
			if (privEncoded == null) return new byte[0];
			HybridSignaturePrivateKey priv =
					new HybridSignaturePrivateKey(privEncoded);
			return signatures.signManifest(signedInput, priv);
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
		if (version >= ChannelConstants.PROTOCOL_VERSION) {
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
		pullAndApply(channelId, true);
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
		pullAndApply(channelId, false);
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

	private void pullAndApply(byte[] channelId, boolean isBootstrap)
			throws DbException {
		for (int round = 0; round < MAX_PULL_ROUNDS; round++) {
			int accepted = pullOnce(channelId, isBootstrap && round == 0);
			if (accepted < ChannelConstants.PULL_BATCH_MAX_POSTS) return;
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
			if (!r.mergedState.getCurrentOnion().equals(
					cur.getCurrentOnion())) {
				store.onions().remember(channelId, cur.getCurrentOnion());
			}
			store.putChannel(r.mergedState);
			withholdNewlyRevoked(channelId, cur, r.mergedState);
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
			boolean reactionsChanged = applyIncomingReactions(channelId,
					r.reactions, r.reactionsSync);
			boolean commentsChanged = applyIncomingComments(channelId,
					r.comments, r.commentsSync);
			if (caughtUp) {
				keepCursor(reactionStore.sync(), channelId, r.reactionsSync);
				keepCursor(commentStore.sync(), channelId, r.commentsSync);
			}
			if (ChannelConstants.DISCUSSIONS_IN_MANIFEST) {
				discussionStore.setEnabled(channelId,
						r.discussionsEnabled);
			}
			if (!r.acceptedPosts.isEmpty() || reactionsChanged
					|| commentsChanged) {
				PollState poll = polls.get(ChannelStore.hex(channelId));
				if (poll != null) {
					poll.lastActivityAt = clock.currentTimeMillis();
				}
			}
			return r.acceptedPosts.size();
		} finally {
			lock.unlock();
		}
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
			ChannelComment k = known.get(c.getCommentId());
			if (k == null || !ChannelCommentPolicy.sameComment(k, c)) {
				if (!verifiedComment(channelId, c)) continue;
				added.add(c.getParentPostSeqNum());
			}
			held.remove(c.getCommentId());
			held.put(c.getCommentId(), c);
		}
		java.util.List<ChannelComment> next =
				ChannelCommentPolicy.fitToCeilings(
						new ArrayList<>(held.values()));
		if (ChannelCommentPolicy.sameSet(next, stored)) return false;
		commentStore.setComments(channelId, next);
		for (Long parent : new java.util.LinkedHashSet<>(added)) {
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
		HybridSignaturePublicKey pub = new HybridSignaturePublicKey(
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey());
		if (!signatures.verifyTombstone(tomb.hybridSig, signedInput, pub)) {
			return false;
		}
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
			ChannelReaction k = known.get(reactionKey(r));
			if (k == null || !ChannelReactionPolicy.sameReaction(k, r)) {
				if (!verifiedReaction(channelId, r)) continue;
			}
			held.remove(reactionKey(r));
			held.put(reactionKey(r), r);
		}
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
					: signTombstone(s);
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
		myApplicationsStore.remove(channelId);
		postTombstoneStore.removeAll(channelId);
		selfAnnounceStore.remove(channelId);
		discussionStore.remove(channelId);
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
		byte[] privEncoded = store.getPublisherPrivKey(s.getChannelId());
		if (privEncoded == null) return null;
		byte[] hybridSig;
		try {
			HybridSignaturePrivateKey priv =
					new HybridSignaturePrivateKey(privEncoded);
			hybridSig = signatures.signTombstone(signedInput, priv);
		} catch (GeneralSecurityException e) {
			return null;
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
	public void leaveChannel(byte[] channelId) throws DbException {
		deleteChannel(channelId);
	}

	@Override
	public void publishPost(byte[] channelId, String body, long ttlSeconds)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) {
			submitAsEditor(channelId, body, ttlSeconds);
			return;
		}
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			publishPostLocked(channelId, body, ttlSeconds);
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
		byte[] mine = ((HybridSignaturePublicKey) memberKeys(
				s.getChannelId()).getPublic()).getEd25519PublicKey();
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

	private void submitAsEditor(byte[] channelId, String body,
			long ttlSeconds) throws DbException {
		validatePostBody(body);
		for (int attempt = 0; attempt < MAX_SUBMIT_ATTEMPTS; attempt++) {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (editorCertificate(s) == null) throw new DbException();
			KeyPair keys = memberKeys(channelId);
			HybridSignaturePublicKey pub =
					(HybridSignaturePublicKey) keys.getPublic();
			ChannelPost post = buildPost(s, channelId, body, ttlSeconds,
					Collections.<ChannelPost.ChannelAttachment>emptyList(),
					(HybridSignaturePrivateKey) keys.getPrivate(),
					pub.getEd25519PublicKey(), pub.getMlDsaPublicKey());
			String status;
			try {
				byte[][] ch = buildChallenge(s, channelId);
				byte[] request = pullCodec().encodeSubmitPostRequest(
						channelId, post, ch == null ? null : ch[0],
						ch == null ? null : ch[1]);
				if (ch != null) {
					request = proveV2(s, channelId, request, ch[0]);
				}
				status = pullCodec().decodeSubmitPostAck(
						transport.requestFromOnion(s.getCurrentOnion(),
								request));
			} catch (IOException e) {
				throw new DbException(e);
			}
			if (ChannelConstants.SUBMIT_STATUS_OK.equals(status)) {
				pullAndApply(channelId, false);
				return;
			}
			if (!ChannelConstants.SUBMIT_STATUS_STALE.equals(status)) {
				throw new DbException();
			}
			pullAndApply(channelId, false);
		}
		throw new DbException();
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
		return body.startsWith(ChannelConstants.TOMBSTONE_PREFIX);
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

	private void publishPostLocked(byte[] channelId, String body,
			long ttlSeconds) throws DbException {
		publishPostLocked(channelId, body, ttlSeconds,
				Collections.<ChannelPost.ChannelAttachment>emptyList(),
				Collections.<String, byte[]>emptyMap());
	}

	private void publishPostLocked(byte[] channelId, String body,
			long ttlSeconds,
			List<ChannelPost.ChannelAttachment> attachments,
			java.util.Map<String, byte[]> blobsToStore)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		validatePostBody(body);
		byte[] privEncoded = store.getPublisherPrivKey(channelId);
		if (privEncoded == null) throw new DbException();
		HybridSignaturePrivateKey hybridPriv =
				new HybridSignaturePrivateKey(privEncoded);
		ChannelPost post = buildPost(s, channelId, body, ttlSeconds,
				attachments, hybridPriv, null, null);
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
		store.putChannel(updated);
		eventBus.broadcast(new ChannelPostReceivedEvent(channelId, nextSeq,
				true));
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

	@Override
	public List<ChannelPost> getRecentPosts(byte[] channelId, long limit)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		List<ChannelPost> all = store.posts().getLatestPosts(channelId,
				(int) Math.min(Integer.MAX_VALUE, Math.max(0L, limit)));
		List<byte[]> keys = s == null ? Collections.<byte[]>emptyList()
				: contentKeys(s);
		boolean encrypted = !keys.isEmpty();

		String channelIdHex = ChannelStore.hex(channelId);
		java.util.Set<Long> deletedSeqs = new java.util.HashSet<>(
				postTombstoneStore.get(channelId));
		List<ChannelPost> decoded = new ArrayList<>(all.size());
		for (ChannelPost p : all) {
			ChannelPost view = encrypted ? decryptForDisplay(p, keys) : p;
			if (view == null) continue;
			decoded.add(view);
			if (view.signedByDelegate()) continue;
			Long target = parseTombstoneTarget(view.getBody(),
					channelIdHex);
			if (target != null && deletedSeqs.add(target)) {
				try {
					postTombstoneStore.add(channelId, target);
				} catch (DbException ignored) {
				}
			}
		}

		List<ChannelPost> visible = new ArrayList<>(decoded.size());
		for (ChannelPost p : decoded) {
			if (!p.signedByDelegate() && parseTombstoneTarget(p.getBody(),
					channelIdHex) != null) {
				continue;
			}
			if (deletedSeqs.contains(p.getSeqNum())) {
				visible.add(withDeletedMarker(p));
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
		String prefix = ChannelConstants.TOMBSTONE_PREFIX
				+ channelIdHex + ":";
		if (!body.startsWith(prefix)) return null;
		String rest = body.substring(prefix.length());
		int colon = rest.indexOf(':');
		if (colon <= 0) return null;
		try {
			return Long.parseLong(rest.substring(0, colon));
		} catch (NumberFormatException e) {
			return null;
		}
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
		if (s.weArePublisher()) {
			keys.addAll(store.getRetiredContentKeys(s.getChannelId()));
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
		if (oldContentKey == null) {
			store.putChannel(updated);
		} else {
			store.putChannel(updated,
					store.retireContentKey(channelId, oldContentKey));
		}
		rotateOnionLocked(channelId, true);
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
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
		long validFrom = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		long seq = s.getNextDelegationSeq();
		byte[] signedInput = codec.delegationSignedInput(channelId,
				delegateeEd25519PubKey, delegateeMlDsaPubKey,
				validFrom, validUntilHourMs, seq);
		byte[] privEncoded = store.getPublisherPrivKey(channelId);
		if (privEncoded == null) throw new DbException();
		org.zerionproject.core.api.crypto.HybridSignaturePrivateKey
				hybridPriv = new org.zerionproject.core.api.crypto
				.HybridSignaturePrivateKey(privEncoded);
		byte[] sig;
		try {
			sig = signatures.signDelegation(signedInput, hybridPriv);
		} catch (java.security.GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		ChannelDelegationCert cert = new ChannelDelegationCert(channelId,
				delegateeEd25519PubKey, delegateeMlDsaPubKey,
				validFrom, validUntilHourMs, seq, sig);
		java.util.List<ChannelDelegationCert> next =
				new java.util.ArrayList<>(s.getActiveDelegations());
		next.add(cert);
		ChannelState updated = withDelegations(s, next,
				s.getRevokedDelegationSeqs(), seq + 1L);
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
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (!s.weArePublisher()) throw new DbException();
			String body = ChannelConstants.TOMBSTONE_PREFIX
					+ ChannelStore.hex(channelId) + ":" + seqNum + ":D";
			boolean autoUnpin = s.getPinnedPostSeq() == seqNum;
			postTombstoneStore.add(channelId, seqNum);
			publishPostLocked(channelId, body, 0L);
			dropDeletedPost(channelId, seqNum);
			if (autoUnpin) {
				setPinnedPostSeqLocked(channelId,
						ChannelState.NO_PINNED_POST);
			}
		} finally {
			lock.unlock();
		}
	}

	private void dropDeletedPost(byte[] channelId, long target)
			throws DbException {
		List<ChannelPost> gone = store.posts().remove(channelId,
				Collections.singletonList(target), false);
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
	}

	private void applyDeletionMark(ChannelState s, ChannelPost post)
			throws DbException {
		if (post.signedByDelegate()) return;
		String body = post.getBody();
		if (!s.isPublicChannel()) {
			List<byte[]> keys = contentKeys(s);
			if (keys.isEmpty()) return;
			String opened;
			try {
				opened = decryptWithAnyKey(keys, post,
						java.util.Base64.getDecoder().decode(body));
			} catch (IllegalArgumentException e) {
				return;
			}
			if (opened == null) return;
			body = opened;
		}
		Long target = parseTombstoneTarget(body,
				ChannelStore.hex(s.getChannelId()));
		if (target == null || target >= post.getSeqNum()) return;
		postTombstoneStore.add(s.getChannelId(), target);
		dropDeletedPost(s.getChannelId(), target);
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
		java.util.List<ChannelPost.ChannelAttachment> wireAttachments =
				new ArrayList<>(attachments.size());
		java.util.Map<String, byte[]> blobsToStore =
				new java.util.LinkedHashMap<>();
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		boolean closed = !s.isPublicChannel();
		byte[] kContent = s.getContentKey();
		if (closed && kContent == null) throw new DbException();
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
			byte[] wrappedKey;
			if (closed) {
				try {
					wrappedKey = contentKey.wrapContentKey(kContent,
							channelId, perAttKey);
				} catch (GeneralSecurityException ex) {
					throw new DbException(ex);
				}
			} else {
				wrappedKey = perAttKey;
			}
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
			wireAttachments.add(new ChannelPost.ChannelAttachment(
					blobHash, spec.getPlaintextBytes().length,
					spec.getMimeType(), wrappedKey,
					spec.getCaptionUtf8(), thumbWire));
			blobsToStore.put(
					java.util.Base64.getEncoder().withoutPadding()
							.encodeToString(blobHash),
					encryptedBlob);
		}
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			publishPostLocked(channelId, body, ttlSeconds,
					wireAttachments, blobsToStore);
		} finally {
			lock.unlock();
		}
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
					s.getCurrentOnion(), reqBytes);
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
		String trimmed = body.trim();
		if (trimmed.isEmpty()
				|| trimmed.length()
						> ChannelConstants.MAX_COMMENT_BODY_CHARS) {
			throw new DbException();
		}
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!discussionStore.isEnabled(channelId)) throw new DbException();
		KeyPair keys = signingKeys(s);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) keys.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keys.getPrivate();
		byte[] signerEd = pub.getEd25519PublicKey();
		byte[] signerMl = pub.getMlDsaPublicKey();
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		String authorName = boundedName(pickAuthorName(channelId, signerEd));
		long commentId = random.nextLong();
		byte[] signedInput = codec.commentSignedInput(channelId,
				parentPostSeqNum, commentId, trimmed, authorName, ts);
		byte[] sig;
		try {
			sig = signatures.signUserComment(signedInput,
					priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		ChannelComment row =
				new ChannelComment(
						parentPostSeqNum, commentId, trimmed, authorName,
						signerEd, signerMl, ts, sig);
		if (s.weArePublisher()) {
			commentStore.putComment(channelId, row);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			return;
		}
		try {
			byte[][] ch = buildChallenge(s, channelId);
			byte[] reqBytes = pullCodec().encodeCommentRequest(
					channelId, parentPostSeqNum, commentId, trimmed,
					authorName, ts, signerEd, signerMl, sig,
					ch == null ? null : ch[0], ch == null ? null : ch[1]);
			if (ch != null) reqBytes = proveV2(s, channelId, reqBytes, ch[0]);
			byte[] ack = transport.requestFromOnion(
					s.getCurrentOnion(), reqBytes);
			if (!pullCodec().decodeCommentAck(ack)) {
				throw new DbException();
			}
			commentStore.putComment(channelId, row);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} catch (IOException ex) {
			throw new DbException(ex);
		}
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
			byte[] privEncoded = store.getPublisherPrivKey(s.getChannelId());
			if (privEncoded == null) throw new DbException();
			return new KeyPair(new HybridSignaturePublicKey(
					s.getPublisherEd25519PubKey(),
					s.getPublisherMlDsaPubKey()),
					new HybridSignaturePrivateKey(privEncoded));
		}
		return memberKeys(s.getChannelId());
	}

	private KeyPair memberKeys(byte[] channelId) throws DbException {
		LocalAuthor me = identityManager.getLocalAuthor();
		return ChannelMemberKeys.derive(crypto,
				me.getPrivateKey().getEncoded(), channelId);
	}

	@Override
	public byte[] getMyChannelPublicKey(byte[] channelId) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		return signingKeys(s).getPublic().getEncoded();
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
			discussionStore.setEnabled(channelId, enabled);
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
			if (!java.util.Arrays.equals(req.channelId, channelId)) {
				return safeCommentAck(false);
			}
			if (!discussionStore.isEnabled(channelId)) {
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
		String trimmed = displayName.trim();
		if (trimmed.isEmpty()
				|| trimmed.getBytes(
						java.nio.charset.StandardCharsets.UTF_8).length
						> ChannelConstants.MAX_DISPLAY_NAME_BYTES) {
			throw new DbException();
		}
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) throw new DbException();
			if (s.weArePublisher()) throw new DbException();
			ChannelMyApplicationsStore.MyApplication existing =
					myApplicationsStore.get(channelId);
			if (existing != null
					&& existing.status == ApplicationStatus.PENDING) {
				return;
			}
			KeyPair ephKp = crypto.generateHybridAgreementKeyPair();
			byte[] ephPub = ephKp.getPublic().getEncoded();
			byte[] ephPriv = ephKp.getPrivate().getEncoded();
			KeyPair keys = memberKeys(channelId);
			HybridSignaturePublicKey pub =
					(HybridSignaturePublicKey) keys.getPublic();
			HybridSignaturePrivateKey priv =
					(HybridSignaturePrivateKey) keys.getPrivate();
			byte[] signerEd = pub.getEd25519PublicKey();
			byte[] signerMl = pub.getMlDsaPublicKey();
			long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
			byte[] signedInput = codec.applicationSignedInput(channelId,
					trimmed, ts, ephPub);
			byte[] sig;
			try {
				sig = signatures.signUserApplication(signedInput,
						priv.getEd25519Component(),
						priv.getMlDsaPrivateKey());
			} catch (GeneralSecurityException ex) {
				throw new DbException(ex);
			}
			myApplicationsStore.put(channelId,
					new ChannelMyApplicationsStore.MyApplication(
							trimmed, ephPriv, ephPub, ts,
							ApplicationStatus.PENDING, true));
			try {
				byte[] reqBytes = pullCodec().encodeApplyRequest(channelId,
						trimmed, ts, signerEd, signerMl, ephPub, sig);
				transport.requestFromOnion(s.getCurrentOnion(), reqBytes);
			} catch (IOException ignored) {
			}
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} finally {
			lock.unlock();
		}
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
		return applicationStore.getApplications(channelId);
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
			byte[] ephPub = app.getApplicantEphemeralAgreementPub();
			org.zerionproject.core.api.crypto.KeyParser parser =
					crypto.getHybridAgreementKeyParser();
			org.zerionproject.core.api.crypto.PublicKey ephPubKey;
			try {
				ephPubKey = parser.parsePublicKey(ephPub);
			} catch (GeneralSecurityException ex) {
				throw new DbException(ex);
			}
			org.zerionproject.core.api.crypto.HybridEncapsulationResult
					encap;
			try {
				encap = crypto.hybridEncapsulate(ephPubKey);
			} catch (GeneralSecurityException ex) {
				throw new DbException(ex);
			}
			byte[] sharedSecretCopy = encap.getSharedSecret();
			byte[] envelope;
			try {
				envelope = wrapApprovalCapability(channelId,
						sharedSecretCopy, capability);
			} catch (GeneralSecurityException ex) {
				java.util.Arrays.fill(sharedSecretCopy, (byte) 0);
				encap.clearSecret();
				throw new DbException(ex);
			}
			java.util.Arrays.fill(sharedSecretCopy, (byte) 0);
			encap.clearSecret();
			applicationStore.putApplication(channelId,
					new ChannelApplication(app.getDisplayName(),
							app.getApplicantEd25519(),
							app.getApplicantMlDsa(), ephPub,
							app.getAppliedAtHourMs(),
							ChannelApplication.Status.APPROVED,
							encap.getCiphertext(), envelope));
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.APPLICANT_APPROVED);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} finally {
			lock.unlock();
		}
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
		if (app == null) return ApplicationStatus.NOT_APPLIED;
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
			if (!writeBudget.hasRoom(budgetKey, now)) {
				return safeApplyAck(false);
			}
			if (subscriberStore.isBanned(channelId, req.signerEd25519)) {
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
					&& mine.getStatus() != ChannelApplication.Status.DENIED
					&& (java.util.Arrays.equals(
							mine.getApplicantEphemeralAgreementPub(),
							req.ephemeralAgreementPub)
					|| req.timestampHourMs < mine.getAppliedAtHourMs())) {
				return safeApplyAck(true);
			}
			boolean addsPending = mine == null
					|| mine.getStatus() == ChannelApplication.Status.APPROVED;
			if (addsPending && pendingCount
					>= ChannelConstants.MAX_PENDING_APPLICATIONS) {
				return safeApplyAck(false);
			}
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
			if (!java.util.Arrays.equals(req.channelId, channelId)) {
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
				return safeApprovalResponse("DENIED", null, null);
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
		} catch (IOException | DbException ex) {
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
		if (my.status != ApplicationStatus.PENDING) return;
		String key = ChannelStore.hex(channelId);
		long now = clock.currentTimeMillis();
		Long last = lastApprovalPollMs.get(key);
		if (last != null
				&& now - last < APPROVAL_POLL_MIN_INTERVAL_MS) {
			return;
		}
		lastApprovalPollMs.put(key, now);
		try {
			ChannelState s = store.getChannel(channelId);
			if (s == null) return;
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
					sig = signatures.signUserCheckApproval(signedInput,
							priv.getEd25519Component(),
							priv.getMlDsaPrivateKey());
				} else {
					LocalAuthor me = identityManager.getLocalAuthor();
					signerEd = me.getPublicKey().getEncoded();
					byte[] mlDsaPub =
							identityManager.getLocalMlDsaSigPublicKey();
					signerMl = mlDsaPub == null ? new byte[0] : mlDsaPub;
					sig = signatures.signUserCheckApproval(signedInput,
							me.getPrivateKey(),
							identityManager.getLocalMlDsaSigPrivateKey());
				}
			} catch (GeneralSecurityException ex) {
				return;
			}
			byte[] reqBytes = pullCodec().encodeCheckApprovalRequest(
					channelId, ts, signerEd, signerMl, sig);
			byte[] respBytes;
			try {
				respBytes = transport.requestFromOnion(
						s.getCurrentOnion(), reqBytes);
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
						|| !java.util.Arrays.equals(
								current.ephemeralAgreementPub,
								my.ephemeralAgreementPub)) {
					return;
				}
				if ("APPROVED".equals(resp.status)
						&& resp.kemCt != null && resp.envelope != null) {
					applyApproval(channelId, current, resp.kemCt,
							resp.envelope);
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
				}
			} finally {
				lock.unlock();
			}
		} catch (IOException | DbException ignored) {
		}
	}

	private void applyApproval(byte[] channelId,
			ChannelMyApplicationsStore.MyApplication my, byte[] kemCt,
			byte[] envelope) throws DbException {
		byte[] ephPriv = my.ephemeralAgreementPriv;
		if (ephPriv != null) {
			org.zerionproject.core.api.crypto.KeyParser parser =
					crypto.getHybridAgreementKeyParser();
			org.zerionproject.core.api.crypto.PrivateKey privKey;
			org.zerionproject.core.api.crypto.PublicKey pubKey;
			try {
				privKey = parser.parsePrivateKey(ephPriv);
				pubKey = parser.parsePublicKey(my.ephemeralAgreementPub);
			} catch (GeneralSecurityException ex) {
				markApprovedStatusOnly(channelId, my);
				return;
			}
			org.zerionproject.core.api.crypto.KeyPair kp =
					new org.zerionproject.core.api.crypto.KeyPair(pubKey,
							privKey);
			byte[] sharedSecret;
			try {
				sharedSecret = crypto.hybridDecapsulate(kp, kemCt);
			} catch (GeneralSecurityException ex) {
				return;
			}
			byte[] capability;
			try {
				capability = unwrapApprovalCapability(channelId,
						sharedSecret, envelope);
			} catch (GeneralSecurityException ex) {
				java.util.Arrays.fill(sharedSecret, (byte) 0);
				return;
			}
			java.util.Arrays.fill(sharedSecret, (byte) 0);
			ChannelState s = store.getChannel(channelId);
			if (s == null) {
				java.util.Arrays.fill(capability, (byte) 0);
				return;
			}
			ChannelState updated = new ChannelState(s.getChannelId(),
					s.getSalt(), s.getPublisherEd25519PubKey(),
					s.getPublisherMlDsaPubKey(), s.getName(),
					s.getDescription(), s.getAvatarHash(),
					s.getCreatedAtHourMs(), s.isPublicChannel(),
					capability, s.getCurrentOnion(),
					s.getManifestSeq(), s.weArePublisher(),
					s.getHighestKnownPostSeq(),
					s.getContentKeyHash(), s.getContentKey(),
					s.getActiveDelegations(),
					s.getRevokedDelegationSeqs(),
					s.getNextDelegationSeq(),
					s.getOnionPrivateKey(),
					s.getPinnedPostSeq(),
					s.requiresApproval(),
				s.getRetiredDelegations());
			store.putChannel(updated);
			java.util.Arrays.fill(ephPriv, (byte) 0);
		}
		markApprovedStatusOnly(channelId, my);
	}

	private void markApprovedStatusOnly(byte[] channelId,
			ChannelMyApplicationsStore.MyApplication my)
			throws DbException {
		if (my.status == ApplicationStatus.APPROVED) {
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			return;
		}
		myApplicationsStore.put(channelId,
				new ChannelMyApplicationsStore.MyApplication(
						my.displayName, null,
						my.ephemeralAgreementPub,
						my.appliedAtHourMs,
						ApplicationStatus.APPROVED,
						my.signedWithMemberKey));
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
			sig = signatures.signUserAnnounce(signedInput,
					priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		ChannelSubscriber row = new ChannelSubscriber(trimmed, signerEd,
				signerMl, ts, false);
		if (s.weArePublisher()) {
			subscriberStore.putSubscriber(channelId, row);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			return;
		}
		try {
			byte[][] ch = buildChallenge(s, channelId);
			byte[] reqBytes = pullCodec().encodeAnnounceRequest(
					channelId, trimmed, ts, signerEd, signerMl, sig,
					ch == null ? null : ch[0], ch == null ? null : ch[1]);
			if (ch != null) reqBytes = proveV2(s, channelId, reqBytes, ch[0]);
			transport.requestFromOnion(s.getCurrentOnion(), reqBytes);
			subscriberStore.putSubscriber(channelId, row);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} catch (IOException ex) {
			throw new DbException(ex);
		}
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
			if (a.getStatus() == ChannelApplication.Status.APPROVED) {
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
		KeyPair keys = signingKeys(s);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) keys.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keys.getPrivate();
		byte[] signerEd = pub.getEd25519PublicKey();
		byte[] signerMl = pub.getMlDsaPublicKey();
		ChannelReaction mine = null;
		for (ChannelReaction r : reactionStore.getReactions(channelId)) {
			if (r.getPostSeqNum() == postSeqNum && java.util.Arrays.equals(
					r.getSignerEd25519PubKey(), signerEd)) {
				mine = r;
			}
		}
		long ts = ChannelReactionPolicy.nextTimestamp(mine,
				clock.currentTimeMillis());
		if (ts < 0L) {
			throw new org.zerionproject.app.api.channel
					.ChannelReactionTooSoonException();
		}
		byte[] signedInput = codec.reactionSignedInput(channelId,
				postSeqNum, emoji, ts);
		byte[] sig;
		try {
			sig = signatures.signUserReaction(signedInput,
					priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		boolean amPublisher = s.weArePublisher();
		if (amPublisher) {
			reactionStore.putReaction(channelId,
					new org.zerionproject.app.api.channel
							.ChannelReaction(postSeqNum, emoji,
									signerEd, signerMl, ts, sig));
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			return;
		}
		try {
			byte[][] ch = buildChallenge(s, channelId);
			byte[] reqBytes = pullCodec().encodeReactionRequest(
					channelId, postSeqNum, emoji, ts, signerEd,
					signerMl, sig,
					ch == null ? null : ch[0], ch == null ? null : ch[1]);
			if (ch != null) reqBytes = proveV2(s, channelId, reqBytes, ch[0]);
			byte[] ack = transport.requestFromOnion(
					s.getCurrentOnion(), reqBytes);
			if (!pullCodec().decodeReactionAck(ack)) {
				throw new DbException();
			}
			reactionStore.putReaction(channelId,
					new org.zerionproject.app.api.channel
							.ChannelReaction(postSeqNum, emoji,
									signerEd, signerMl, ts, sig));
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} catch (IOException ex) {
			throw new DbException(ex);
		}
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
			if (!java.util.Arrays.equals(req.channelId, channelId)) {
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
		}
		if (!expired.isEmpty()) {
			for (ChannelPost gone : store.posts().remove(channelId, expired,
					false)) {
				for (ChannelPost.ChannelAttachment a : gone.getAttachments()) {
					blobStore.removeBlob(channelId, a.getBlobHash());
				}
			}
			java.util.Set<Long> held = visiblePosts(channelId);
			reactionStore.retainPosts(channelId, held);
			commentStore.retainPosts(channelId, held);
			store.setUnread(channelId,
					store.posts().meta(channelId).unread());
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
		if (incoming.isWithheld()) {
			boolean chainOk = validator.validateChain(incoming, tip, true)
					== ChannelPostValidator.Result.OK;
			boolean signerState = vr == ChannelPostValidator.Result.OK
					|| vr == ChannelPostValidator.Result.DELEGATION_REVOKED
					|| vr == ChannelPostValidator.Result.DELEGATION_NOT_FOUND;
			if (!chainOk || !signerState || !incoming.signedByDelegate()) {
				throw new DbException();
			}
			keepWithinWindow(s, incoming, hash);
			store.putChannel(withSeq(s, incoming.getSeqNum()));
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.UNREAD_COUNT_CHANGED);
			return;
		}
		if (vr != ChannelPostValidator.Result.OK) {
			throw new DbException();
		}
		boolean kept = keepWithinWindow(s, incoming, hash);
		ChannelState updated = withSeq(s, incoming.getSeqNum());
		store.putChannel(updated);
		if (!kept) return;
		applyDeletionMark(updated, incoming);
		store.setUnread(channelId, store.posts().meta(channelId).unread());
		eventBus.broadcast(new ChannelPostReceivedEvent(channelId,
				incoming.getSeqNum(), false));
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.UNREAD_COUNT_CHANGED);
	}

	private boolean keepWithinWindow(ChannelState s, ChannelPost post,
			byte[] hash) throws DbException {
		byte[] channelId = s.getChannelId();
		long b = ChannelPostCeilings.storedBytes(post);
		if (b > ChannelConstants.MAX_SUBSCRIBER_BYTES_PER_POST) {
			store.posts().passOver(channelId, post.getSeqNum(), hash);
			return false;
		}
		ChannelPostStore.Meta m = store.posts().meta(channelId);
		long count = m.count;
		long bytes = m.bytes;
		List<Long> victims = new ArrayList<>();
		boolean fits = count + 1L
				<= ChannelConstants.MAX_SUBSCRIBER_POSTS_PER_CHANNEL
				&& bytes + b
				<= ChannelConstants.MAX_SUBSCRIBER_POST_BYTES_PER_CHANNEL;
		for (Long seq : fits ? Collections.<Long>emptyList()
				: m.heldSeqs()) {
			if (count + 1L <= ChannelConstants.MAX_SUBSCRIBER_POSTS_PER_CHANNEL
					&& bytes + b <= ChannelConstants
					.MAX_SUBSCRIBER_POST_BYTES_PER_CHANNEL) {
				break;
			}
			if (seq == s.getPinnedPostSeq()) continue;
			ChannelPost old = store.posts().getPost(channelId, seq);
			victims.add(seq);
			count--;
			if (old != null) bytes -= ChannelPostCeilings.storedBytes(old);
		}
		if (!victims.isEmpty()) {
			for (ChannelPost gone : store.posts().remove(channelId, victims,
					true)) {
				for (ChannelPost.ChannelAttachment a : gone.getAttachments()) {
					blobStore.removeBlob(channelId, a.getBlobHash());
				}
			}
		}
		store.posts().append(channelId, post, hash);
		return true;
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
