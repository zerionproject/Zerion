package org.zerionproject.core.connection;

import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactExchangeManager;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.HandshakeManager;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.OutgoingSessionRecord;
import org.zerionproject.core.api.sync.SyncSessionFactory;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.StreamReaderFactory;
import org.zerionproject.core.api.transport.StreamWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

@ThreadSafe
@NotNullByDefault
class ConnectionManagerImpl implements ConnectionManager {

	private static final int MAX_CONCURRENT_INCOMING_HANDSHAKES = 3;

	private final ConcurrentMap<PendingContactId, Integer>
			incomingHandshakes = new ConcurrentHashMap<>();

	private final Executor ioExecutor;
	private final KeyManager keyManager;
	private final StreamReaderFactory streamReaderFactory;
	private final StreamWriterFactory streamWriterFactory;
	private final SyncSessionFactory syncSessionFactory;
	private final HandshakeManager handshakeManager;
	private final ContactExchangeManager contactExchangeManager;
	private final ConnectionRegistry connectionRegistry;
	private final TransportPropertyManager transportPropertyManager;
	private final SecureRandom secureRandom;
	private final TaskScheduler scheduler;

	@Inject
	ConnectionManagerImpl(@IoExecutor Executor ioExecutor,
			KeyManager keyManager, StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory,
			SyncSessionFactory syncSessionFactory,
			HandshakeManager handshakeManager,
			ContactExchangeManager contactExchangeManager,
			ConnectionRegistry connectionRegistry,
			TransportPropertyManager transportPropertyManager,
			SecureRandom secureRandom, TaskScheduler scheduler) {
		this.ioExecutor = ioExecutor;
		this.keyManager = keyManager;
		this.streamReaderFactory = streamReaderFactory;
		this.streamWriterFactory = streamWriterFactory;
		this.syncSessionFactory = syncSessionFactory;
		this.handshakeManager = handshakeManager;
		this.contactExchangeManager = contactExchangeManager;
		this.connectionRegistry = connectionRegistry;
		this.transportPropertyManager = transportPropertyManager;
		this.secureRandom = secureRandom;
		this.scheduler = scheduler;
	}

	@Override
	public void manageIncomingConnection(TransportId t,
			TransportConnectionReader r) {
		ioExecutor.execute(new IncomingSimplexSyncConnection(keyManager,
				connectionRegistry, streamReaderFactory, streamWriterFactory,
				syncSessionFactory, transportPropertyManager, t, r, null));
	}

	@Override
	public void manageIncomingConnection(TransportId t,
			TransportConnectionReader r, TagController c) {
		ioExecutor.execute(new IncomingSimplexSyncConnection(keyManager,
				connectionRegistry, streamReaderFactory, streamWriterFactory,
				syncSessionFactory, transportPropertyManager, t, r, c));
	}

	@Override
	public void manageIncomingConnection(TransportId t,
			DuplexTransportConnection d) {
		ioExecutor.execute(new IncomingDuplexSyncConnection(keyManager,
				connectionRegistry, streamReaderFactory, streamWriterFactory,
				syncSessionFactory, transportPropertyManager,
				scheduler, ioExecutor, t, d));
	}

	@Override
	public void manageIncomingConnection(PendingContactId p, TransportId t,
			DuplexTransportConnection d, boolean classical) {
		if (!tryAdmitHandshake(p)) {
			disposeQuietly(d);
			return;
		}
		Runnable conn = new IncomingHandshakeConnection(keyManager,
				connectionRegistry, streamReaderFactory, streamWriterFactory,
				handshakeManager, contactExchangeManager, this, p, t, d,
				classical);
		try {
			ioExecutor.execute(() -> {
				try {
					conn.run();
				} finally {
					releaseHandshake(p);
				}
			});
		} catch (RuntimeException e) {
			releaseHandshake(p);
			disposeQuietly(d);
		}
	}

	private boolean tryAdmitHandshake(PendingContactId p) {
		boolean[] admitted = new boolean[1];
		incomingHandshakes.compute(p, (k, v) -> {
			int current = v == null ? 0 : v;
			if (current >= MAX_CONCURRENT_INCOMING_HANDSHAKES) {
				admitted[0] = false;
				return current;
			}
			admitted[0] = true;
			return current + 1;
		});
		return admitted[0];
	}

	private void releaseHandshake(PendingContactId p) {
		incomingHandshakes.compute(p, (k, v) -> {
			if (v == null || v <= 1) return null;
			return v - 1;
		});
	}

	private void disposeQuietly(DuplexTransportConnection d) {
		try {
			d.getReader().dispose(true, false);
		} catch (IOException ignored) {
		}
		try {
			d.getWriter().dispose(true);
		} catch (IOException ignored) {
		}
	}

	@Override
	public void manageOutgoingConnection(ContactId c, TransportId t,
			TransportConnectionWriter w) {
		ioExecutor.execute(new OutgoingSimplexSyncConnection(keyManager,
				connectionRegistry, streamReaderFactory, streamWriterFactory,
				syncSessionFactory, transportPropertyManager, c, t, w, null));
	}

	@Override
	public void manageOutgoingConnection(ContactId c, TransportId t,
			TransportConnectionWriter w, OutgoingSessionRecord sessionRecord) {
		ioExecutor.execute(new OutgoingSimplexSyncConnection(keyManager,
				connectionRegistry, streamReaderFactory, streamWriterFactory,
				syncSessionFactory, transportPropertyManager, c, t, w,
				sessionRecord));
	}

	@Override
	public void manageOutgoingConnection(ContactId c, TransportId t,
			DuplexTransportConnection d) {
		ioExecutor.execute(new OutgoingDuplexSyncConnection(keyManager,
				connectionRegistry, streamReaderFactory, streamWriterFactory,
				syncSessionFactory, transportPropertyManager,
				scheduler, ioExecutor, secureRandom, c, t, d));
	}

	@Override
	public void manageOutgoingConnection(PendingContactId p, TransportId t,
			DuplexTransportConnection d, boolean classical) {
		ioExecutor.execute(new OutgoingHandshakeConnection(keyManager,
				connectionRegistry, streamReaderFactory, streamWriterFactory,
				handshakeManager, contactExchangeManager, this, p, t, d,
				classical));
	}
}
