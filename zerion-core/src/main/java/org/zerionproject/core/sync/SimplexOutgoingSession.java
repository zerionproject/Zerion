package org.zerionproject.core.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.event.LifecycleEvent;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.event.TransportInactiveEvent;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.SyncConstants;
import org.zerionproject.core.api.sync.SyncRecordWriter;
import org.zerionproject.core.api.sync.SyncSession;
import org.zerionproject.core.api.sync.Versions;
import org.zerionproject.core.api.sync.event.CloseSyncConnectionsEvent;
import org.zerionproject.core.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.Collection;
import javax.annotation.concurrent.ThreadSafe;
import static org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState.STOPPING;
import static org.zerionproject.core.api.record.Record.RECORD_HEADER_BYTES;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_IDS;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.SUPPORTED_VERSIONS;

@ThreadSafe
@NotNullByDefault
class SimplexOutgoingSession implements SyncSession, EventListener {

	static final int BATCH_CAPACITY =
			(RECORD_HEADER_BYTES + MAX_MESSAGE_LENGTH) * 2;

	protected final DatabaseComponent db;
	protected final EventBus eventBus;
	protected final ContactId contactId;
	protected final TransportId transportId;
	protected final long maxLatency;
	protected final StreamWriter streamWriter;
	protected final SyncRecordWriter recordWriter;

	private volatile boolean interrupted = false;

	SimplexOutgoingSession(DatabaseComponent db,
			EventBus eventBus,
			ContactId contactId,
			TransportId transportId,
			long maxLatency,
			StreamWriter streamWriter,
			SyncRecordWriter recordWriter) {
		this.db = db;
		this.eventBus = eventBus;
		this.contactId = contactId;
		this.transportId = transportId;
		this.maxLatency = maxLatency;
		this.streamWriter = streamWriter;
		this.recordWriter = recordWriter;
	}

	@IoExecutor
	@Override
	public void run() throws IOException {
		eventBus.addListener(this);
		try {
			recordWriter.writeVersions(new Versions(SUPPORTED_VERSIONS));
			try {
				sendAcks();
				sendMessages();
			} catch (DbException e) {
			}
			streamWriter.sendEndOfStream();
		} finally {
			eventBus.removeListener(this);
		}
	}

	@Override
	public void interrupt() {
		interrupted = true;
	}

	boolean isInterrupted() {
		return interrupted;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactRemovedEvent) {
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			if (c.getContactId().equals(contactId)) interrupt();
		} else if (e instanceof LifecycleEvent) {
			LifecycleEvent l = (LifecycleEvent) e;
			if (l.getLifecycleState() == STOPPING) interrupt();
		} else if (e instanceof CloseSyncConnectionsEvent) {
			CloseSyncConnectionsEvent c = (CloseSyncConnectionsEvent) e;
			if (c.getTransportId().equals(transportId)) interrupt();
		} else if (e instanceof TransportInactiveEvent) {
			TransportInactiveEvent t = (TransportInactiveEvent) e;
			if (t.getTransportId().equals(transportId)) interrupt();
		}
	}

	void sendAcks() throws DbException, IOException {
		while (!isInterrupted()) if (!generateAndSendAck()) break;
	}

	private boolean generateAndSendAck() throws DbException, IOException {
		Ack a = db.transactionWithNullableResult(false, txn ->
				db.generateAck(txn, contactId, MAX_MESSAGE_IDS));
		if (a == null) return false;
		recordWriter.writeAck(a);
		return true;
	}

	void sendMessages() throws DbException, IOException {
		while (!isInterrupted()) if (!generateAndSendBatch()) break;
	}

	private boolean generateAndSendBatch() throws DbException, IOException {
		Collection<Message> b = db.transactionWithNullableResult(false, txn ->
				db.generateBatch(txn, contactId, BATCH_CAPACITY, maxLatency));
		if (b == null) return false;
		for (Message m : b) recordWriter.writeMessage(m);
		return true;
	}
}
