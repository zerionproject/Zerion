package org.briarproject.bramble.api.contact;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.ContactExistsException;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;

@NotNullByDefault
public interface ContactExchangeManager {


	Contact exchangeContacts(DuplexTransportConnection conn,
			SecretKey masterKey, boolean alice, boolean verified)
			throws IOException, DbException;


	Contact exchangeContacts(PendingContactId p, DuplexTransportConnection conn,
			SecretKey masterKey, boolean alice, boolean verified,
			boolean classical)
			throws IOException, DbException;


	Contact exchangeContacts(PendingContactId p, DuplexTransportConnection conn,
			SecretKey masterKey, boolean alice, boolean verified,
			boolean classical, boolean mode3Capable)
			throws IOException, DbException;

	/**
	 * B.3-aware overload. When {@code B3Constants.B3_PROOF_ENABLED} and
	 * all four byte arrays are non-null, the encoder appends the B.3
	 * proof at slot[4] of the {@code CONTACT_INFO} BDF list and the
	 * decoder verifies it on receive (hard-rejects on
	 * missing/malformed/verify-fail). Falls through to the legacy
	 * 4-slot path if any of the byte arrays are null or the gate is
	 * off — wire-byte-identical with the 7-arg overload in that case.
	 *
	 * @param ourStaticHybridPub   our 1216-byte static hybrid pubkey
	 *                             (X25519(32) ‖ ML-KEM-768(1184))
	 * @param theirStaticHybridPub the peer's 1216-byte static hybrid
	 *                             pubkey
	 * @param ourEphX25519         our 32-byte X25519 ephemeral pubkey
	 *                             from the handshake
	 * @param theirEphX25519       the peer's 32-byte X25519 ephemeral
	 *                             pubkey from the handshake
	 */
	Contact exchangeContacts(PendingContactId p, DuplexTransportConnection conn,
			SecretKey masterKey, boolean alice, boolean verified,
			boolean classical, boolean mode3Capable,
			@Nullable byte[] ourStaticHybridPub,
			@Nullable byte[] theirStaticHybridPub,
			@Nullable byte[] ourEphX25519,
			@Nullable byte[] theirEphX25519)
			throws IOException, DbException;
}
