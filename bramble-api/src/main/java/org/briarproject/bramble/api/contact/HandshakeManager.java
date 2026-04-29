package org.briarproject.bramble.api.contact;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;

@NotNullByDefault
public interface HandshakeManager {


	HandshakeResult handshake(PendingContactId p, InputStream in,
			StreamWriter out) throws DbException, IOException;

	class HandshakeResult {

		private final SecretKey masterKey;
		private final boolean alice;
		private final boolean mode3Capable;

		// B.3 buffered state — populated by performHybridHandshake() so the
		// caller (Outgoing/IncomingHandshakeConnection) can pass it to
		// ContactExchangeManager.exchangeContacts when B3_PROOF_ENABLED.
		// Null on the legacy non-hybrid handshake path. See
		// docs/wire/B3_RECORD_PLACEMENT.md §4 for the receiver state
		// machine.
		@Nullable
		private final byte[] ourStaticHybridPub;
		@Nullable
		private final byte[] theirStaticHybridPub;
		@Nullable
		private final byte[] ourEphX25519;
		@Nullable
		private final byte[] theirEphX25519;

		public HandshakeResult(SecretKey masterKey, boolean alice) {
			this(masterKey, alice, false, null, null, null, null);
		}

		public HandshakeResult(SecretKey masterKey, boolean alice,
				boolean mode3Capable) {
			this(masterKey, alice, mode3Capable, null, null, null, null);
		}

		public HandshakeResult(SecretKey masterKey, boolean alice,
				boolean mode3Capable,
				@Nullable byte[] ourStaticHybridPub,
				@Nullable byte[] theirStaticHybridPub,
				@Nullable byte[] ourEphX25519,
				@Nullable byte[] theirEphX25519) {
			this.masterKey = masterKey;
			this.alice = alice;
			this.mode3Capable = mode3Capable;
			this.ourStaticHybridPub = ourStaticHybridPub;
			this.theirStaticHybridPub = theirStaticHybridPub;
			this.ourEphX25519 = ourEphX25519;
			this.theirEphX25519 = theirEphX25519;
		}

		public SecretKey getMasterKey() {
			return masterKey;
		}

		public boolean isAlice() {
			return alice;
		}

		public boolean isMode3Capable() {
			return mode3Capable;
		}

		/**
		 * Our full 1216-byte static hybrid pubkey. The trailing 1184 bytes
		 * are the ML-KEM-768 portion B.3 signs over when sending
		 * CONTACT_INFO slot[4]. Null on non-hybrid handshakes.
		 */
		@Nullable
		public byte[] getOurStaticHybridPub() {
			return ourStaticHybridPub;
		}

		/**
		 * The peer's full 1216-byte static hybrid pubkey from the handshake's
		 * RECORD_HYBRID_STATIC_KEY exchange. The trailing 1184 bytes are the
		 * ML-KEM-768 portion B.3 verifies against when receiving
		 * CONTACT_INFO slot[4]. Null on non-hybrid handshakes.
		 */
		@Nullable
		public byte[] getTheirStaticHybridPub() {
			return theirStaticHybridPub;
		}

		/** Our X25519 ephemeral pubkey (32 bytes). Used for B.3 sessionId
		 * + role derivation. Null on non-hybrid handshakes. */
		@Nullable
		public byte[] getOurEphX25519() {
			return ourEphX25519;
		}

		/** The peer's X25519 ephemeral pubkey (32 bytes). Used for B.3
		 * sessionId + role derivation. Null on non-hybrid handshakes. */
		@Nullable
		public byte[] getTheirEphX25519() {
			return theirEphX25519;
		}
	}
}
