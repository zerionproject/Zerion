package org.zerionproject.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

@ThreadSafe
@NotNullByDefault
@Singleton
public class ZppConnectionRegistryImpl implements ZppConnectionRegistry {

	private static final long MAX_LATENCY_MS = 30_000L;

	private final DatabaseComponent db;
	private final Executor dbExecutor;
	private final EventBus eventBus;
	private final TaskScheduler taskScheduler;
	private final Clock clock;
	private final ZmmSyncCodec codec;
	private final LocalMessageLog localMessages;

	private final Map<ZppSendScheduler, ZppOutgoingSource> sources =
			new ConcurrentHashMap<>();

	@Inject
	public ZppConnectionRegistryImpl(DatabaseComponent db,
			@DatabaseExecutor Executor dbExecutor, EventBus eventBus,
			TaskScheduler taskScheduler, Clock clock, ZmmSyncCodec codec,
			LocalMessageLog localMessages) {
		this.localMessages = localMessages;
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.eventBus = eventBus;
		this.taskScheduler = taskScheduler;
		this.clock = clock;
		this.codec = codec;
	}

	@Override
	public void onConnectionOpened(int contactId, ZppSendScheduler scheduler,
			int maxRecordBytes) {
		ZppOutgoingSource source = new ZppOutgoingSource(db, dbExecutor, eventBus,
				taskScheduler, clock, codec, new ContactId(contactId),
				MAX_LATENCY_MS, maxRecordBytes, scheduler, localMessages);
		sources.put(scheduler, source);
		source.start();
	}

	@Override
	public void onConnectionClosed(int contactId, ZppSendScheduler scheduler) {
		ZppOutgoingSource source = sources.remove(scheduler);
		if (source != null) source.stop();
	}
}
