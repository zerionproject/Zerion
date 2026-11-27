package org.zerionproject.core.api.keyagreement;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;

import javax.annotation.Nullable;

public class KeyAgreementResult {

	private final SecretKey masterKey;
	@Nullable
	private final SecretKey exchangeKey;
	private final DuplexTransportConnection connection;
	private final TransportId transportId;
	private final boolean alice;

	public KeyAgreementResult(SecretKey masterKey,
			DuplexTransportConnection connection, TransportId transportId,
			boolean alice) {
		this(masterKey, null, connection, transportId, alice);
	}

	public KeyAgreementResult(SecretKey masterKey,
			@Nullable SecretKey exchangeKey,
			DuplexTransportConnection connection, TransportId transportId,
			boolean alice) {
		this.masterKey = masterKey;
		this.exchangeKey = exchangeKey;
		this.connection = connection;
		this.transportId = transportId;
		this.alice = alice;
	}

	public SecretKey getMasterKey() {
		return masterKey;
	}

	public SecretKey getExchangeKey() {
		return exchangeKey != null ? exchangeKey : masterKey;
	}

	public boolean isKeySeparated() {
		return exchangeKey != null;
	}

	public DuplexTransportConnection getConnection() {
		return connection;
	}

	public TransportId getTransportId() {
		return transportId;
	}

	public boolean wasAlice() {
		return alice;
	}
}
