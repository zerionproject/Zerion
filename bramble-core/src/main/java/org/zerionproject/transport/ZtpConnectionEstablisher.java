package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.contact.HandshakeCrypto;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.handshake.ZwfHandshake;
import org.zerionproject.handshake.ZwfHandshakeResult;
import org.zerionproject.wire.ZwfStreamCounter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

/**
 * Turns a raw transport connection (a Tor socket's streams) into a live,
 * post-quantum {@link ZwfDuplexConnection}: it runs the native handshake over
 * the streams, derives the transport session from the resulting root key, and
 * hands back a duplex connection ready to carry messages on the same streams.
 *
 * <p>This is the single seam the Tor transport calls once it has a connected
 * socket; it is transport-agnostic and fully exercisable over in-memory pipes.
 */
@NotNullByDefault
public class ZtpConnectionEstablisher {

	private final CryptoComponent crypto;
	private final HandshakeCrypto handshakeCrypto;
	private final PcsRatchet ratchet;
	private final Mode3FullRatchet mode3FullRatchet;
	private final ZwfSessionFactory sessionFactory;
	private final ZwfStreamCounter counter;
	private final Supplier<AuthenticatedCipher> cipherFactory;

	public ZtpConnectionEstablisher(CryptoComponent crypto,
			HandshakeCrypto handshakeCrypto, PcsRatchet ratchet,
			Mode3FullRatchet mode3FullRatchet, ZwfSessionFactory sessionFactory,
			ZwfStreamCounter counter,
			Supplier<AuthenticatedCipher> cipherFactory) {
		this.crypto = crypto;
		this.handshakeCrypto = handshakeCrypto;
		this.ratchet = ratchet;
		this.mode3FullRatchet = mode3FullRatchet;
		this.sessionFactory = sessionFactory;
		this.counter = counter;
		this.cipherFactory = cipherFactory;
	}

	/**
	 * Runs the handshake and returns a live connection over the same streams.
	 *
	 * @param contactId the local contact id.
	 * @param ourStaticKeyPair our hybrid handshake identity key pair.
	 * @param peerCommitment the peer's key commitment from the pairing link.
	 */
	public ZwfDuplexConnection establish(int contactId,
			KeyPair ourStaticKeyPair, byte[] peerCommitment, InputStream in,
			OutputStream out) throws IOException {
		ZwfHandshakeResult result = new ZwfHandshake(crypto, handshakeCrypto)
				.run(ourStaticKeyPair, peerCommitment, in, out);
		ZwfSession session = sessionFactory.deriveSession(result.getRootKey(),
				result.isAlice());
		return new ZwfDuplexConnection(contactId, session, counter, crypto,
				ratchet, mode3FullRatchet, cipherFactory, in, out);
	}

	/**
	 * Resumes an ongoing contact's connection over the streams without a
	 * handshake. Used for every connection after the initial pairing: the root
	 * key and role were fixed at pairing, so no key agreement runs.
	 *
	 * <p>The post-quantum Mode 3-Full ratchet starts from a fresh initial state on
	 * each connection and re-engages in-band (the first frame per direction is the
	 * classical sentinel, after which each side re-advertises its ML-KEM key). The
	 * ratchet is <em>not</em> resumed from the persisted state: because it is a
	 * single per-connection state shared and advanced asynchronously by both
	 * directions, an abrupt drop leaves the two peers with divergent saved states,
	 * and resuming from them fails to decrypt. Starting fresh makes every
	 * reconnection symmetric and identical to the (proven-stable) first session,
	 * at the cost of one classical frame before post-quantum re-engages. Security
	 * is unaffected: the root key is already post-quantum (hybrid ML-KEM at
	 * pairing), so even the sentinel frame is protected by post-quantum-derived
	 * keys.
	 *
	 * @param contactId the local contact id.
	 * @param rootKey the contact's stored handshake root key.
	 * @param alice our role tiebreaker, fixed at pairing.
	 * @param persistedMode3Full retained for source/compatibility; not resumed
	 * (see above).
	 */
	public ZwfDuplexConnection resume(int contactId, SecretKey rootKey,
			boolean alice, Mode3FullState persistedMode3Full, InputStream in,
			OutputStream out) {
		ZwfSession session = sessionFactory.deriveSession(rootKey, alice);
		return new ZwfDuplexConnection(contactId, session, counter, crypto,
				ratchet, mode3FullRatchet, cipherFactory, in, out);
	}
}
