package org.zerionproject.core.api.contact;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;

@NotNullByDefault
public interface HandshakeManager {

	HandshakeResult handshake(PendingContactId p, InputStream in,
			StreamWriter out) throws DbException, IOException;

	class HandshakeResult {

		private final SecretKey exchangeKey;
		private final SecretKey rootKey;
		private final boolean keySeparated;
		private final boolean alice;
		private final boolean mode3Capable;

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
			this(masterKey, masterKey, false, alice, mode3Capable,
					ourStaticHybridPub, theirStaticHybridPub, ourEphX25519,
					theirEphX25519);
		}

		public HandshakeResult(SecretKey exchangeKey, SecretKey rootKey,
				boolean keySeparated, boolean alice, boolean mode3Capable,
				@Nullable byte[] ourStaticHybridPub,
				@Nullable byte[] theirStaticHybridPub,
				@Nullable byte[] ourEphX25519,
				@Nullable byte[] theirEphX25519) {
			this.exchangeKey = exchangeKey;
			this.rootKey = rootKey;
			this.keySeparated = keySeparated;
			this.alice = alice;
			this.mode3Capable = mode3Capable;
			this.ourStaticHybridPub = ourStaticHybridPub;
			this.theirStaticHybridPub = theirStaticHybridPub;
			this.ourEphX25519 = ourEphX25519;
			this.theirEphX25519 = theirEphX25519;
		}

		public SecretKey getExchangeKey() {
			return exchangeKey;
		}

		public SecretKey getRootKey() {
			return rootKey;
		}

		public boolean isKeySeparated() {
			return keySeparated;
		}

		public boolean isAlice() {
			return alice;
		}

		public boolean isMode3Capable() {
			return mode3Capable;
		}

		@Nullable
		public byte[] getOurStaticHybridPub() {
			return ourStaticHybridPub;
		}

		@Nullable
		public byte[] getTheirStaticHybridPub() {
			return theirStaticHybridPub;
		}

		@Nullable
		public byte[] getOurEphX25519() {
			return ourEphX25519;
		}

		@Nullable
		public byte[] getTheirEphX25519() {
			return theirEphX25519;
		}
	}
}
