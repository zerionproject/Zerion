package org.briarproject.bramble.api.contact;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

@NotNullByDefault
public interface HandshakeManager {

	
	HandshakeResult handshake(PendingContactId p, InputStream in,
			StreamWriter out) throws DbException, IOException;

	class HandshakeResult {

		private final SecretKey masterKey;
		private final boolean alice;
		private final boolean mode3Capable;

		public HandshakeResult(SecretKey masterKey, boolean alice) {
			this(masterKey, alice, false);
		}

		public HandshakeResult(SecretKey masterKey, boolean alice,
				boolean mode3Capable) {
			this.masterKey = masterKey;
			this.alice = alice;
			this.mode3Capable = mode3Capable;
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
	}
}
