package org.briarproject.bramble.connection;

import org.briarproject.bramble.api.Cancellable;
import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.connection.InterruptibleConnection;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.system.TaskScheduler;
import org.briarproject.bramble.api.plugin.event.TransportInactiveEvent;
import org.briarproject.bramble.api.sync.event.CloseSyncConnectionsEvent;
import org.briarproject.bramble.api.plugin.TransportConnectionReader;
import org.briarproject.bramble.api.plugin.TransportConnectionWriter;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.properties.TransportPropertyManager;
import org.briarproject.bramble.api.sync.Priority;
import org.briarproject.bramble.api.sync.SyncSession;
import org.briarproject.bramble.api.sync.SyncSessionFactory;
import org.briarproject.bramble.api.transport.KeyManager;
import org.briarproject.bramble.api.transport.StreamContext;
import org.briarproject.bramble.api.transport.StreamReaderFactory;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.bramble.api.transport.StreamWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@NotNullByDefault
abstract class DuplexSyncConnection extends SyncConnection
		implements InterruptibleConnection, EventListener {

	final Executor ioExecutor;
	final TransportId transportId;
	final TransportConnectionReader reader;
	final TransportConnectionWriter writer;
	final TransportProperties remote;
	private final EventBus eventBus;
	private final TaskScheduler scheduler;

	private static final long MAX_CONNECTION_LIFETIME_MS = 30L * 60L * 1000L;

	private final Object watchdogLock = new Object();
	@GuardedBy("watchdogLock")
	@Nullable
	private Cancellable closeWatchdog = null;
	private volatile boolean fullyClosed = false;

	private final Object interruptLock = new Object();

	@GuardedBy("interruptLock")
	@Nullable
	private SyncSession outgoingSession = null;
	@GuardedBy("interruptLock")
	private boolean interruptWaiting = false;

	@Override
	public void interruptOutgoingSession() {
		SyncSession out = null;
		synchronized (interruptLock) {
			if (outgoingSession == null) interruptWaiting = true;
			else out = outgoingSession;
		}
		if (out != null) out.interrupt();
		// Fix B: once the outgoing session is interrupted the connection is
		// being torn down, so the reader must not outlive it indefinitely.
		// If the peer keeps feeding the reader (e.g. cover traffic) so the
		// read timeout never fires, force a full close shortly after.
		armCloseWatchdog(2L * writer.getMaxIdleTime());
	}

	void setOutgoingSession(SyncSession outgoingSession) {
		boolean interruptWasWaiting = false;
		synchronized (interruptLock) {
			this.outgoingSession = outgoingSession;
			if (interruptWaiting) {
				interruptWasWaiting = true;
				interruptWaiting = false;
			}
		}
		if (interruptWasWaiting) outgoingSession.interrupt();
	}

	DuplexSyncConnection(KeyManager keyManager,
			ConnectionRegistry connectionRegistry,
			StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory,
			SyncSessionFactory syncSessionFactory,
			TransportPropertyManager transportPropertyManager,
			EventBus eventBus, TaskScheduler scheduler, Executor ioExecutor,
			TransportId transportId,
			DuplexTransportConnection connection) {
		super(keyManager, connectionRegistry, streamReaderFactory,
				streamWriterFactory, syncSessionFactory,
				transportPropertyManager);
		this.eventBus = eventBus;
		this.scheduler = scheduler;
		this.ioExecutor = ioExecutor;
		this.transportId = transportId;
		reader = connection.getReader();
		writer = connection.getWriter();
		remote = connection.getRemoteProperties();
	}

	void startListeningForClose() {
		eventBus.addListener(this);
		// Fix D: a connection can never live long enough to become a
		// permanent zombie. If it is still open after the maximum lifetime,
		// force a full close so the poller re-dials a fresh connection.
		armCloseWatchdog(MAX_CONNECTION_LIFETIME_MS);
	}

	void stopListeningForClose() {
		fullyClosed = true;
		eventBus.removeListener(this);
		cancelCloseWatchdog();
	}

	private void armCloseWatchdog(long delayMs) {
		if (fullyClosed) return;
		Cancellable c = scheduler.schedule(() -> {
			if (!fullyClosed) onWriteError();
		}, ioExecutor, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
		Cancellable old;
		synchronized (watchdogLock) {
			old = closeWatchdog;
			closeWatchdog = c;
		}
		if (old != null) old.cancel();
		if (fullyClosed) cancelCloseWatchdog();
	}

	private void cancelCloseWatchdog() {
		Cancellable c;
		synchronized (watchdogLock) {
			c = closeWatchdog;
			closeWatchdog = null;
		}
		if (c != null) c.cancel();
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof TransportInactiveEvent) {
			TransportInactiveEvent t = (TransportInactiveEvent) e;
			if (t.getTransportId().equals(transportId)) onWriteError();
		} else if (e instanceof CloseSyncConnectionsEvent) {
			CloseSyncConnectionsEvent c = (CloseSyncConnectionsEvent) e;
			if (c.getTransportId().equals(transportId)) onWriteError();
		}
	}

	void onReadError(boolean recognised) {
		fullyClosed = true;
		cancelCloseWatchdog();
		disposeOnError(reader, recognised);
		disposeOnError(writer);
		interruptOutgoingSession();
	}

	void onWriteError() {
		fullyClosed = true;
		cancelCloseWatchdog();
		disposeOnError(reader, true);
		disposeOnError(writer);
	}

	SyncSession createDuplexOutgoingSession(StreamContext ctx,
			TransportConnectionWriter w, @Nullable Priority priority)
			throws IOException {
		StreamWriter streamWriter = streamWriterFactory.createStreamWriter(
				w.getOutputStream(), ctx);
		ContactId c = requireNonNull(ctx.getContactId());
		return syncSessionFactory.createDuplexOutgoingSession(c,
				ctx.getTransportId(), w.getMaxLatency(), w.getMaxIdleTime(),
				streamWriter, priority, ctx.isClassical());
	}
}
