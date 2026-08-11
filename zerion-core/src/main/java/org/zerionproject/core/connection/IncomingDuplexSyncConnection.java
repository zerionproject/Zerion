package org.zerionproject.core.connection;

import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.PriorityHandler;
import org.zerionproject.core.api.sync.SyncSession;
import org.zerionproject.core.api.sync.SyncSessionFactory;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.api.transport.StreamReaderFactory;
import org.zerionproject.core.api.transport.StreamWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.concurrent.Executor;
@NotNullByDefault
class IncomingDuplexSyncConnection extends DuplexSyncConnection
		implements Runnable {

	IncomingDuplexSyncConnection(KeyManager keyManager,
			ConnectionRegistry connectionRegistry,
			StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory,
			SyncSessionFactory syncSessionFactory,
			TransportPropertyManager transportPropertyManager,
			org.zerionproject.core.api.system.TaskScheduler scheduler,
			Executor ioExecutor, TransportId transportId,
			DuplexTransportConnection connection) {
		super(keyManager, connectionRegistry, streamReaderFactory,
				streamWriterFactory, syncSessionFactory,
				transportPropertyManager, scheduler, ioExecutor,
				transportId, connection);
	}

	@Override
	public void run() {
		StreamContext ctx = recogniseTag(reader, transportId);
		if (ctx == null) {
			onReadError(false);
			return;
		}
		ContactId contactId = ctx.getContactId();
		if (contactId == null) {
			onReadError(true);
			return;
		}
		if (ctx.isHandshakeMode()) {
			onReadError(true);
			return;
		}
		connectionRegistry.registerIncomingConnection(contactId, transportId,
				this);
		ioExecutor.execute(() -> runOutgoingSession(contactId));
		try {
			transportPropertyManager.addRemotePropertiesFromConnection(
					contactId, transportId, remote);
			PriorityHandler handler = p -> connectionRegistry.setPriority(
					contactId, transportId, this, p);
			createIncomingSession(ctx, reader, handler).run();
			reader.dispose(false, true);
			interruptOutgoingSession();
			connectionRegistry.unregisterConnection(contactId, transportId,
					this, true, false);
		} catch (DbException | IOException e) {
			onReadError(true);
			connectionRegistry.unregisterConnection(contactId, transportId,
					this, true, true);
		} finally {
			stopCloseWatchdog();
		}
	}

	private void runOutgoingSession(ContactId contactId) {
		StreamContext ctx = allocateStreamContext(contactId, transportId);
		if (ctx == null) {
			onWriteError();
			return;
		}
		try {
			SyncSession out = createDuplexOutgoingSession(ctx, writer, null);
			setOutgoingSession(out);
			out.run();
			writer.dispose(false);
		} catch (IOException e) {
			onWriteError();
		}
	}
}

