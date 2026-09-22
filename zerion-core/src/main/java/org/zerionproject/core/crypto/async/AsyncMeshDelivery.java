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

		/**
		 * True when the sender is known; only a known sender's envelopes
		 * are recorded in the replay store, so a stranger cannot fill it.
		 */
		default boolean knowsSender(byte[] senderIdentitySigPub) {
			return true;
		}
	}

	/**
	 * Recently opened envelopes, by dedup id, so a repeat of an envelope from
	 * a stranger costs a map lookup rather than another open. In memory
	 * only; the durable record is the per-sender store.
	 */
	private static final int RECENTLY_OPENED = 1024;
	private final java.util.LinkedHashMap<String, Boolean> recentlyOpened =
			new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(
						java.util.Map.Entry<String, Boolean> eldest) {
					return size() > RECENTLY_OPENED;
				}
			};

	private static final long MAX_TTL_SECONDS = 30L * 24 * 60 * 60;
	private static final long CLOCK_SKEW_TOLERANCE_MS = 60L * 1000;

	private final CryptoComponent crypto;
	private final AsyncSealedSender sealer;
	private final AsyncPrekeyStore store;
	private final OpenedListener listener;
	private final Identity identity;
	private final org.zerionproject.core.api.system.Clock clock;
	private final SecureRandom random = new SecureRandom();

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

		public Identity(byte[] sigPub, PrivateKey sigPriv, byte[] agreePub) {
			this.sigPub = sigPub;
			this.sigPriv = sigPriv;
			this.agreePub = agreePub;
		}
	}

	public byte[] send(MeshForwarder forwarder,
			AsyncPrekeyBundle recipientBundle, int messageType,
			byte[] payload, long ttlSeconds, long sendTimestamp,
			boolean preferOneTime) throws GeneralSecurityException {
		AsyncSealedSender.SealRequest r = new AsyncSealedSender.SealRequest();
		List<AsyncPrekeyBundle.OneTimePrekey> otks =
				recipientBundle.getOneTimePrekeys();
		if (preferOneTime && !otks.isEmpty()) {
			AsyncPrekeyBundle.OneTimePrekey otk =
					otks.get(random.nextInt(otks.size()));
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
			long spk = 1 + (long) (-Math.log(1.0 - random.nextDouble()) * 6.0);
			r.signedPrekeyId = Math.max(1, Math.min(spk, 120));
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
			KeyPair prekey = store.resolvePrekey(env.getPrekeyKind(),
					env.getPrekeyId(), env.getSignedPrekeyId());
			if (prekey == null) return;
			AsyncSealedSender.OpenRequest o =
					new AsyncSealedSender.OpenRequest();
			o.recipientAgreementKeyPair = prekey;
			o.recipientIdentitySigPub = identity.sigPub;
			o.recipientIdentityAgreePub = identity.agreePub;
			AsyncSealedSender.OpenedMessage m = sealer.open(envelopeBytes, o);
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
			if (accepted && env.getPrekeyKind()
					== AsyncEnvelope.PREKEY_KIND_ONE_TIME) {
				store.consumeOneTimePrekey(env.getPrekeyId());
			}
		} catch (Exception e) {
		}
	}

	private PublicKey parseAgreement(byte[] encoded)
			throws GeneralSecurityException {
		return crypto.getHybridAgreementKeyParser().parsePublicKey(encoded);
	}
}
