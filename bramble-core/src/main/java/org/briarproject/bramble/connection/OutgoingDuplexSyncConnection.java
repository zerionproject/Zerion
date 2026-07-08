package org.briarproject.bramble.connection;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportPropertyManager;
import org.briarproject.bramble.api.sync.Priority;
import org.briarproject.bramble.api.sync.PriorityHandler;
import org.briarproject.bramble.api.sync.SyncSession;
import org.briarproject.bramble.api.sync.SyncSessionFactory;
import org.briarproject.bramble.api.transport.KeyManager;
import org.briarproject.bramble.api.transport.StreamContext;
import org.briarproject.bramble.api.transport.StreamReaderFactory;
import org.briarproject.bramble.api.transport.StreamWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.Executor;
import static org.briarproject.bramble.api.sync.SyncConstants.PRIORITY_NONCE_BYTES;
@NotNullByDefault
class OutgoingDuplexSyncConnection extends DuplexSyncConnection
		implements Runnable {

	private final SecureRandom secureRandom;
	private final ContactId contactId;

	OutgoingDuplexSyncConnection(KeyManager keyManager,
			ConnectionRegistry connectionRegistry,
			StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory,
			SyncSessionFactory syncSessionFactory,
			TransportPropertyManager transportPropertyManager,
			org.briarproject.bramble.api.system.TaskScheduler scheduler,
			Executor ioExecutor, SecureRandom secureRandom, ContactId contactId,
			TransportId transportId, DuplexTransportConnection connection) {
		super(keyManager, connectionRegistry, streamReaderFactory,
				streamWriterFactory, syncSessionFactory,
				transportPropertyManager, scheduler, ioExecutor,
				transportId, connection);
		this.secureRandom = secureRandom;
		this.contactId = contactId;
	}

	@Override
	public void run() {
		StreamContext ctx = allocateStreamContext(contactId, transportId);
		if (ctx == null) {
			onWriteError();
			return;
		}
		if (ctx.isHandshakeMode()) {
			onWriteError();
			return;
		}
		Priority priority = generatePriority();
		ioExecutor.execute(() -> runIncomingSession(priority));
		try {
			SyncSession out =
					createDuplexOutgoingSession(ctx, writer, priority);
			setOutgoingSession(out);
			out.run();
			writer.dispose(false);
		} catch (IOException e) {
			onWriteError();
		}
	}

	private void runIncomingSession(Priority priority) {
		StreamContext ctx = recogniseTag(reader, transportId);
		if (ctx == null) {
			onReadError();
			return;
		}
		ContactId inContactId = ctx.getContactId();
		if (inContactId == null) {
			onReadError();
			return;
		}
		if (!contactId.equals(inContactId)) {
			onReadError();
			return;
		}
		if (ctx.isHandshakeMode()) {
			onReadError();
			return;
		}
		connectionRegistry.registerOutgoingConnection(contactId, transportId,
				this, priority);
		try {
			transportPropertyManager.addRemotePropertiesFromConnection(
					contactId, transportId, remote);
			PriorityHandler handler = p -> {};
			createIncomingSession(ctx, reader, handler).run();
			reader.dispose(false, true);
			interruptOutgoingSession();
			connectionRegistry.unregisterConnection(contactId, transportId,
					this, false, false);
		} catch (DbException | IOException e) {
			onReadError();
			connectionRegistry.unregisterConnection(contactId, transportId,
					this, false, true);
		} finally {
			stopCloseWatchdog();
		}
	}

	private void onReadError() {
		onReadError(true);
	}

	private Priority generatePriority() {
		byte[] nonce = new byte[PRIORITY_NONCE_BYTES];
		secureRandom.nextBytes(nonce);
		return new Priority(nonce);
	}
}
