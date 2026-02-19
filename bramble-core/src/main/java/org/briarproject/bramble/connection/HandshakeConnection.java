package org.briarproject.bramble.connection;

import org.briarproject.bramble.api.connection.ConnectionManager;
import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactExchangeManager;
import org.briarproject.bramble.api.contact.HandshakeManager;
import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.plugin.TransportConnectionReader;
import org.briarproject.bramble.api.plugin.TransportConnectionWriter;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.transport.KeyManager;
import org.briarproject.bramble.api.transport.StreamContext;
import org.briarproject.bramble.api.transport.StreamReaderFactory;
import org.briarproject.bramble.api.transport.StreamWriterFactory;
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

	// 2 minutes: generous for Tor latency, prevents indefinite blocking
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
			// Keys may not be loaded yet — wait briefly and retry once
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

	/**
	 * Starts a watchdog thread that closes the connection if the handshake
	 * does not complete within HANDSHAKE_TIMEOUT_MS. Call
	 * {@link #cancelTimeout()} when handshake succeeds.
	 */
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
