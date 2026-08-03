package org.zerionproject.core.connection;

import org.zerionproject.core.api.connection.ConnectionManager.TagController;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.PriorityHandler;
import org.zerionproject.core.api.sync.SyncSessionFactory;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.api.transport.StreamReaderFactory;
import org.zerionproject.core.api.transport.StreamWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;
@NotNullByDefault
class IncomingSimplexSyncConnection extends SyncConnection implements Runnable {

	private final TransportId transportId;
	private final TransportConnectionReader reader;
	@Nullable
	private final TagController tagController;

	IncomingSimplexSyncConnection(KeyManager keyManager,
			ConnectionRegistry connectionRegistry,
			StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory,
			SyncSessionFactory syncSessionFactory,
			TransportPropertyManager transportPropertyManager,
			TransportId transportId,
			TransportConnectionReader reader,
			@Nullable TagController tagController) {
		super(keyManager, connectionRegistry, streamReaderFactory,
				streamWriterFactory, syncSessionFactory,
				transportPropertyManager);
		this.transportId = transportId;
		this.reader = reader;
		this.tagController = tagController;
	}

	@Override
	public void run() {
		byte[] tag;
		StreamContext ctx;
		try {
			tag = readTag(reader.getInputStream());
			if (tagController == null) {
				ctx = keyManager.getStreamContext(transportId, tag);
			} else {
				ctx = keyManager.getStreamContextOnly(transportId, tag);
			}
		} catch (IOException | DbException e) {
			onError();
			return;
		}
		if (ctx == null) {
			onError();
			return;
		}
		ContactId contactId = ctx.getContactId();
		if (contactId == null) {
			onError(tag);
			return;
		}
		if (ctx.isHandshakeMode()) {
			onError(tag);
			return;
		}
		try {
			PriorityHandler handler = p -> {};
			createIncomingSession(ctx, reader, handler).run();
			markTagAsRecognisedIfRequired(false, tag);
			reader.dispose(false, true);
		} catch (IOException e) {
			onError(tag);
		}
	}

	private void onError() {
		disposeOnError(reader, false);
	}

	private void onError(byte[] tag) {
		markTagAsRecognisedIfRequired(true, tag);
		disposeOnError(reader, true);
	}

	private void markTagAsRecognisedIfRequired(boolean exception, byte[] tag) {
		if (tagController != null &&
				tagController.shouldMarkTagAsRecognised(exception)) {
			try {
				keyManager.markTagAsRecognised(transportId, tag);
			} catch (DbException e) {
			}
		}
	}
}

