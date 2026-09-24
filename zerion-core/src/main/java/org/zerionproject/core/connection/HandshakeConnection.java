package org.zerionproject.core.connection;

import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactExchangeManager;
import org.zerionproject.core.api.contact.ContactId;
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
import org.zerionproject.transport.ZtpConnectionHandler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

/**
 * A pairing connection. The handshake and the contact exchange run over the
 * classical streams keyed by the pending contact's handshake keys. Once the
 * contact exists on both sides, the same socket carries the first session
 * with the new contact through the established-contact handler, so no
 * traffic to a contact ever runs over the rotation-key streams.
 */
@NotNullByDefault
abstract class HandshakeConnection extends Connection {

	final HandshakeManager handshakeManager;
	final ContactExchangeManager contactExchangeManager;
	final ZtpConnectionHandler connectionHandler;
	final Executor ioExecutor;
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
			ZtpConnectionHandler connectionHandler, Executor ioExecutor,
			PendingContactId pendingContactId,
			TransportId transportId, DuplexTransportConnection connection,
			boolean classical) {
		super(keyManager, connectionRegistry, streamReaderFactory,
				streamWriterFactory);
		this.handshakeManager = handshakeManager;
		this.contactExchangeManager = contactExchangeManager;
		this.connectionHandler = connectionHandler;
		this.ioExecutor = ioExecutor;
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

	/**
	 * Hands the socket the pairing ran on to the established-contact handler,
	 * which resumes the new contact's session from the inputs the exchange
	 * just committed. The session runs on the I/O executor so this pairing
	 * task completes, and the streams are disposed when it ends.
	 */
	void runPairedSession(ContactId contactId, boolean incoming) {
		ioExecutor.execute(() -> {
			boolean exception = false;
			try {
				connectionHandler.handlePaired(transportId, contactId.getInt(),
						incoming, reader.getInputStream(),
						writer.getOutputStream());
			} catch (IOException e) {
				exception = true;
			} finally {
				dispose(exception);
			}
		});
	}

	private void dispose(boolean exception) {
		try {
			reader.dispose(exception, true);
		} catch (IOException ignored) {
		}
		try {
			writer.dispose(exception);
		} catch (IOException ignored) {
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
