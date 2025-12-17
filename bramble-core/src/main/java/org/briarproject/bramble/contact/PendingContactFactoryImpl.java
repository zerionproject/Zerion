package org.briarproject.bramble.contact;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UnsupportedVersionException;
import org.briarproject.bramble.api.contact.PendingContact;
import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyParser;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.util.Base32;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.regex.Matcher;

import javax.inject.Inject;

import static java.lang.System.arraycopy;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.FORMAT_VERSION;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.FORMAT_VERSION_CLASSICAL;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.FORMAT_VERSION_HYBRID;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.ID_LABEL;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.LINK_REGEX;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.RAW_LINK_BYTES;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.RAW_LINK_BYTES_CLASSICAL;
import static org.briarproject.bramble.api.crypto.CryptoConstants.KEY_TYPE_AGREEMENT;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_AGREEMENT;

/**
 * Factory for creating and parsing handshake links.
 * <p>
 * Supports two format versions:
 * <ul>
 *   <li><b>Version 0 (Classical)</b>: Direct X25519 public key in QR code.
 *       Compatible with standard Briar clients.</li>
 *   <li><b>Version 1 (Hybrid PQ)</b>: BLAKE2b-256 commitment to hybrid
 *       X25519+ML-KEM-768 public key. The full hybrid key (1,216 bytes) is
 *       exchanged over the Tor connection after QR scan.</li>
 * </ul>
 */
@NotNullByDefault
class PendingContactFactoryImpl implements PendingContactFactory {

	private final CryptoComponent crypto;
	private final Clock clock;

	@Inject
	PendingContactFactoryImpl(CryptoComponent crypto, Clock clock) {
		this.crypto = crypto;
		this.clock = clock;
	}

	@Override
	public PendingContact createPendingContact(String link, String alias)
			throws FormatException {
		ParsedLink parsed = parseHandshakeLink(link);

		// For hybrid format, the parsed "key" is actually a commitment
		// The full hybrid key will be received over Tor and verified against this
		PublicKey keyOrCommitment = parsed.publicKeyOrCommitment;
		int version = parsed.version;

		PendingContactId id = getPendingContactId(keyOrCommitment, version);
		long timestamp = clock.currentTimeMillis();
		return new PendingContact(id, keyOrCommitment, alias, timestamp, version);
	}

	@Override
	public String createHandshakeLink(PublicKey k) {
		String keyType = k.getKeyType();

		if (keyType.equals(KEY_TYPE_HYBRID_AGREEMENT)) {
			// Hybrid PQ format: create commitment to full key
			return createHybridHandshakeLink(k);
		} else if (keyType.equals(KEY_TYPE_AGREEMENT)) {
			// Classical format: embed key directly
			return createClassicalHandshakeLink(k);
		} else {
			throw new IllegalArgumentException("Unsupported key type: " + keyType);
		}
	}

	/**
	 * Creates a classical handshake link with the X25519 public key embedded.
	 */
	private String createClassicalHandshakeLink(PublicKey k) {
		byte[] encoded = k.getEncoded();
		if (encoded.length != RAW_LINK_BYTES_CLASSICAL - 1) {
			throw new IllegalArgumentException(
					"Invalid classical key length: " + encoded.length);
		}
		byte[] raw = new byte[RAW_LINK_BYTES_CLASSICAL];
		raw[0] = FORMAT_VERSION_CLASSICAL;
		arraycopy(encoded, 0, raw, 1, encoded.length);
		return "zerion://" + Base32.encode(raw).toLowerCase(Locale.US);
	}

	private String createHybridHandshakeLink(PublicKey hybridKey) {
		byte[] commitment = crypto.hash(HYBRID_COMMITMENT_LABEL, hybridKey.getEncoded());
		if (commitment.length != 32) {
			throw new AssertionError("Unexpected commitment length");
		}
		byte[] raw = new byte[RAW_LINK_BYTES];
		raw[0] = FORMAT_VERSION_HYBRID;
		arraycopy(commitment, 0, raw, 1, commitment.length);
		return "zerion://" + Base32.encode(raw).toLowerCase(Locale.US);
	}

