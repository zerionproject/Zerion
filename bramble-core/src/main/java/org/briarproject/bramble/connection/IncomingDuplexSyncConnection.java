package org.briarproject.bramble.connection;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportPropertyManager;
import org.briarproject.bramble.api.sync.PriorityHandler;
import org.briarproject.bramble.api.sync.SyncSession;
import org.briarproject.bramble.api.sync.SyncSessionFactory;
import org.briarproject.bramble.api.transport.KeyManager;
import org.briarproject.bramble.api.transport.StreamContext;
import org.briarproject.bramble.api.transport.StreamReaderFactory;
import org.briarproject.bramble.api.transport.StreamWriterFactory;
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
			org.briarproject.bramble.api.event.EventBus eventBus,
			org.briarproject.bramble.api.system.TaskScheduler scheduler,
			Executor ioExecutor, TransportId transportId,
			DuplexTransportConnection connection) {
		super(keyManager, connectionRegistry, streamReaderFactory,
				streamWriterFactory, syncSessionFactory,
				transportPropertyManager, eventBus, scheduler, ioExecutor,
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
		startListeningForClose();
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
			stopListeningForClose();
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

