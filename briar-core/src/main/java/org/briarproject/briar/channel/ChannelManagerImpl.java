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
	private final TaskScheduler taskScheduler;
	private final java.util.concurrent.Executor ioExecutor;
	private final SecureRandom random;
	private final java.util.Map<String, ChannelTransport.ChannelServer>
			boundServers =
					new java.util.concurrent.ConcurrentHashMap<>();

	@Inject
	ChannelManagerImpl(CryptoComponent crypto, EventBus eventBus,
			Clock clock, ChannelCodec codec,
			ChannelSignatures signatures,
			ChannelChainVerifier chainVerifier, ChannelStore store,
			ChannelContentKey contentKey,
			ChannelPostValidator validator,
			ChannelPullProtocol pullProtocol,
			ChannelTransport transport,
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
		ioExecutor.execute(this::rebindOwnedChannelsOnStartup);
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
				s.getNextDelegationSeq());
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
		String onion = readLocalOnion();
		long manifestSeq = 0L;
		byte[] signedInput = codec.manifestSignedInput(channelId, salt,
				ed25519Pub, mlDsaPub, name, description, null,
				nowHourMs, publicChannel, capability, onion, manifestSeq);
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
			ChannelTransport.ChannelServer server =
					transport.bindServer(channelId,
							requestBytes -> handlePublisherRequest(
									channelId, requestBytes));
			boundServers.put(ChannelStore.hex(channelId), server);
			return server.getOnionAddress();
		} catch (IOException e) {
			return null;
		}
	}

	private byte[] handlePublisherRequest(byte[] channelId,
			byte[] requestBytes) {
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
			return pullProtocol.buildResponseAsPublisher(s,
					s.getPublisherEd25519PubKey(),
					s.getPublisherMlDsaPubKey(), manifestSig,
					wirePosts, envelope,
					java.util.Collections.<String>emptyList());
		} catch (IOException | DbException e) {
			return new byte[0];
		}
	}

	private java.util.List<ChannelPost> convertToWirePosts(
			ChannelState s, java.util.List<ChannelPost> stored) {
		if (s.isPublicChannel()) return stored;
		byte[] kContent = s.getContentKey();
		if (kContent == null) return stored;
		java.util.List<ChannelPost> out =
				new java.util.ArrayList<>(stored.size());
		for (ChannelPost p : stored) {
			try {
				byte[] ct = contentKey.encryptBody(kContent,
						p.getChannelId(), p.getSeqNum(), p.getBody());
				String wireBody = new String(ct,
						java.nio.charset.StandardCharsets.ISO_8859_1);
				out.add(new ChannelPost(p.getChannelId(),
						p.getSeqNum(), p.getPrevHash(),
						p.getTimestampHourMs(), wireBody,
						p.getAttachments(), p.getTtlMs(),
						p.getSignature(), p.isRead(),
						p.getDelegateSignerEd25519PubKey(),
						p.getDelegateSignerMlDsaPubKey()));
			} catch (GeneralSecurityException e) {
				return stored;
			}
		}
		return out;
	}

	private byte[] signLatestManifest(ChannelState s) {
		byte[] signedInput = codec.manifestSignedInput(s.getChannelId(),
				s.getSalt(), s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), s.getCurrentOnion(),
				s.getManifestSeq());
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
				s.getNextDelegationSeq());
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
		fireEvent(channelId, ChannelStateChangedEvent.Kind.LEFT);
	}

	@Override
	public String exportInviteLink(byte[] channelId) throws DbException {
		ChannelState s = store.getChannel(channelId);
		if (s == null) throw new DbException();
		return codec.formatInviteLink(s.getChannelId(),
				s.getPublisherEd25519PubKey(), s.isPublicChannel(),
				s.getJoinCapability());
	}

	@Nullable
	@Override
	public ChannelInviteLink parseInviteLink(String url) {
		return codec.parseInviteLink(url);
	}

	@Override
	public ChannelState joinChannel(ChannelInviteLink link)
			throws DbException {
		ChannelState existing = store.getChannel(link.getChannelId());
		if (existing != null) return existing;
		ChannelState provisional = new ChannelState(
				link.getChannelId(),
				new byte[ChannelConstants.CHANNEL_SALT_BYTES],
				link.getPublisherEd25519PubKey(),
				new byte[0],
				"",
				"",
				null,
				clock.currentTimeMillis() / HOUR_MS * HOUR_MS,
				link.isPublicChannel(),
				link.getJoinCapability(),
				"",
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
		fireEvent(channelId, ChannelStateChangedEvent.Kind.LEFT);
	}

	@Override
	public void publishPost(byte[] channelId, String body, long ttlSeconds)
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
		List<ChannelPost.ChannelAttachment> noAttachments =
				Collections.emptyList();
		byte[] attHash = codec.attachmentsHash(noAttachments);

		String wireBody = body;
		if (!s.isPublicChannel()) {
			byte[] kContent = s.getContentKey();
			if (kContent == null) throw new DbException();
			try {
				byte[] ct = contentKey.encryptBody(kContent, channelId,
						nextSeq, body);
				wireBody = new String(ct,
						java.nio.charset.StandardCharsets.ISO_8859_1);
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
				nowHourMs, body, noAttachments, ttlMs, sig, true);
		store.appendPost(channelId, post);
		ChannelState updated = withSeq(s, nextSeq);
		store.putChannel(updated);
		eventBus.broadcast(new ChannelPostReceivedEvent(channelId, nextSeq));
	}

	@Override
	public List<ChannelPost> getRecentPosts(byte[] channelId, long limit)
			throws DbException {
		List<ChannelPost> all = store.getPosts(channelId);
		if (all.size() <= limit) {
			return new ArrayList<>(all);
		}
		return new ArrayList<>(
				all.subList((int) (all.size() - limit), all.size()));
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
				s.getNextDelegationSeq());
		store.putChannel(updated);
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
	}

	@Override
	public ChannelDelegationCert delegatePublisher(byte[] channelId,
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
	public void purgeExpiredPosts() throws DbException {
		long now = clock.currentTimeMillis();
		for (ChannelState s : store.listChannels()) {
			java.util.List<ChannelPost> kept = new java.util.ArrayList<>();
			boolean changed = false;
			for (ChannelPost p : store.getPosts(s.getChannelId())) {
				if (p.getTtlMs() > 0
						&& now > p.getTimestampHourMs() + p.getTtlMs()) {
					changed = true;
					continue;
				}
				kept.add(p);
			}
			if (changed) store.writePosts(s.getChannelId(), kept);
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
				active, revoked, nextSeq);
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
					s.getHighestKnownPostSeq());
			store.putChannel(updated);
			fireEvent(s.getChannelId(),
					ChannelStateChangedEvent.Kind.MANIFEST_UPDATED);
		}
	}

	void acceptIncomingPost(byte[] channelId, ChannelPost incoming)
			throws DbException {
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

	private String readLocalOnion() {
		return "";
	}

	private ChannelState withSeq(ChannelState s, long newHighSeq) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(),
				s.getPublisherMlDsaPubKey(), s.getName(),
				s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), s.getCurrentOnion(),
				s.getManifestSeq(), s.weArePublisher(), newHighSeq);
	}

	private void clearReturned(byte[] b) {
		java.util.Arrays.fill(b, (byte) 0);
	}
}
