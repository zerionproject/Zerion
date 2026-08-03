package org.zerionproject.core.connection;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.connection.InterruptibleConnection;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.Priority;
import org.zerionproject.core.api.sync.SyncSession;
import org.zerionproject.core.api.sync.SyncSessionFactory;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.api.transport.StreamReaderFactory;
import org.zerionproject.core.api.transport.StreamWriter;
import org.zerionproject.core.api.transport.StreamWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@NotNullByDefault
abstract class DuplexSyncConnection extends SyncConnection
		implements InterruptibleConnection {

	final Executor ioExecutor;
	final TransportId transportId;
	final TransportConnectionReader reader;
	final TransportConnectionWriter writer;
	final TransportProperties remote;
	private final TaskScheduler scheduler;

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
			TaskScheduler scheduler, Executor ioExecutor,
			TransportId transportId,
			DuplexTransportConnection connection) {
		super(keyManager, connectionRegistry, streamReaderFactory,
				streamWriterFactory, syncSessionFactory,
				transportPropertyManager);
		this.scheduler = scheduler;
		this.ioExecutor = ioExecutor;
		this.transportId = transportId;
		reader = connection.getReader();
		writer = connection.getWriter();
		remote = connection.getRemoteProperties();
	}

	void stopCloseWatchdog() {
		fullyClosed = true;
		cancelCloseWatchdog();
	}

	@Override
	public void forceClose() {
		forceCloseAsync();
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

	private void forceCloseAsync() {
		if (fullyClosed) return;
		ioExecutor.execute(this::onWriteError);
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
