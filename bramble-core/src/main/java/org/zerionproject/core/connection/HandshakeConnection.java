package org.zerionproject.core.connection;

import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactExchangeManager;
import org.zerionproject.core.api.contact.HandshakeManager;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.api.transport.StreamReaderFactory;
import org.zerionproject.core.api.transport.StreamWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

@NotNullByDefault
abstract class HandshakeConnection extends Connection {

	final HandshakeManager handshakeManager;
	final ContactExchangeManager contactExchangeManager;
	final ConnectionManager connectionManager;
	final PendingContactId pendingContactId;
	final TransportId transportId;
	final DuplexTransportConnection connection;
	final TransportConnectionReader reader;
	final TransportConnectionWriter writer;

	final boolean classical;

	private static final long HANDSHAKE_TIMEOUT_MS = 120_000;
	private final AtomicBoolean handshakeComplete = new AtomicBoolean(false);

	HandshakeConnection(KeyManager keyManager,
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
				streamWriterFactory);
		this.handshakeManager = handshakeManager;
		this.contactExchangeManager = contactExchangeManager;
		this.connectionManager = connectionManager;
		this.pendingContactId = pendingContactId;
		this.transportId = transportId;
		this.connection = connection;
		this.classical = classical;
		reader = connection.getReader();
		writer = connection.getWriter();
	}

	@Nullable
	StreamContext allocateStreamContext(PendingContactId pendingContactId,
			TransportId transportId) {
		try {
			StreamContext ctx =
					keyManager.getStreamContext(pendingContactId, transportId);
			if (ctx != null) return ctx;

			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				return null;
			}
			return keyManager.getStreamContext(pendingContactId, transportId);
		} catch (DbException e) {
			return null;
		}
	}

	void onError(boolean recognised) {
		disposeOnError(reader, recognised);
		disposeOnError(writer);
	}

	void startTimeout() {
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(HANDSHAKE_TIMEOUT_MS);
			} catch (InterruptedException e) {
				return;
			}
			if (!handshakeComplete.get()) {
				onError(true);
			}
		}, "HandshakeTimeout");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	void cancelTimeout() {
		handshakeComplete.set(true);
	}
}