	/**
	 * Verifies that a received hybrid public key matches the commitment
	 * from the handshake link.
	 *
	 * @param receivedKey The full hybrid public key received over Tor
	 * @param commitment The commitment from the link
	 * @return true if the key matches the commitment
	 */
	public boolean verifyHybridKeyCommitment(PublicKey receivedKey,
			byte[] commitment) {
		byte[] expectedCommitment = crypto.hash(HYBRID_COMMITMENT_LABEL,
				receivedKey.getEncoded());
		return constantTimeEquals(expectedCommitment, commitment);
	}

	private ParsedLink parseHandshakeLink(String link) throws FormatException {
		Matcher matcher = LINK_REGEX.matcher(link);
		if (!matcher.find()) throw new FormatException();

		// Discard 'zerion://' and anything before or after the link
		link = matcher.group(2);
		byte[] raw = Base32.decode(link, false);

		if (raw.length < 1) throw new FormatException();
		byte version = raw[0];

		// Check version support
		if (version > FORMAT_VERSION) {
			// Newer version than we support
			throw new UnsupportedVersionException(false);
		}

		byte[] publicKeyBytes = new byte[raw.length - 1];
		arraycopy(raw, 1, publicKeyBytes, 0, publicKeyBytes.length);

		try {
			if (version == FORMAT_VERSION_CLASSICAL) {
				// Classical format: parse as X25519 key
				if (raw.length != RAW_LINK_BYTES_CLASSICAL) {
					throw new FormatException();
				}
				KeyParser parser = crypto.getAgreementKeyParser();
				PublicKey key = parser.parsePublicKey(publicKeyBytes);
				return new ParsedLink(version, key);
			} else if (version == FORMAT_VERSION_HYBRID) {
				// Hybrid format: publicKeyBytes is actually a commitment (hash)
				// We create a placeholder key to store the commitment
				// The real key will come over Tor and be verified
				if (raw.length != RAW_LINK_BYTES) {
					throw new FormatException();
				}
				// Store commitment as a pseudo-key for now
				// The IdentityManager will handle the full key exchange
				return new ParsedLink(version, new CommitmentKey(publicKeyBytes));
			} else {
				throw new UnsupportedVersionException(true);
			}
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
	}

	private PendingContactId getPendingContactId(PublicKey publicKey, int version) {
		// For hybrid, the "key" is actually a commitment - hash it for ID
		byte[] hash = crypto.hash(ID_LABEL, publicKey.getEncoded());
		return new PendingContactId(hash);
	}

	/**
	 * Constant-time comparison of two byte arrays.
	 */
	private boolean constantTimeEquals(byte[] a, byte[] b) {
		if (a.length != b.length) return false;
		int result = 0;
		for (int i = 0; i < a.length; i++) {
			result |= a[i] ^ b[i];
		}
		return result == 0;
	}

	/**
	 * Result of parsing a handshake link.
	 */
	private static class ParsedLink {
		final int version;
		final PublicKey publicKeyOrCommitment;

		ParsedLink(int version, PublicKey publicKeyOrCommitment) {
			this.version = version;
			this.publicKeyOrCommitment = publicKeyOrCommitment;
		}
	}

	/**
	 * A pseudo-key that holds a commitment hash for hybrid format links.
	 * The real hybrid key will be received and verified separately.
	 */
	private static class CommitmentKey implements PublicKey {
		private static final String KEY_TYPE = "Hybrid-Commitment";
		private final byte[] commitment;

		CommitmentKey(byte[] commitment) {
			this.commitment = commitment;
		}

		@Override
		public String getKeyType() {
			return KEY_TYPE;
		}

		@Override
		public byte[] getEncoded() {
			return commitment;
		}
	}
}
