package org.zerionproject.core.api.contact;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.ContactExistsException;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
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
			boolean classical,
			@Nullable byte[] ourStaticHybridPub,
			@Nullable byte[] theirStaticHybridPub,
			@Nullable byte[] ourEphX25519,
			@Nullable byte[] theirEphX25519)
			throws IOException, DbException;
}
