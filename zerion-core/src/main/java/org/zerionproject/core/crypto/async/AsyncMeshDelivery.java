package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.transport.mesh.MeshForwarder;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class AsyncMeshDelivery implements MeshForwarder.FrameListener {

	public interface OpenedListener {
		boolean onOpened(byte[] senderIdentitySigPub, int messageType,
				byte[] payload, long sendTimestamp);

		default boolean knowsSender(byte[] senderIdentitySigPub) {
			return true;
		}
	}

	private static final int RECENTLY_OPENED = 1024;
	private final java.util.LinkedHashMap<String, Boolean> recentlyOpened =
			new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(
						java.util.Map.Entry<String, Boolean> eldest) {
					return size() > RECENTLY_OPENED;
				}
			};

	private static final int RECENTLY_FAILED = 4096;
	private static final String LABEL_FAILED_OPEN =
			"org.zerionproject.async/FAILED_OPEN";
	private final java.util.LinkedHashMap<String, Boolean> recentlyFailed =
			new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(
						java.util.Map.Entry<String, Boolean> eldest) {
					return size() > RECENTLY_FAILED;
				}
			};

	static final int OPEN_ATTEMPTS_PER_SECOND = 48;
	static final int OPEN_ATTEMPT_BURST = 96;
	static final int PEER_OPEN_ATTEMPTS_PER_SECOND = 16;
	static final int PEER_OPEN_ATTEMPT_BURST = 32;
	private static final int MAX_PEER_BUDGETS = 256;
	private final Object budgetLock = new Object();
	private final Budget nodeBudget =
			new Budget(OPEN_ATTEMPTS_PER_SECOND, OPEN_ATTEMPT_BURST);
	private final java.util.LinkedHashMap<String, Budget> peerBudgets =
			new java.util.LinkedHashMap<String, Budget>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(
						java.util.Map.Entry<String, Budget> eldest) {
					return size() > MAX_PEER_BUDGETS;
				}
			};

	private static final class Budget {
		final int perSecond;
		final int burst;
		double tokens;
		long refilledAt = Long.MIN_VALUE;

		Budget(int perSecond, int burst) {
			this.perSecond = perSecond;
			this.burst = burst;
			this.tokens = burst;
		}

		void refill(long now) {
			if (refilledAt == Long.MIN_VALUE) refilledAt = now;
			long elapsed = Math.max(0L, now - refilledAt);
			tokens = Math.min(burst, tokens + elapsed * perSecond / 1000.0);
			refilledAt = now;
		}
	}

	private static final long MAX_TTL_SECONDS = 30L * 24 * 60 * 60;
	private static final long CLOCK_SKEW_TOLERANCE_MS = 60L * 1000;

	private final CryptoComponent crypto;
	private final AsyncSealedSender sealer;
	private final AsyncPrekeyStore store;
	private final OpenedListener listener;
	private final Identity identity;
	private final org.zerionproject.core.api.system.Clock clock;
	private final SecureRandom random = new SecureRandom();

	private static final int MAX_USED_ONE_TIME_KEYS = 4096;
	private final java.util.LinkedHashSet<String> usedOneTimeKeys =
			new java.util.LinkedHashSet<>();

	public AsyncMeshDelivery(CryptoComponent crypto, AsyncSealedSender sealer,
			AsyncPrekeyStore store, OpenedListener listener,
			Identity identity,
			org.zerionproject.core.api.system.Clock clock) {
		this.crypto = crypto;
		this.sealer = sealer;
		this.store = store;
		this.listener = listener;
		this.identity = identity;
		this.clock = clock;
	}

	public static class Identity {
		public final byte[] sigPub;
		public final PrivateKey sigPriv;
		public final byte[] agreePub;
		public final byte[] legacyAgreePub;

		public Identity(byte[] sigPub, PrivateKey sigPriv, byte[] agreePub) {
			this(sigPub, sigPriv, agreePub, agreePub);
		}

		public Identity(byte[] sigPub, PrivateKey sigPriv, byte[] agreePub,
				byte[] legacyAgreePub) {
			this.sigPub = sigPub;
			this.sigPriv = sigPriv;
			this.agreePub = agreePub;
			this.legacyAgreePub = legacyAgreePub;
		}
	}

	public byte[] send(MeshForwarder forwarder,
			AsyncPrekeyBundle recipientBundle, int messageType,
			byte[] payload, long ttlSeconds, long sendTimestamp,
			boolean preferOneTime) throws GeneralSecurityException {
		AsyncSealedSender.SealRequest r = new AsyncSealedSender.SealRequest();
		long nowSeconds = clock.currentTimeMillis() / 1000L;
		if (recipientBundle.getSignedPrekeyExpiry() <= nowSeconds) {
			throw new GeneralSecurityException("recipient signed prekey expired");
		}
		AsyncPrekeyBundle.OneTimePrekey otk = preferOneTime
				? pickUnusedOneTimePrekey(recipientBundle) : null;
		if (otk != null) {
			r.prekeyKind = AsyncEnvelope.PREKEY_KIND_ONE_TIME;
			r.prekeyId = otk.id;
			r.recipientAgreementPub = parseAgreement(otk.pub);
		} else {
			r.prekeyKind = AsyncEnvelope.PREKEY_KIND_SIGNED;
			r.prekeyId = new byte[AsyncEnvelope.PREKEY_ID_BYTES];
			r.recipientAgreementPub =
					parseAgreement(recipientBundle.getSignedPrekeyPub());
		}
		r.signedPrekeyId = recipientBundle.getSignedPrekeyId();
		r.recipientIdentitySigPub = recipientBundle.getIdentitySigPub();
		r.recipientIdentityAgreePub = recipientBundle.getIdentityAgreePub();
		r.senderIdentitySigPub = identity.sigPub;
		r.senderIdentitySigPrivateKey = identity.sigPriv;
		r.messageType = messageType;
		r.payload = payload;
		r.ttl = ttlSeconds;
		r.dedupId = new byte[AsyncEnvelope.DEDUP_ID_BYTES];
		random.nextBytes(r.dedupId);
		r.sendTimestamp = sendTimestamp;
		byte[] envelope = sealer.seal(r);
		return forwarder.originate(envelope);
	}

	@javax.annotation.Nullable
	private AsyncPrekeyBundle.OneTimePrekey pickUnusedOneTimePrekey(
			AsyncPrekeyBundle bundle) {
		List<AsyncPrekeyBundle.OneTimePrekey> otks = bundle.getOneTimePrekeys();
		if (otks.isEmpty()) return null;
		String recipient = org.zerionproject.core.util.StringUtils.toHexString(
				bundle.getIdentitySigPub());
		int start = random.nextInt(otks.size());
		synchronized (usedOneTimeKeys) {
			for (int i = 0; i < otks.size(); i++) {
				AsyncPrekeyBundle.OneTimePrekey otk =
						otks.get((start + i) % otks.size());
				String key = recipient + ":" + org.zerionproject.core.util
						.StringUtils.toHexString(otk.id);
				if (usedOneTimeKeys.contains(key)) continue;
				if (usedOneTimeKeys.size() >= MAX_USED_ONE_TIME_KEYS) {
					java.util.Iterator<String> it = usedOneTimeKeys.iterator();
					it.next();
					it.remove();
				}
				usedOneTimeKeys.add(key);
				return otk;
			}
		}
		return null;
	}

	public void sendCover(MeshForwarder forwarder, byte[] payload,
			long ttlSeconds, long sendTimestamp, boolean oneTimeKind)
			throws GeneralSecurityException {
		KeyPair throwaway = crypto.generateHybridAgreementKeyPair();
		try {
			AsyncSealedSender.SealRequest r =
					new AsyncSealedSender.SealRequest();
			byte[] prekeyId = new byte[AsyncEnvelope.PREKEY_ID_BYTES];
			if (oneTimeKind) {
				r.prekeyKind = AsyncEnvelope.PREKEY_KIND_ONE_TIME;
				random.nextBytes(prekeyId);
			} else {
				r.prekeyKind = AsyncEnvelope.PREKEY_KIND_SIGNED;
			}
			r.prekeyId = prekeyId;
			r.recipientAgreementPub = throwaway.getPublic();
			r.signedPrekeyId = AsyncPrekeyStore.PUBLISHED_SIGNED_PREKEY_ID;
			r.recipientIdentitySigPub = randomBytes(
					org.zerionproject.core.api.crypto.PostQuantumConstants
							.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES);
			r.recipientIdentityAgreePub = randomBytes(
					org.zerionproject.core.api.crypto.PostQuantumConstants
							.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES);
			r.senderIdentitySigPub = identity.sigPub;
			r.senderIdentitySigPrivateKey = identity.sigPriv;
			r.messageType = 0;
			r.payload = payload;
			r.ttl = ttlSeconds;
			r.dedupId = randomBytes(AsyncEnvelope.DEDUP_ID_BYTES);
			r.sendTimestamp = sendTimestamp;
			byte[] envelope = sealer.seal(r);
			forwarder.originate(envelope);
		} finally {
			PrivateKey priv = throwaway.getPrivate();
			if (priv instanceof org.zerionproject.core.api.crypto
					.HybridAgreementPrivateKey) {
				((org.zerionproject.core.api.crypto.HybridAgreementPrivateKey)
						priv).clear();
			}
		}
	}

	private byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}

	@Override
	public void onFrame(byte[] envelopeBytes) {
		onFrame(envelopeBytes, "");
	}

	@Override
	public void onFrame(byte[] envelopeBytes, String fromPeer) {
		AsyncEnvelope env;
		try {
			env = AsyncEnvelope.decode(envelopeBytes);
		} catch (Exception e) {
			return;
		}
		try {
			long ttl = env.getTtl();
			if (ttl < 0 || ttl > MAX_TTL_SECONDS) return;
			String dedupHex = org.zerionproject.core.util.StringUtils
					.toHexString(env.getDedupId());
			synchronized (recentlyOpened) {
				if (recentlyOpened.containsKey(dedupHex)) return;
			}
			if (store.isSeen(env.getDedupId())) return;
			List<AsyncPrekeyStore.PrekeyCandidate> candidates =
					store.resolveCandidates(env.getPrekeyKind(),
							env.getPrekeyId(), env.getSignedPrekeyId(), ttl);
			if (candidates.isEmpty()) return;
			String failedKey = org.zerionproject.core.util.StringUtils
					.toHexString(crypto.hash(LABEL_FAILED_OPEN, envelopeBytes));
			synchronized (recentlyFailed) {
				if (recentlyFailed.containsKey(failedKey)) return;
			}
			if (!takeOpenBudget(fromPeer, candidates.size())) return;
			AsyncSealedSender.OpenedMessage m = null;
			AsyncPrekeyStore.PrekeyCandidate used = null;
			for (AsyncPrekeyStore.PrekeyCandidate c : candidates) {
				AsyncSealedSender.OpenRequest o =
						new AsyncSealedSender.OpenRequest();
				o.recipientAgreementKeyPair = c.keyPair;
				o.recipientIdentitySigPub = identity.sigPub;
				o.recipientIdentityAgreePub = c.published
						? identity.agreePub : identity.legacyAgreePub;
				try {
					m = sealer.open(envelopeBytes, o);
					used = c;
					break;
				} catch (GeneralSecurityException
						| org.zerionproject.core.api.FormatException e) {
				}
			}
			if (m == null || used == null) {
				synchronized (recentlyFailed) {
					recentlyFailed.put(failedKey, Boolean.TRUE);
				}
				return;
			}
			long now = clock.currentTimeMillis();
			long expiry = m.getSendTimestamp() + ttl * 1000L;
			if (now > expiry
					|| m.getSendTimestamp() > now + CLOCK_SKEW_TOLERANCE_MS) {
				return;
			}
			synchronized (recentlyOpened) {
				recentlyOpened.put(dedupHex, Boolean.TRUE);
			}
			if (!listener.knowsSender(m.getSenderIdentitySigPub())) return;
			if (!store.checkAndMarkSeen(m.getSenderIdentitySigPub(),
					env.getDedupId(), expiry)) {
				return;
			}
			boolean accepted = listener.onOpened(m.getSenderIdentitySigPub(),
					m.getMessageType(), m.getPayload(), m.getSendTimestamp());
			if (accepted && used.oneTimeId != null) {
				store.consumeOneTimePrekey(used.oneTimeId);
			}
		} catch (Exception e) {
		}
	}

	private boolean takeOpenBudget(String fromPeer, int attempts) {
		synchronized (budgetLock) {
			long now = clock.currentTimeMillis();
			Budget peer = peerBudgets.get(fromPeer);
			if (peer == null) {
				peer = new Budget(PEER_OPEN_ATTEMPTS_PER_SECOND,
						PEER_OPEN_ATTEMPT_BURST);
				peerBudgets.put(fromPeer, peer);
			}
			nodeBudget.refill(now);
			peer.refill(now);
			if (nodeBudget.tokens < attempts || peer.tokens < attempts) {
				return false;
			}
			nodeBudget.tokens -= attempts;
			peer.tokens -= attempts;
			return true;
		}
	}

	private PublicKey parseAgreement(byte[] encoded)
			throws GeneralSecurityException {
		return crypto.getHybridAgreementKeyParser().parsePublicKey(encoded);
	}
}
