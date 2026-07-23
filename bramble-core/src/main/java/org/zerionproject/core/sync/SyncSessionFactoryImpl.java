package org.zerionproject.core.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.OutgoingSessionRecord;
import org.zerionproject.core.api.sync.Priority;
import org.zerionproject.core.api.sync.PriorityHandler;
import org.zerionproject.core.api.sync.SyncRecordReader;
import org.zerionproject.core.api.sync.SyncRecordReaderFactory;
import org.zerionproject.core.api.sync.SyncRecordWriter;
import org.zerionproject.core.api.sync.SyncRecordWriterFactory;
import org.zerionproject.core.api.sync.SyncSession;
import org.zerionproject.core.api.sync.SyncSessionFactory;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

@Immutable
@NotNullByDefault
class SyncSessionFactoryImpl implements SyncSessionFactory {

	private final DatabaseComponent db;
	private final Executor dbExecutor;
	private final EventBus eventBus;
	private final Clock clock;
	private final SyncRecordReaderFactory recordReaderFactory;
	private final SyncRecordWriterFactory recordWriterFactory;

	@Inject
	SyncSessionFactoryImpl(DatabaseComponent db,
			@DatabaseExecutor Executor dbExecutor, EventBus eventBus,
			Clock clock, SyncRecordReaderFactory recordReaderFactory,
			SyncRecordWriterFactory recordWriterFactory) {
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.eventBus = eventBus;
		this.clock = clock;
		this.recordReaderFactory = recordReaderFactory;
		this.recordWriterFactory = recordWriterFactory;
	}

	@Override
	public SyncSession createIncomingSession(ContactId c, InputStream in,
			PriorityHandler handler, boolean classical) {
		SyncRecordReader recordReader =
				recordReaderFactory.createRecordReader(in, classical);
		return new IncomingSession(db, dbExecutor, eventBus, c, recordReader,
				handler);
	}

	@Override
	public SyncSession createSimplexOutgoingSession(ContactId c, TransportId t,
			long maxLatency, boolean eager, StreamWriter streamWriter,
			boolean classical) {
		OutputStream out = streamWriter.getOutputStream();
		SyncRecordWriter recordWriter =
				recordWriterFactory.createRecordWriter(out, classical);
		if (eager) {
			return new EagerSimplexOutgoingSession(db, eventBus, c, t,
					maxLatency, streamWriter, recordWriter);
		} else {
			return new SimplexOutgoingSession(db, eventBus, c, t,
					maxLatency, streamWriter, recordWriter);
		}
	}

	@Override
	public SyncSession createSimplexOutgoingSession(ContactId c, TransportId t,
			long maxLatency, StreamWriter streamWriter,
			OutgoingSessionRecord sessionRecord, boolean classical) {
		OutputStream out = streamWriter.getOutputStream();
		SyncRecordWriter recordWriter =
				recordWriterFactory.createRecordWriter(out, classical);
		return new SimplexOutgoingSession(db, eventBus, c, t,
				maxLatency, streamWriter, recordWriter);
	}

	@Override
	public SyncSession createDuplexOutgoingSession(ContactId c, TransportId t,
			long maxLatency, int maxIdleTime, StreamWriter streamWriter,
			@Nullable Priority priority, boolean classical) {
		OutputStream out = streamWriter.getOutputStream();
		SyncRecordWriter recordWriter =
				recordWriterFactory.createRecordWriter(out, classical);
		return new DuplexOutgoingSession(db, dbExecutor, eventBus, clock, c, t,
				maxLatency, maxIdleTime, streamWriter, recordWriter, priority);
	}
}
