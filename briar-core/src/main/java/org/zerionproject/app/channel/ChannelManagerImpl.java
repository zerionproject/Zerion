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
import org.zerionproject.app.api.channel.ChannelPost;
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
	private static final int PULL_NONCE_MAX_PER_CHANNEL = 4096;
	private final java.util.Map<String,
			java.util.LinkedHashMap<String, Long>> seenPullNonces =
					new java.util.concurrent.ConcurrentHashMap<>();
	private static final long PULL_MIN_INTERVAL_MS = 5_000L;
	private static final long PULL_MAX_INTERVAL_MS = 60_000L;
	private static final long PULL_ACTIVE_WINDOW_MS = 120_000L;
	private static final long PULL_BACKOFF_STEP_MS = 5_000L;
	private volatile long lastChannelActivityMs = 0L;
	private volatile long currentPullIntervalMs = PULL_MIN_INTERVAL_MS;

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

	private void refreshAllSubscriptionsSafely() {
		Collection<ChannelState> all;
		try {
			all = store.listChannels();
		} catch (DbException ignored) {
			return;
		}
		for (ChannelState s : all) {
			if (s.weArePublisher()) continue;
			if (s.getCurrentOnion() == null
					|| s.getCurrentOnion().isEmpty()) continue;
			byte[] channelId = s.getChannelId();
			String pullKey = ChannelStore.hex(channelId);
			if (!inFlightPulls.add(pullKey)) continue;
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
	}

	private void scheduleNextRefresh(long delayMs) {
		try {
			taskScheduler.schedule(this::refreshAndReschedule, ioExecutor,
					delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
		}
	}

	private void refreshAndReschedule() {
		try {
			refreshAllSubscriptionsSafely();
		} finally {
			long idle = clock.currentTimeMillis() - lastChannelActivityMs;
			if (idle < PULL_ACTIVE_WINDOW_MS) {
				currentPullIntervalMs = PULL_MIN_INTERVAL_MS;
			} else {
				currentPullIntervalMs = Math.min(PULL_MAX_INTERVAL_MS,
						currentPullIntervalMs + PULL_BACKOFF_STEP_MS);
			}
			scheduleNextRefresh(currentPullIntervalMs);
		}
	}

	private void rebindOwnedChannelsOnStartup() {
		try {
			for (ChannelState s : store.listChannels()) {
				if (!s.weArePublisher()) continue;
				bindPublisherServer(s.getChannelId());
			}
		} catch (Throwable ignored) {
		}
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
				s.requiresApproval());
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
		String boundOnion = bindPublisherServer(channelId);
		if (boundOnion != null && !boundOnion.isEmpty()) {
			ChannelState withOnion = withOnion(state, boundOnion);
			store.putChannel(withOnion);
			state = withOnion;
		}
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.CREATED);
		clearReturned(manifestSig);
		return state;
	}

	@Nullable
	private String bindPublisherServer(byte[] channelId) {
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
				s.requiresApproval());
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
		java.util.concurrent.locks.ReentrantLock pullLock =
				lockFor(channelId);
		pullLock.lock();
		try {
			ChannelPullCodec.PullRequest req = pullCodec()
					.decodePullRequest(requestBytes);
			ChannelState s = store.getChannel(channelId);
			if (s == null) return new byte[0];
			boolean challengePresent = req.hmacResponse != null
					&& req.nonce != null;
			boolean challengeOk = false;
			if (challengePresent && s.getJoinCapability() != null) {
				if (!recordFreshNonce(channelId, req.nonce)) {
					return new byte[0];
				}
				challengeOk = verifyChallenge(s.getJoinCapability(),
						req.nonce, channelId, req.hmacResponse);
				if (!challengeOk) return new byte[0];
			}
			if (!s.isPublicChannel() && s.getJoinCapability() != null
					&& !challengeOk) {
				return new byte[0];
			}
			java.util.List<ChannelPost> all =
					store.getPosts(channelId);
			java.util.List<ChannelPost> toSend =
					new java.util.ArrayList<>();
			for (ChannelPost p : all) {
				if (p.getSeqNum() > req.sinceSeqNum) toSend.add(p);
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
			java.util.List<ChannelPost> wirePosts =
					convertToWirePosts(s, toSend);
			boolean discussions = discussionStore.isEnabled(channelId);
			byte[] manifestSig = signLatestManifest(s, discussions);
			java.util.List<org.zerionproject.app.api.channel
					.ChannelReaction> reactions =
					reactionStore.getReactions(channelId);
			java.util.List<org.zerionproject.app.api.channel
					.ChannelComment> comments =
					commentStore.getComments(channelId);
			return pullProtocol.buildResponseAsPublisher(s,
					s.getPublisherEd25519PubKey(),
					s.getPublisherMlDsaPubKey(), manifestSig,
					discussions, wirePosts, envelope,
					java.util.Collections.<String>emptyList(),
					reactions, comments);
		} catch (IOException | DbException e) {
			return new byte[0];
		} finally {
			pullLock.unlock();
		}
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
			if (!java.util.Arrays.equals(req.channelId, channelId)) {
				return new byte[0];
			}
			byte[] blob = blobStore.get(channelId, req.blobHash);
			byte[] payload = blob == null ? new byte[0] : blob;
			return pullCodec().encodeAttachmentResponse(req.blobHash,
					payload);
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

	/**
	 * Requests that write to or read from a private channel must prove they
	 * hold the current join capability. Rotating the capability then actually
	 * revokes access: an evicted holder can no longer comment, react, announce
	 * or fetch attachments. Public channels and the join/approval handshake
	 * itself are exempt, since a joiner has no capability yet.
	 */
	/**
	 * Builds a capability proof for an outgoing request, or null when this
	 * channel has no join capability (public, or not yet joined).
	 * Element 0 is the nonce, element 1 the response.
	 */
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
			if (c == null) return false;
			if (!recordFreshNonce(channelId, c.nonce)) return false;
			return verifyChallenge(capability, c.nonce, channelId, c.hmac);
		} catch (Exception e) {
			return false;
		}
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

	private void pullAndApply(byte[] channelId, boolean isBootstrap)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (s.weArePublisher()) return;
		pollApprovalStatusIfPending(channelId);
		s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		byte[] requestBytes;
		try {
			if (isBootstrap || s.getJoinCapability() == null) {
				requestBytes = pullProtocol.buildBootstrapRequest(
						channelId);
			} else {
				byte[] nonce = hmacChallenge().freshNonce();
				requestBytes = pullProtocol.buildAuthenticatedRequest(
						channelId, s.getHighestKnownPostSeq(),
						s.getJoinCapability(), nonce);
			}
		} catch (IOException e) {
			throw new DbException(e);
		}
		byte[] responseBytes;
		try {
			responseBytes = transport.requestFromOnion(
					s.getCurrentOnion(), requestBytes);
		} catch (IOException e) {
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
				return;
			}
			java.util.List<ChannelPost> existing =
					store.getPosts(channelId);
			ChannelPullProtocol.ProcessResult r =
					pullProtocol.processSubscriberResponse(responseBytes,
							cur, existing, cur.getJoinCapability());
			if (!r.ok || r.mergedState == null) {
				throw new DbException();
			}
			store.putChannel(r.mergedState);
			for (ChannelPost p : r.acceptedPosts) {
				acceptIncomingPost(channelId, p);
			}
			applyIncomingReactions(channelId, r.reactions);
			applyIncomingComments(channelId, r.comments);
			if (ChannelConstants.DISCUSSIONS_IN_MANIFEST) {
				discussionStore.setEnabled(channelId,
						r.discussionsEnabled);
			}
			if (!r.acceptedPosts.isEmpty() || !r.reactions.isEmpty()
					|| !r.comments.isEmpty()) {
				lastChannelActivityMs = clock.currentTimeMillis();
			}
		} finally {
			lock.unlock();
		}
	}

	private void applyIncomingComments(byte[] channelId,
			java.util.List<org.zerionproject.app.api.channel
					.ChannelComment> incoming) throws DbException {
		if (incoming.isEmpty()) return;
		boolean accepted = false;
		for (org.zerionproject.app.api.channel.ChannelComment c
				: incoming) {
			byte[] sig = c.getSignature();
			if (sig == null || sig.length == 0) continue;
			byte[] signedInput = codec.commentSignedInput(channelId,
					c.getParentPostSeqNum(), c.getCommentId(),
					c.getBody(), c.getAuthorDisplayName(),
					c.getTimestampHourMs());
			org.zerionproject.core.api.crypto.PublicKey edPub;
			try {
				edPub = crypto.getSignatureKeyParser()
						.parsePublicKey(c.getAuthorEd25519PubKey());
			} catch (GeneralSecurityException ex) {
				continue;
			}
			if (!signatures.verifyUserComment(sig, signedInput, edPub,
					c.getAuthorMlDsaPubKey())) {
				continue;
			}
			if (subscriberStore.isBanned(channelId,
					c.getAuthorEd25519PubKey())) {
				continue;
			}
			if (!commentStore.putComment(channelId, c)) {
				continue;
			}
			eventBus.broadcast(new ChannelCommentReceivedEvent(channelId,
					c.getParentPostSeqNum()));
			accepted = true;
		}
		if (accepted) {
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		}
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

	private void applyIncomingReactions(byte[] channelId,
			java.util.List<org.zerionproject.app.api.channel
					.ChannelReaction> incoming) throws DbException {
		if (incoming.isEmpty()) return;
		boolean accepted = false;
		for (org.zerionproject.app.api.channel.ChannelReaction r
				: incoming) {
			byte[] sig = r.getSignature();
			if (sig == null || sig.length == 0) continue;
			byte[] signedInput = codec.reactionSignedInput(channelId,
					r.getPostSeqNum(), r.getEmoji(),
					r.getTimestampHourMs());
			org.zerionproject.core.api.crypto.PublicKey edPub;
			try {
				edPub = crypto.getSignatureKeyParser()
						.parsePublicKey(r.getSignerEd25519PubKey());
			} catch (GeneralSecurityException ex) {
				continue;
			}
			if (!signatures.verifyUserReaction(sig, signedInput, edPub,
					r.getSignerMlDsaPubKey())) {
				continue;
			}
			if (subscriberStore.isBanned(channelId,
					r.getSignerEd25519PubKey())) {
				continue;
			}
			if (reactionStore.putReaction(channelId, r)) {
				accepted = true;
			}
		}
		if (accepted) {
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		}
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

	private ChannelState withOnion(ChannelState s, String onion) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), onion, s.getManifestSeq(),
				s.weArePublisher(), s.getHighestKnownPostSeq(),
				s.getContentKeyHash(), s.getContentKey(),
				s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(),
				s.getOnionPrivateKey(),
				s.getPinnedPostSeq(),
				s.requiresApproval());
	}

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
			if (s != null && s.weArePublisher()) {
				publishTombstone(s);
			}
			removeChannelLocally(channelId);
		} finally {
			lock.unlock();
		}
	}

	private void removeChannelLocally(byte[] channelId) throws DbException {
		String key = ChannelStore.hex(channelId);
		seenPullNonces.remove(key);
		lastApprovalPollMs.remove(key);
		inFlightPulls.remove(key);
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
		fireEvent(channelId, ChannelStateChangedEvent.Kind.LEFT);
	}

	private void publishTombstone(ChannelState s) throws DbException {
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		byte[] signedInput = codec.tombstoneSignedInput(
				s.getChannelId(), ts);
		byte[] privEncoded = store.getPublisherPrivKey(s.getChannelId());
		if (privEncoded == null) return;
		byte[] hybridSig;
		try {
			HybridSignaturePrivateKey priv =
					new HybridSignaturePrivateKey(privEncoded);
			hybridSig = signatures.signTombstone(signedInput, priv);
		} catch (GeneralSecurityException e) {
			return;
		}
		byte[] tombstoneBytes;
		try {
			tombstoneBytes = pullCodec().encodeTombstone(
					s.getChannelId(), ts, hybridSig);
		} catch (IOException e) {
			return;
		}
		tombstoneStore.put(s.getChannelId(), tombstoneBytes);
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
		java.util.concurrent.locks.ReentrantLock lock = lockFor(channelId);
		lock.lock();
		try {
			publishPostLocked(channelId, body, ttlSeconds);
		} finally {
			lock.unlock();
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
		List<ChannelPost> existing = store.getPosts(channelId);
		long nextSeq = existing.isEmpty() ? 0L
				: existing.get(existing.size() - 1).getSeqNum() + 1L;
		byte[] prevHash = existing.isEmpty()
				? new byte[ChannelConstants.PREV_HASH_BYTES]
				: chainVerifier.hashOf(existing.get(existing.size() - 1));
		long nowHourMs =
				clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		long ttlMs = Math.max(0L, ttlSeconds) * 1000L;
		byte[] attHash = codec.attachmentsHash(attachments);

		String wireBody = body;
		if (!s.isPublicChannel()) {
			byte[] kContent = s.getContentKey();
			if (kContent == null) throw new DbException();
			try {
				byte[] ct = contentKey.encryptBody(kContent, channelId,
						nextSeq, body);
				wireBody = java.util.Base64.getEncoder()
						.withoutPadding().encodeToString(ct);
			} catch (GeneralSecurityException ex) {
				throw new DbException(ex);
			}
		}

		byte[] signedInput = codec.postSignedInput(channelId, nextSeq,
				prevHash, nowHourMs, wireBody, attHash, ttlMs);
		byte[] sig;
		try {
			sig = signatures.signPost(signedInput, hybridPriv);
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		ChannelPost post = new ChannelPost(channelId, nextSeq, prevHash,
				nowHourMs, wireBody, attachments, ttlMs, sig, true);
		store.appendPost(channelId, post);
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

	@Override
	public List<ChannelPost> getRecentPosts(byte[] channelId, long limit)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		List<ChannelPost> all = store.getPosts(channelId);
		byte[] kContent = s == null ? null : s.getContentKey();
		boolean encrypted = s != null && !s.isPublicChannel()
				&& kContent != null;

		String channelIdHex = ChannelStore.hex(channelId);
		java.util.Set<Long> deletedSeqs = new java.util.HashSet<>(
				postTombstoneStore.get(channelId));
		List<ChannelPost> decoded = new ArrayList<>(all.size());
		for (ChannelPost p : all) {
			ChannelPost view = encrypted ? decryptForDisplay(p, kContent)
					: p;
			decoded.add(view);
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
			if (parseTombstoneTarget(p.getBody(), channelIdHex) != null) {
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
		return new ChannelPost(p.getChannelId(), p.getSeqNum(),
				p.getPrevHash(), p.getTimestampHourMs(),
				"—deleted—",
				Collections.<ChannelPost.ChannelAttachment>emptyList(),
				p.getTtlMs(), p.getSignature(), p.isRead(),
				p.getDelegateSignerEd25519PubKey(),
				p.getDelegateSignerMlDsaPubKey());
	}

	private ChannelPost decryptForDisplay(ChannelPost p,
			byte[] kContent) {
		try {
			byte[] ct = java.util.Base64.getDecoder().decode(p.getBody());
			String plain = contentKey.decryptBody(kContent,
					p.getChannelId(), p.getSeqNum(), ct);
			return new ChannelPost(p.getChannelId(), p.getSeqNum(),
					p.getPrevHash(), p.getTimestampHourMs(), plain,
					p.getAttachments(), p.getTtlMs(), p.getSignature(),
					p.isRead(), p.getDelegateSignerEd25519PubKey(),
					p.getDelegateSignerMlDsaPubKey());
		} catch (GeneralSecurityException | IllegalArgumentException ex) {
			return p;
		}
	}

	@Override
	public int getUnreadCount(byte[] channelId) throws DbException {
		return store.getUnread(channelId);
	}

	@Override
	public void markChannelRead(byte[] channelId) throws DbException {
		if (store.getUnread(channelId) == 0) return;
		store.setUnread(channelId, 0);
		List<ChannelPost> posts = store.getPosts(channelId);
		boolean changed = false;
		for (int i = 0; i < posts.size(); i++) {
			ChannelPost p = posts.get(i);
			if (!p.isRead()) {
				posts.set(i, new ChannelPost(p.getChannelId(),
						p.getSeqNum(), p.getPrevHash(),
						p.getTimestampHourMs(), p.getBody(),
						p.getAttachments(), p.getTtlMs(),
						p.getSignature(), true,
						p.getDelegateSignerEd25519PubKey(),
						p.getDelegateSignerMlDsaPubKey()));
				changed = true;
			}
		}
		if (changed) store.writePosts(channelId, posts);
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
				s.requiresApproval());
		store.putChannel(updated);
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
			if (autoUnpin) {
				setPinnedPostSeqLocked(channelId,
						ChannelState.NO_PINNED_POST);
			}
		} finally {
			lock.unlock();
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
			byte[] thumbWire = null;
			byte[] thumbPlain = spec.getPlaintextThumbnail();
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
		ChannelPost.ChannelAttachment target = null;
		for (ChannelPost p : store.getPosts(channelId)) {
			if (p.getSeqNum() != postSeqNum) continue;
			for (ChannelPost.ChannelAttachment a : p.getAttachments()) {
				if (java.util.Arrays.equals(a.getBlobHash(), blobHash)) {
					target = a;
					break;
				}
			}
			break;
		}
		if (target == null) return null;
		byte[] cachedBlob = blobStore.get(channelId, blobHash);
		boolean closed = !s.isPublicChannel();
		byte[] perAttKey;
		if (closed) {
			byte[] kContent = s.getContentKey();
			if (kContent == null) return null;
			try {
				perAttKey = contentKey.unwrapContentKey(kContent,
						channelId, target.getPerAttachmentKey());
			} catch (GeneralSecurityException ex) {
				return null;
			}
		} else {
			perAttKey = target.getPerAttachmentKey();
		}
		byte[] blob = cachedBlob;
		if (blob == null) {
			byte[][] ch = buildChallenge(s, channelId);
			byte[] reqBytes = pullCodec().encodeAttachmentRequest(
					channelId, blobHash,
					ch == null ? null : ch[0], ch == null ? null : ch[1]);
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
			blobStore.put(channelId, blobHash, blob);
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
		LocalAuthor me = identityManager.getLocalAuthor();
		byte[] signerEd = me.getPublicKey().getEncoded();
		byte[] mlDsaPub = identityManager.getLocalMlDsaSigPublicKey();
		byte[] signerMl = mlDsaPub == null ? new byte[0] : mlDsaPub;
		byte[] mlDsaPriv = identityManager.getLocalMlDsaSigPrivateKey();
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		String authorName = pickAuthorName(channelId, signerEd, me);
		long commentId = random.nextLong();
		byte[] signedInput = codec.commentSignedInput(channelId,
				parentPostSeqNum, commentId, trimmed, authorName, ts);
		byte[] sig;
		try {
			sig = signatures.signUserComment(signedInput,
					me.getPrivateKey(), mlDsaPriv);
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		org.zerionproject.app.api.channel.ChannelComment row =
				new org.zerionproject.app.api.channel.ChannelComment(
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

	private String pickAuthorName(byte[] channelId, byte[] signerEd,
			LocalAuthor me) throws DbException {
		for (ChannelSubscriber sub
				: subscriberStore.getSubscribers(channelId)) {
			if (java.util.Arrays.equals(sub.getEd25519PubKey(), signerEd)) {
				return sub.getDisplayName();
			}
		}
		return me.getName();
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
		for (org.zerionproject.app.api.channel.ChannelComment c : all) {
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
			if (req.body.isEmpty()
					|| req.body.length()
							> ChannelConstants.MAX_COMMENT_BODY_CHARS) {
				return safeCommentAck(false);
			}
			byte[] signedInput = codec.commentSignedInput(channelId,
					req.parentPostSeqNum, req.commentId, req.body,
					req.authorName, req.timestampHourMs);
			org.zerionproject.core.api.crypto.PublicKey edPub;
			try {
				edPub = crypto.getSignatureKeyParser()
						.parsePublicKey(req.signerEd25519);
			} catch (GeneralSecurityException ex) {
				return safeCommentAck(false);
			}
			if (!signatures.verifyUserComment(req.signature, signedInput,
					edPub, req.signerMlDsa)) {
				return safeCommentAck(false);
			}
			if (subscriberStore.isBanned(channelId, req.signerEd25519)) {
				return safeCommentAck(false);
			}
			java.util.List<org.zerionproject.app.api.channel
					.ChannelComment> existing =
					commentStore.getComments(channelId);
			if (existing.size()
					>= ChannelConstants.MAX_COMMENTS_PER_CHANNEL) {
				return safeCommentAck(false);
			}
			int byAuthor = 0;
			for (org.zerionproject.app.api.channel.ChannelComment c
					: existing) {
				if (java.util.Arrays.equals(c.getAuthorEd25519PubKey(),
						req.signerEd25519)) {
					byAuthor++;
				}
			}
			if (byAuthor >= ChannelConstants.MAX_COMMENTS_PER_AUTHOR) {
				return safeCommentAck(false);
			}
			commentStore.putComment(channelId,
					new org.zerionproject.app.api.channel.ChannelComment(
							req.parentPostSeqNum, req.commentId,
							req.body, req.authorName,
							req.signerEd25519, req.signerMlDsa,
							req.timestampHourMs, req.signature));
			eventBus.broadcast(new ChannelCommentReceivedEvent(channelId,
					req.parentPostSeqNum));
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
					required);
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
			LocalAuthor me = identityManager.getLocalAuthor();
			byte[] signerEd = me.getPublicKey().getEncoded();
			byte[] mlDsaPub = identityManager.getLocalMlDsaSigPublicKey();
			byte[] signerMl = mlDsaPub == null ? new byte[0] : mlDsaPub;
			byte[] mlDsaPriv =
					identityManager.getLocalMlDsaSigPrivateKey();
			long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
			byte[] signedInput = codec.applicationSignedInput(channelId,
					trimmed, ts, ephPub);
			byte[] sig;
			try {
				sig = signatures.signUserApplication(signedInput,
						me.getPrivateKey(), mlDsaPriv);
			} catch (GeneralSecurityException ex) {
				throw new DbException(ex);
			}
			myApplicationsStore.put(channelId,
					new ChannelMyApplicationsStore.MyApplication(
							trimmed, ephPriv, ephPub, ts,
							ApplicationStatus.PENDING));
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

	private byte[] handleApplyRequest(byte[] channelId,
			byte[] requestBytes) {
		try {
			ChannelPullCodec.ApplyRequest req = pullCodec()
					.decodeApplyRequest(requestBytes);
			if (!java.util.Arrays.equals(req.channelId, channelId)) {
				return safeApplyAck(false);
			}
			ChannelState s = store.getChannel(channelId);
			if (s == null) return safeApplyAck(false);
			if (!s.requiresApproval()) return safeApplyAck(false);
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
			boolean replacingDenied = false;
			for (ChannelApplication a : existing) {
				if (a.getStatus() == ChannelApplication.Status.PENDING) {
					pendingCount++;
				}
				if (java.util.Arrays.equals(a.getApplicantEd25519(),
						req.signerEd25519)) {
					if (a.getStatus()
							== ChannelApplication.Status.DENIED) {
						replacingDenied = true;
						break;
					}
					return safeApplyAck(true);
				}
			}
			if (!replacingDenied && pendingCount
					>= ChannelConstants.MAX_PENDING_APPLICATIONS) {
				return safeApplyAck(false);
			}
			applicationStore.putApplication(channelId,
					new ChannelApplication(req.displayName,
							req.signerEd25519, req.signerMlDsa,
							req.ephemeralAgreementPub,
							req.timestampHourMs,
							ChannelApplication.Status.PENDING,
							null, null));
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
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
			LocalAuthor me = identityManager.getLocalAuthor();
			byte[] signerEd = me.getPublicKey().getEncoded();
			byte[] mlDsaPub = identityManager.getLocalMlDsaSigPublicKey();
			byte[] signerMl = mlDsaPub == null ? new byte[0] : mlDsaPub;
			byte[] mlDsaPriv =
					identityManager.getLocalMlDsaSigPrivateKey();
			long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
			byte[] signedInput =
					codec.checkApprovalSignedInput(channelId, ts);
			byte[] sig;
			try {
				sig = signatures.signUserCheckApproval(signedInput,
						me.getPrivateKey(), mlDsaPriv);
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
			if ("APPROVED".equals(resp.status)
					&& resp.kemCt != null && resp.envelope != null) {
				applyApproval(channelId, my, resp.kemCt, resp.envelope);
			} else if ("DENIED".equals(resp.status)) {
				myApplicationsStore.put(channelId,
						new ChannelMyApplicationsStore.MyApplication(
								my.displayName, null,
								my.ephemeralAgreementPub,
								my.appliedAtHourMs,
								ApplicationStatus.DENIED));
				fireEvent(channelId,
						ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
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
				org.zerionproject.core.api.crypto.SecretKey ss =
						crypto.deriveHybridSharedSecretAsResponder(
								ChannelConstants.APPROVAL_WRAP_LABEL,
								pubKey, kp, kemCt);
				sharedSecret = ss.getBytes();
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
					s.requiresApproval());
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
						ApplicationStatus.APPROVED));
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
		LocalAuthor me = identityManager.getLocalAuthor();
		byte[] signerEd = me.getPublicKey().getEncoded();
		byte[] mlDsaPub = identityManager.getLocalMlDsaSigPublicKey();
		byte[] signerMl = mlDsaPub == null ? new byte[0] : mlDsaPub;
		byte[] mlDsaPriv = identityManager.getLocalMlDsaSigPrivateKey();
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		byte[] signedInput = codec.announceSignedInput(channelId,
				trimmed, ts);
		byte[] sig;
		try {
			sig = signatures.signUserAnnounce(signedInput,
					me.getPrivateKey(), mlDsaPriv);
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
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} finally {
			lock.unlock();
		}
	}

	private byte[] handleAnnounceRequest(byte[] channelId,
			byte[] requestBytes) {
		try {
			ChannelPullCodec.AnnounceRequest req = pullCodec()
					.decodeAnnounceRequest(requestBytes);
			if (!java.util.Arrays.equals(req.channelId, channelId)) {
				return safeAnnounceAck(false);
			}
			if (req.displayName.isEmpty()
					|| req.displayName.getBytes(
							java.nio.charset.StandardCharsets.UTF_8).length
					> ChannelConstants.MAX_DISPLAY_NAME_BYTES) {
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
			if (existing.size()
					>= ChannelConstants.MAX_ANNOUNCED_SUBSCRIBERS) {
				return safeAnnounceAck(false);
			}
			subscriberStore.putSubscriber(channelId,
					new ChannelSubscriber(req.displayName,
							req.signerEd25519, req.signerMlDsa,
							req.timestampHourMs, false));
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
		LocalAuthor me = identityManager.getLocalAuthor();
		byte[] signerEd = me.getPublicKey().getEncoded();
		byte[] mlDsaPub = identityManager.getLocalMlDsaSigPublicKey();
		byte[] signerMl = mlDsaPub == null ? new byte[0] : mlDsaPub;
		byte[] mlDsaPriv = identityManager.getLocalMlDsaSigPrivateKey();
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		byte[] signedInput = codec.reactionSignedInput(channelId,
				postSeqNum, emoji, ts);
		byte[] sig;
		try {
			sig = signatures.signUserReaction(signedInput,
					me.getPrivateKey(), mlDsaPriv);
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
		for (org.zerionproject.app.api.channel.ChannelReaction r : all) {
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
			byte[] signedInput = codec.reactionSignedInput(channelId,
					req.postSeqNum, req.emoji, req.timestampHourMs);
			org.zerionproject.core.api.crypto.PublicKey edPub;
			try {
				edPub = crypto.getSignatureKeyParser()
						.parsePublicKey(req.signerEd25519);
			} catch (GeneralSecurityException ex) {
				return safeAck(false);
			}
			if (!signatures.verifyUserReaction(req.signature, signedInput,
					edPub, req.signerMlDsa)) {
				return safeAck(false);
			}
			if (subscriberStore.isBanned(channelId, req.signerEd25519)) {
				return safeAck(false);
			}
			java.util.List<org.zerionproject.app.api.channel
					.ChannelReaction> existing =
					reactionStore.getReactions(channelId);
			int perPost = 0;
			for (org.zerionproject.app.api.channel.ChannelReaction r
					: existing) {
				if (r.getPostSeqNum() == req.postSeqNum) perPost++;
			}
			if (perPost >= ChannelConstants.MAX_REACTIONS_PER_POST) {
				return safeAck(false);
			}
			reactionStore.putReaction(channelId,
					new org.zerionproject.app.api.channel
							.ChannelReaction(req.postSeqNum, req.emoji,
									req.signerEd25519, req.signerMlDsa,
									req.timestampHourMs,
									req.signature));
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
		ChannelPost.ChannelAttachment target = null;
		for (ChannelPost p : store.getPosts(channelId)) {
			if (p.getSeqNum() != postSeqNum) continue;
			for (ChannelPost.ChannelAttachment a : p.getAttachments()) {
				if (java.util.Arrays.equals(a.getBlobHash(), blobHash)) {
					target = a;
					break;
				}
			}
			break;
		}
		if (target == null) return null;
		byte[] thumbCt = target.getThumbnail();
		if (thumbCt == null) return null;
		boolean closed = !s.isPublicChannel();
		byte[] perAttKey;
		if (closed) {
			byte[] kContent = s.getContentKey();
			if (kContent == null) return null;
			try {
				perAttKey = contentKey.unwrapContentKey(kContent,
						channelId, target.getPerAttachmentKey());
			} catch (GeneralSecurityException ex) {
				return null;
			}
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
				s.requiresApproval());
		store.putChannel(updated);
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
	}

	@Override
	public void purgeExpiredPosts() throws DbException {
		long now = clock.currentTimeMillis();
		for (ChannelState s : store.listChannels()) {
			byte[] channelId = s.getChannelId();
			java.util.concurrent.locks.ReentrantLock lock =
					lockFor(channelId);
			lock.lock();
			try {
				java.util.List<ChannelPost> kept =
						new java.util.ArrayList<>();
				java.util.List<byte[]> expiredBlobHashes =
						new java.util.ArrayList<>();
				java.util.List<Long> expiredSeqs =
						new java.util.ArrayList<>();
				boolean changed = false;
				for (ChannelPost p : store.getPosts(channelId)) {
					if (p.getTtlMs() > 0
							&& now > p.getTimestampHourMs()
							+ p.getTtlMs()) {
						changed = true;
						expiredSeqs.add(p.getSeqNum());
						for (ChannelPost.ChannelAttachment a
								: p.getAttachments()) {
							expiredBlobHashes.add(a.getBlobHash());
						}
						continue;
					}
					kept.add(p);
				}
				if (changed) store.writePosts(channelId, kept);
				for (byte[] h : expiredBlobHashes) {
					blobStore.removeBlob(channelId, h);
				}
				for (Long seq : expiredSeqs) {
					reactionStore.removeForPost(channelId, seq);
					commentStore.removeForParent(channelId, seq);
				}
			} finally {
				lock.unlock();
			}
		}
	}

	private ChannelState withDelegations(ChannelState s,
			java.util.List<ChannelDelegationCert> active,
			java.util.List<Long> revoked, long nextSeq) {
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
				s.requiresApproval());
	}

	@Override
	public void onOnionRotated(String newOnionAddress) throws DbException {
		Collection<ChannelState> mine = store.listChannels();
		for (ChannelState s : mine) {
			if (!s.weArePublisher()) continue;
			ChannelState updated = new ChannelState(s.getChannelId(),
					s.getSalt(), s.getPublisherEd25519PubKey(),
					s.getPublisherMlDsaPubKey(), s.getName(),
					s.getDescription(), s.getAvatarHash(),
					s.getCreatedAtHourMs(), s.isPublicChannel(),
					s.getJoinCapability(), newOnionAddress,
					s.getManifestSeq() + 1L, true,
					s.getHighestKnownPostSeq(),
					s.getContentKeyHash(), s.getContentKey(),
					s.getActiveDelegations(),
					s.getRevokedDelegationSeqs(),
					s.getNextDelegationSeq(),
					s.getOnionPrivateKey(),
					s.getPinnedPostSeq(),
				s.requiresApproval());
			store.putChannel(updated);
			fireEvent(s.getChannelId(),
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		}
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
		List<ChannelPost> existing = store.getPosts(channelId);
		ChannelPost previous = existing.isEmpty() ? null
				: existing.get(existing.size() - 1);
		ChannelPostValidator.Result vr =
				validator.validate(s, incoming, previous);
		if (vr != ChannelPostValidator.Result.OK) {
			throw new DbException();
		}
		store.appendPost(channelId, incoming);
		ChannelState updated = withSeq(s, incoming.getSeqNum());
		store.putChannel(updated);
		store.setUnread(channelId, store.getUnread(channelId) + 1);
		eventBus.broadcast(new ChannelPostReceivedEvent(channelId,
				incoming.getSeqNum(), false));
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
				s.requiresApproval());
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
				s.requiresApproval());
	}

	private void clearReturned(byte[] b) {
		java.util.Arrays.fill(b, (byte) 0);
	}
}
