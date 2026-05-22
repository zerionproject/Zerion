package org.briarproject.briar.channel;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey;
import org.briarproject.bramble.api.crypto.HybridSignaturePublicKey;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.plugin.event.B4OwnRotationCompletedEvent;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.system.TaskScheduler;
import org.briarproject.briar.api.channel.ChannelConstants;
import org.briarproject.briar.api.channel.ChannelDelegationCert;
import org.briarproject.briar.api.channel.ChannelInviteLink;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.ChannelPost;
import org.briarproject.briar.api.channel.ChannelState;
import org.briarproject.briar.api.channel.ChannelSubscriber;
import org.briarproject.briar.api.channel.ChannelTransport;
import org.briarproject.briar.api.channel.event.ChannelPostReceivedEvent;
import org.briarproject.briar.api.channel.event.ChannelStateChangedEvent;
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
	private final IdentityManager identityManager;
	private final TaskScheduler taskScheduler;
	private final java.util.concurrent.Executor ioExecutor;
	private final SecureRandom random;
	private final java.util.Map<String, ChannelTransport.ChannelServer>
			boundServers =
					new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String,
			java.util.concurrent.locks.ReentrantLock> channelLocks =
					new java.util.concurrent.ConcurrentHashMap<>();

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
		taskScheduler.scheduleWithFixedDelay(
				this::refreshAllSubscriptionsSafely,
				ioExecutor, 15L, 30L,
				java.util.concurrent.TimeUnit.SECONDS);
		ioExecutor.execute(this::rebindOwnedChannelsOnStartup);
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
			ioExecutor.execute(() -> {
				try {
					pullAndApply(channelId, false);
				} catch (DbException ignored) {
				}
			});
		}
	}

	private void rebindOwnedChannelsOnStartup() {
		try {
			for (ChannelState s : store.listChannels()) {
				if (!s.weArePublisher()) continue;
				if (boundServers.containsKey(
						ChannelStore.hex(s.getChannelId()))) continue;
				String onion = bindPublisherServer(s.getChannelId());
				if (onion == null || onion.isEmpty()) continue;
				if (onion.equals(s.getCurrentOnion())) continue;
				ChannelState updated = withRotatedOnion(s, onion);
				store.putChannel(updated);
				fireEvent(s.getChannelId(),
						ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			}
		} catch (DbException ignored) {
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
		if (e instanceof B4OwnRotationCompletedEvent) {
			ioExecutor.execute(this::rebindAllPublisherServers);
		}
	}

	private void rebindAllPublisherServers() {
		try {
			for (ChannelState s : store.listChannels()) {
				if (!s.weArePublisher()) continue;
				ChannelTransport.ChannelServer previous =
						boundServers.remove(ChannelStore.hex(
								s.getChannelId()));
				if (previous != null) previous.close();
				String newOnion = bindPublisherServer(s.getChannelId());
				if (newOnion == null || newOnion.isEmpty()) continue;
				ChannelState rotated = withRotatedOnion(s, newOnion);
				store.putChannel(rotated);
				fireEvent(s.getChannelId(),
						ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			}
		} catch (DbException ignored) {
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
				s.getPinnedPostSeq());
	}

	@Override
	public ChannelState createChannel(String name, String description,
			boolean publicChannel) throws DbException {
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
				crypto.hash("org.briarproject.zerion/CHANNEL_ID",
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
		byte[] signedInput = codec.manifestSignedInput(channelId, salt,
				ed25519Pub, mlDsaPub, name, description, null,
				nowHourMs, publicChannel, capability, onion, manifestSeq,
				kContentHash,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(),
				ChannelState.NO_PINNED_POST);
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
				java.util.Collections.<org.briarproject.briar.api.channel
						.ChannelDelegationCert>emptyList(),
				java.util.Collections.<Long>emptyList(), 0L);
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
		try {
			ChannelState existing = store.getChannel(channelId);
			String existingPriv = existing == null ? null
					: existing.getOnionPrivateKey();
			ChannelTransport.ChannelServer server =
					transport.bindServer(channelId, existingPriv,
							requestBytes -> handlePublisherRequest(
									channelId, requestBytes));
			boundServers.put(ChannelStore.hex(channelId), server);
			String returnedPriv = server.getOnionPrivateKey();
			if (returnedPriv != null && (existingPriv == null
					|| !returnedPriv.equals(existingPriv))) {
				if (existing != null) {
					ChannelState withPriv = withOnionPrivateKey(
							existing, returnedPriv);
					store.putChannel(withPriv);
				}
			}
			return server.getOnionAddress();
		} catch (IOException e) {
			return null;
		} catch (DbException e) {
			return null;
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
				s.getPinnedPostSeq());
	}

	private byte[] handlePublisherRequest(byte[] channelId,
			byte[] requestBytes) {
		String wireType = pullCodec().peekType(requestBytes);
		if (ChannelConstants.WIRE_TYPE_GET_ATTACHMENT.equals(wireType)) {
			return handleAttachmentFetch(channelId, requestBytes);
		}
		if (ChannelConstants.WIRE_TYPE_POST_REACTION.equals(wireType)) {
			return handleReactionRequest(channelId, requestBytes);
		}
		if (ChannelConstants.WIRE_TYPE_ANNOUNCE.equals(wireType)) {
			return handleAnnounceRequest(channelId, requestBytes);
		}
		if (ChannelConstants.WIRE_TYPE_POST_COMMENT.equals(wireType)) {
			return handleCommentRequest(channelId, requestBytes);
		}
		try {
			ChannelPullCodec.PullRequest req = pullCodec()
					.decodePullRequest(requestBytes);
			ChannelState s = store.getChannel(channelId);
			if (s == null) return new byte[0];
			java.util.List<ChannelPost> all =
					store.getPosts(channelId);
			java.util.List<ChannelPost> toSend =
					new java.util.ArrayList<>();
			for (ChannelPost p : all) {
				if (p.getSeqNum() > req.sinceSeqNum) toSend.add(p);
			}
			byte[] envelope = null;
			if (!s.isPublicChannel() && req.hmacResponse != null
					&& req.nonce != null
					&& s.getJoinCapability() != null
					&& s.getContentKey() != null) {
				if (verifyChallenge(s.getJoinCapability(),
						req.nonce, channelId, req.hmacResponse)) {
					try {
						envelope = contentKey.wrapContentKey(
								s.getJoinCapability(), channelId,
								s.getContentKey());
					} catch (GeneralSecurityException ignored) {
						envelope = null;
					}
				}
			}
			java.util.List<ChannelPost> wirePosts =
					convertToWirePosts(s, toSend);
			byte[] manifestSig = signLatestManifest(s);
			java.util.List<org.briarproject.briar.api.channel
					.ChannelReaction> reactions =
					reactionStore.getReactions(channelId);
			java.util.List<org.briarproject.briar.api.channel
					.ChannelComment> comments =
					commentStore.getComments(channelId);
			return pullProtocol.buildResponseAsPublisher(s,
					s.getPublisherEd25519PubKey(),
					s.getPublisherMlDsaPubKey(), manifestSig,
					wirePosts, envelope,
					java.util.Collections.<String>emptyList(),
					reactions, comments);
		} catch (IOException | DbException e) {
			return new byte[0];
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

	private byte[] signLatestManifest(ChannelState s) {
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
				s.getPinnedPostSeq());
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
	public void refreshChannel(byte[] channelId) throws DbException {
		pullAndApply(channelId, false);
	}

	private void pullAndApply(byte[] channelId, boolean isBootstrap)
			throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (s.weArePublisher()) return;
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
		java.util.List<ChannelPost> existing =
				store.getPosts(channelId);
		ChannelPullProtocol.ProcessResult r =
				pullProtocol.processSubscriberResponse(responseBytes,
						s, existing, s.getJoinCapability());
		if (!r.ok || r.mergedState == null) {
			throw new DbException();
		}
		store.putChannel(r.mergedState);
		for (ChannelPost p : r.acceptedPosts) {
			acceptIncomingPost(channelId, p);
		}
		applyIncomingReactions(channelId, r.reactions);
		applyIncomingComments(channelId, r.comments);
	}

	private void applyIncomingComments(byte[] channelId,
			java.util.List<org.briarproject.briar.api.channel
					.ChannelComment> incoming) throws DbException {
		if (incoming.isEmpty()) return;
		for (org.briarproject.briar.api.channel.ChannelComment c
				: incoming) {
			commentStore.putComment(channelId, c);
		}
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
	}

	private void applyIncomingReactions(byte[] channelId,
			java.util.List<org.briarproject.briar.api.channel
					.ChannelReaction> incoming) throws DbException {
		if (incoming.isEmpty()) return;
		for (org.briarproject.briar.api.channel.ChannelReaction r
				: incoming) {
			reactionStore.putReaction(channelId, r);
		}
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
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

	@Inject org.briarproject.bramble.api.data.BdfReaderFactory
			readerFactory;
	@Inject org.briarproject.bramble.api.data.BdfWriterFactory
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
				s.getPinnedPostSeq());
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
		store.removeChannel(channelId);
		blobStore.removeAllForChannel(channelId);
		reactionStore.removeAll(channelId);
		subscriberStore.removeAll(channelId);
		commentStore.removeAll(channelId);
		fireEvent(channelId, ChannelStateChangedEvent.Kind.LEFT);
	}

	@Override
	public String exportInviteLink(byte[] channelId) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		return codec.formatInviteLink(s.getChannelId(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(),
				s.isPublicChannel(),
				s.getJoinCapability(),
				s.getCurrentOnion());
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
				0L,
				false,
				-1L);
		store.putChannel(provisional);
		store.writePosts(link.getChannelId(), Collections.emptyList());
		fireEvent(link.getChannelId(),
				ChannelStateChangedEvent.Kind.JOINED);
		return provisional;
	}

	@Override
	public void leaveChannel(byte[] channelId) throws DbException {
		store.removeChannel(channelId);
		blobStore.removeAllForChannel(channelId);
		reactionStore.removeAll(channelId);
		subscriberStore.removeAll(channelId);
		commentStore.removeAll(channelId);
		fireEvent(channelId, ChannelStateChangedEvent.Kind.LEFT);
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
		eventBus.broadcast(new ChannelPostReceivedEvent(channelId, nextSeq));
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
		java.util.Set<Long> deletedSeqs = new java.util.HashSet<>();
		List<ChannelPost> decoded = new ArrayList<>(all.size());
		for (ChannelPost p : all) {
			ChannelPost view = encrypted ? decryptForDisplay(p, kContent)
					: p;
			decoded.add(view);
			Long target = parseTombstoneTarget(view.getBody(),
					channelIdHex);
			if (target != null) deletedSeqs.add(target);
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
						p.getSignature(), true));
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
				s.getPinnedPostSeq());
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
		org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey
				hybridPriv = new org.briarproject.bramble.api.crypto
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
				org.briarproject.briar.api.channel.event
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
				org.briarproject.briar.api.channel.event
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
			java.util.List<org.briarproject.briar.api.channel
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
		for (org.briarproject.briar.api.channel.AttachmentSpec spec
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
					"org.briarproject.zerion/CHANNEL_ATTACHMENT_BLOB",
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
	public org.briarproject.briar.api.channel.AttachmentBlob
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
			byte[] reqBytes = pullCodec().encodeAttachmentRequest(
					channelId, blobHash);
			byte[] respBytes = transport.requestFromOnion(
					s.getCurrentOnion(), reqBytes);
			ChannelPullCodec.AttachmentResponse resp =
					pullCodec().decodeAttachmentResponse(respBytes);
			if (resp.blob.length == 0) return null;
			if (!java.util.Arrays.equals(resp.blobHash, blobHash)) {
				return null;
			}
			byte[] derived = crypto.hash(
					"org.briarproject.zerion/CHANNEL_ATTACHMENT_BLOB",
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
		return new org.briarproject.briar.api.channel.AttachmentBlob(
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
		LocalAuthor me = identityManager.getLocalAuthor();
		byte[] hybridPubEncoded = me.getPublicKey().getEncoded();
		byte[] signerEd = new byte[32];
		byte[] signerMl = new byte[hybridPubEncoded.length - 32];
		System.arraycopy(hybridPubEncoded, 0, signerEd, 0, 32);
		System.arraycopy(hybridPubEncoded, 32, signerMl, 0,
				signerMl.length);
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		String authorName = pickAuthorName(channelId, signerEd, me);
		long commentId = random.nextLong();
		byte[] signedInput = codec.commentSignedInput(channelId,
				parentPostSeqNum, commentId, trimmed, authorName, ts);
		byte[] sig;
		try {
			sig = signatures.signComment(signedInput,
					me.getPrivateKey());
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		org.briarproject.briar.api.channel.ChannelComment row =
				new org.briarproject.briar.api.channel.ChannelComment(
						parentPostSeqNum, commentId, trimmed, authorName,
						signerEd, signerMl, ts);
		if (s.weArePublisher()) {
			commentStore.putComment(channelId, row);
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			return;
		}
		try {
			byte[] reqBytes = pullCodec().encodeCommentRequest(
					channelId, parentPostSeqNum, commentId, trimmed,
					authorName, ts, signerEd, signerMl, sig);
			transport.requestFromOnion(s.getCurrentOnion(), reqBytes);
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
	public java.util.List<org.briarproject.briar.api.channel
			.ChannelComment> getComments(byte[] channelId,
					long parentPostSeqNum) throws DbException {
		java.util.List<org.briarproject.briar.api.channel
				.ChannelComment> all =
				commentStore.getComments(channelId);
		java.util.List<org.briarproject.briar.api.channel
				.ChannelComment> out = new ArrayList<>();
		for (org.briarproject.briar.api.channel.ChannelComment c : all) {
			if (c.getParentPostSeqNum() == parentPostSeqNum) out.add(c);
		}
		return out;
	}

	private byte[] handleCommentRequest(byte[] channelId,
			byte[] requestBytes) {
		try {
			ChannelPullCodec.CommentRequest req = pullCodec()
					.decodeCommentRequest(requestBytes);
			if (!java.util.Arrays.equals(req.channelId, channelId)) {
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
			org.briarproject.bramble.api.crypto.HybridSignaturePublicKey
					pub = new org.briarproject.bramble.api.crypto
					.HybridSignaturePublicKey(req.signerEd25519,
							req.signerMlDsa);
			if (!signatures.verifyComment(req.signature, signedInput,
					pub)) {
				return safeCommentAck(false);
			}
			if (subscriberStore.isBanned(channelId, req.signerEd25519)) {
				return safeCommentAck(false);
			}
			java.util.List<org.briarproject.briar.api.channel
					.ChannelComment> existing =
					commentStore.getComments(channelId);
			if (existing.size()
					>= ChannelConstants.MAX_COMMENTS_PER_CHANNEL) {
				return safeCommentAck(false);
			}
			commentStore.putComment(channelId,
					new org.briarproject.briar.api.channel.ChannelComment(
							req.parentPostSeqNum, req.commentId,
							req.body, req.authorName,
							req.signerEd25519, req.signerMlDsa,
							req.timestampHourMs));
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
		byte[] hybridPubEncoded = me.getPublicKey().getEncoded();
		byte[] signerEd = new byte[32];
		byte[] signerMl = new byte[hybridPubEncoded.length - 32];
		System.arraycopy(hybridPubEncoded, 0, signerEd, 0, 32);
		System.arraycopy(hybridPubEncoded, 32, signerMl, 0,
				signerMl.length);
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		byte[] signedInput = codec.announceSignedInput(channelId,
				trimmed, ts);
		byte[] sig;
		try {
			sig = signatures.signAnnounce(signedInput, me.getPrivateKey());
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
			byte[] reqBytes = pullCodec().encodeAnnounceRequest(
					channelId, trimmed, ts, signerEd, signerMl, sig);
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
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		if (!s.weArePublisher()) throw new DbException();
		subscriberStore.setBanned(channelId, ed25519PubKey, true);
		if (!s.isPublicChannel()) {
			rotateJoinCapability(channelId);
		}
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
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
			org.briarproject.bramble.api.crypto.HybridSignaturePublicKey
					pub = new org.briarproject.bramble.api.crypto
					.HybridSignaturePublicKey(req.signerEd25519,
							req.signerMlDsa);
			if (!signatures.verifyAnnounce(req.signature, signedInput,
					pub)) {
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
		byte[] hybridPubEncoded = me.getPublicKey().getEncoded();
		byte[] signerEd = new byte[32];
		byte[] signerMl = new byte[hybridPubEncoded.length - 32];
		System.arraycopy(hybridPubEncoded, 0, signerEd, 0, 32);
		System.arraycopy(hybridPubEncoded, 32, signerMl, 0,
				signerMl.length);
		long ts = clock.currentTimeMillis() / HOUR_MS * HOUR_MS;
		byte[] signedInput = codec.reactionSignedInput(channelId,
				postSeqNum, emoji, ts);
		byte[] sig;
		try {
			sig = signatures.signReaction(signedInput, me.getPrivateKey());
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		boolean amPublisher = s.weArePublisher();
		if (amPublisher) {
			reactionStore.putReaction(channelId,
					new org.briarproject.briar.api.channel
							.ChannelReaction(postSeqNum, emoji,
									signerEd, signerMl, ts));
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
			return;
		}
		try {
			byte[] reqBytes = pullCodec().encodeReactionRequest(
					channelId, postSeqNum, emoji, ts, signerEd,
					signerMl, sig);
			transport.requestFromOnion(s.getCurrentOnion(), reqBytes);
			reactionStore.putReaction(channelId,
					new org.briarproject.briar.api.channel
							.ChannelReaction(postSeqNum, emoji,
									signerEd, signerMl, ts));
			fireEvent(channelId,
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		} catch (IOException ex) {
			throw new DbException(ex);
		}
	}

	@Override
	public java.util.List<org.briarproject.briar.api.channel
			.ChannelReaction> getReactions(byte[] channelId,
					long postSeqNum) throws DbException {
		java.util.List<org.briarproject.briar.api.channel
				.ChannelReaction> all =
				reactionStore.getReactions(channelId);
		java.util.List<org.briarproject.briar.api.channel
				.ChannelReaction> out = new ArrayList<>();
		for (org.briarproject.briar.api.channel.ChannelReaction r : all) {
			if (r.getPostSeqNum() == postSeqNum) out.add(r);
		}
		return out;
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
			org.briarproject.bramble.api.crypto.HybridSignaturePublicKey
					pub = new org.briarproject.bramble.api.crypto
					.HybridSignaturePublicKey(req.signerEd25519,
							req.signerMlDsa);
			if (!signatures.verifyReaction(req.signature, signedInput,
					pub)) {
				return safeAck(false);
			}
			java.util.List<org.briarproject.briar.api.channel
					.ChannelReaction> existing =
					reactionStore.getReactions(channelId);
			if (existing.size()
					>= ChannelConstants.MAX_REACTIONS_PER_POST
					* 16) {
				return safeAck(false);
			}
			reactionStore.putReaction(channelId,
					new org.briarproject.briar.api.channel
							.ChannelReaction(req.postSeqNum, req.emoji,
									req.signerEd25519, req.signerMlDsa,
									req.timestampHourMs));
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
				seqNum);
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
				s.getPinnedPostSeq());
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
					s.getPinnedPostSeq());
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
				incoming.getSeqNum()));
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
				s.getPinnedPostSeq());
	}

	private void clearReturned(byte[] b) {
		java.util.Arrays.fill(b, (byte) 0);
	}
}
