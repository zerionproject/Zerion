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
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.channel.ChannelConstants;
import org.briarproject.briar.api.channel.ChannelDelegationCert;
import org.briarproject.briar.api.channel.ChannelInviteLink;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.ChannelPost;
import org.briarproject.briar.api.channel.ChannelState;
import org.briarproject.briar.api.channel.event.ChannelPostReceivedEvent;
import org.briarproject.briar.api.channel.event.ChannelStateChangedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

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
	private final SecureRandom random;

	@Inject
	ChannelManagerImpl(CryptoComponent crypto, EventBus eventBus,
			Clock clock, ChannelCodec codec,
			ChannelSignatures signatures,
			ChannelChainVerifier chainVerifier, ChannelStore store,
			ChannelContentKey contentKey,
			ChannelPostValidator validator) {
		this.crypto = crypto;
		this.eventBus = eventBus;
		this.clock = clock;
		this.codec = codec;
		this.signatures = signatures;
		this.chainVerifier = chainVerifier;
		this.store = store;
		this.contentKey = contentKey;
		this.validator = validator;
		this.random = new SecureRandom();
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		eventBus.addListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
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
		fireEvent(channelId,
				ChannelStateChangedEvent.Kind.CREATED);
		clearReturned(manifestSig);
		return state;
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
