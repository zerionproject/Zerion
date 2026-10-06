package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.wire.ZwfStreamCounter;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

@NotNullByDefault
public class ZtpConnectionEstablisher {

	private final CryptoComponent crypto;
	private final PcsRatchet ratchet;
	private final Mode3FullRatchet mode3FullRatchet;
	private final ZwfSessionFactory sessionFactory;
	private final ZwfStreamCounter counter;
	private final Supplier<AuthenticatedCipher> cipherFactory;

	public ZtpConnectionEstablisher(CryptoComponent crypto, PcsRatchet ratchet,
			Mode3FullRatchet mode3FullRatchet, ZwfSessionFactory sessionFactory,
			ZwfStreamCounter counter,
			Supplier<AuthenticatedCipher> cipherFactory) {
		this.crypto = crypto;
		this.ratchet = ratchet;
		this.mode3FullRatchet = mode3FullRatchet;
		this.sessionFactory = sessionFactory;
		this.counter = counter;
		this.cipherFactory = cipherFactory;
	}

	public ZwfDuplexConnection resume(int contactId, SecretKey rootKey,
			boolean alice, InputStream in, OutputStream out) {
		return resume(contactId, rootKey, alice,
				counter.generation(contactId), in, out);
	}

	public ZwfDuplexConnection resume(int contactId, SecretKey rootKey,
			boolean alice, long generation, InputStream in, OutputStream out) {
		return resume(contactId, ContactRootKeys.atPairing(rootKey), 0, alice,
				generation, in, out);
	}

	public ZwfDuplexConnection resume(int contactId, ContactRootKeys keys,
			long sendEpoch, boolean alice, long generation, InputStream in,
			OutputStream out) {
		ZwfSession session = sessionFactory.deriveSession(keys, sendEpoch,
				alice);
		return new ZwfDuplexConnection(contactId, generation, session, counter,
				crypto, ratchet, mode3FullRatchet, cipherFactory, in, out);
	}

	public long epochOfTag(int contactId, ContactRootKeys keys, boolean alice,
			byte[] tag) {
		long highWater = counter.currentRecvHighWater(contactId);
		long[] epochs = {keys.getEpoch(), keys.getPendingEpoch()};
		org.zerionproject.crypto.ZwfTagRecogniser r =
				new org.zerionproject.crypto.ZwfTagRecogniser(crypto,
						org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE);
		java.util.Map<Long, SecretKey> tagKeys =
				new java.util.LinkedHashMap<>();
		for (long e : epochs) {
			SecretKey root = keys.getKey(e);
			if (root != null) {
				tagKeys.put(e, sessionFactory.deriveRecvTagKey(root, e, alice));
			}
		}
		r.register(contactId, tagKeys, highWater);
		org.zerionproject.crypto.ZwfTagRecogniser.Match m = r.recognise(tag);
		for (SecretKey k : tagKeys.values()) k.clear();
		return m == null ? -1 : m.epoch;
	}
}
