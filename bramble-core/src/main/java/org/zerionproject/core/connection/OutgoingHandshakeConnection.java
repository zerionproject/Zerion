package org.zerionproject.core.connection;

import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.Contact;
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
class OutgoingHandshakeConnection extends HandshakeConnection
		implements Runnable {

	OutgoingHandshakeConnection(KeyManager keyManager,
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
		StreamContext ctxOut =
				allocateStreamContext(pendingContactId, transportId);
		if (ctxOut == null) {
			onError();
			return;
		}
		StreamWriter out;
		try {
			out = streamWriterFactory.createStreamWriter(
					writer.getOutputStream(), ctxOut);
			out.getOutputStream().flush();
		} catch (IOException e) {
			onError();
			return;
		}
		StreamContext ctxIn = recogniseTag(reader, transportId);
		if (ctxIn == null) {
			onError();
			return;
		}
		PendingContactId inPendingContactId = ctxIn.getPendingContactId();
		if (inPendingContactId == null || !inPendingContactId.equals(pendingContactId)) {
			onError();
			return;
		}
		if (!connectionRegistry.registerConnection(pendingContactId)) {
			onError();
			return;
		}
		try {
			InputStream in = streamReaderFactory.createStreamReader(
					reader.getInputStream(), ctxIn);
			HandshakeResult result =
					handshakeManager.handshake(pendingContactId, in, out);
			Contact contact = contactExchangeManager.exchangeContacts(
					pendingContactId, connection, result.getMasterKey(),
					result.isAlice(), true, classical,
					result.getOurStaticHybridPub(),
					result.getTheirStaticHybridPub(),
					result.getOurEphX25519(),
					result.getTheirEphX25519());
			cancelTimeout();
			connectionRegistry.unregisterConnection(pendingContactId, true);
			connectionManager.manageOutgoingConnection(contact.getId(),
					transportId, connection);
		} catch (IOException | DbException e) {
			onError();
			connectionRegistry.unregisterConnection(pendingContactId, false);
		}
	}

	private void onError() {
		onError(true);
	}
}
