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
			return createHybridHandshakeLink(k);
		} else if (keyType.equals(KEY_TYPE_AGREEMENT)) {
			return createClassicalHandshakeLink(k);
		} else {
			throw new IllegalArgumentException("Unsupported key type: " + keyType);
		}
	}

	
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

	
	public boolean verifyHybridKeyCommitment(PublicKey receivedKey,
			byte[] commitment) {
		byte[] expectedCommitment = crypto.hash(HYBRID_COMMITMENT_LABEL,
				receivedKey.getEncoded());
		return constantTimeEquals(expectedCommitment, commitment);
	}

	private ParsedLink parseHandshakeLink(String link) throws FormatException {
		Matcher matcher = LINK_REGEX.matcher(link);
		if (!matcher.find()) throw new FormatException();
		link = matcher.group(1);
		byte[] raw = Base32.decode(link, false);

		if (raw.length < 1) throw new FormatException();
		byte version = raw[0];
		if (version > FORMAT_VERSION) {
			throw new UnsupportedVersionException(false);
		}

		byte[] publicKeyBytes = new byte[raw.length - 1];
		arraycopy(raw, 1, publicKeyBytes, 0, publicKeyBytes.length);

		try {
			if (version == FORMAT_VERSION_CLASSICAL) {
				if (raw.length != RAW_LINK_BYTES_CLASSICAL) {
					throw new FormatException();
				}
				KeyParser parser = crypto.getAgreementKeyParser();
				PublicKey key = parser.parsePublicKey(publicKeyBytes);
				return new ParsedLink(version, key);
			} else if (version == FORMAT_VERSION_HYBRID) {
				if (raw.length != RAW_LINK_BYTES) {
					throw new FormatException();
				}
				return new ParsedLink(version, new CommitmentKey(publicKeyBytes));
			} else {
				throw new UnsupportedVersionException(true);
			}
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
	}

	private PendingContactId getPendingContactId(PublicKey publicKey, int version) {
		byte[] hash = crypto.hash(ID_LABEL, publicKey.getEncoded());
		return new PendingContactId(hash);
	}

	
	private boolean constantTimeEquals(byte[] a, byte[] b) {
		if (a.length != b.length) return false;
		int result = 0;
		for (int i = 0; i < a.length; i++) {
			result |= a[i] ^ b[i];
		}
		return result == 0;
	}

	
	private static class ParsedLink {
		final int version;
		final PublicKey publicKeyOrCommitment;

		ParsedLink(int version, PublicKey publicKeyOrCommitment) {
			this.version = version;
			this.publicKeyOrCommitment = publicKeyOrCommitment;
		}
	}

	
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
