package org.zerionproject.core.connection;

import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactExchangeManager;
import org.zerionproject.core.api.contact.HandshakeManager;
import org.zerionproject.core.api.contact.HandshakeManager.HandshakeResult;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.api.transport.StreamReaderFactory;
import org.zerionproject.core.api.transport.StreamWriter;
import org.zerionproject.core.api.transport.StreamWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

@NotNullByDefault
class IncomingHandshakeConnection extends HandshakeConnection
		implements Runnable {

	IncomingHandshakeConnection(KeyManager keyManager,
			ConnectionRegistry connectionRegistry,
			StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory,
			HandshakeManager handshakeManager,
			ContactExchangeManager contactExchangeManager,
			ConnectionManager connectionManager,
			PendingContactId pendingContactId,
			TransportId transportId, DuplexTransportConnection connection,
			boolean classical) {
		super(keyManager, connectionRegistry, streamReaderFactory,
				streamWriterFactory, handshakeManager, contactExchangeManager,
				connectionManager, pendingContactId, transportId, connection,
				classical);
	}

	@Override
	public void run() {
		startTimeout();
		StreamContext ctxIn = recogniseTag(reader, transportId);
		if (ctxIn == null) {
			onError(false);
			return;
		}
		PendingContactId inPendingContactId = ctxIn.getPendingContactId();
		if (inPendingContactId == null) {
			onError(true);
			return;
		}
		StreamContext ctxOut =
				allocateStreamContext(pendingContactId, transportId);
		if (ctxOut == null) {
			onError(true);
			return;
		}
		if (!connectionRegistry.registerConnection(pendingContactId)) {
			onError(true);
			return;
		}
		try {
			InputStream in = streamReaderFactory.createStreamReader(
					reader.getInputStream(), ctxIn);
			StreamWriter out = streamWriterFactory.createStreamWriter(
					writer.getOutputStream(), ctxOut);
			out.getOutputStream().flush();
			HandshakeResult result =
					handshakeManager.handshake(pendingContactId, in, out);
			contactExchangeManager.exchangeContacts(pendingContactId,
					connection, result.getMasterKey(), result.isAlice(), true,
					classical,
					result.getOurStaticHybridPub(),
					result.getTheirStaticHybridPub(),
					result.getOurEphX25519(),
					result.getTheirEphX25519());
			cancelTimeout();
			connectionRegistry.unregisterConnection(pendingContactId, true);
			connectionManager.manageIncomingConnection(transportId, connection);
		} catch (IOException | DbException e) {
			onError(true);
			connectionRegistry.unregisterConnection(pendingContactId, false);
		}
	}
}
