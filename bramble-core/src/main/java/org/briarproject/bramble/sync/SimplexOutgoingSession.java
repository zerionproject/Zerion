package org.briarproject.bramble.sync;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.event.ContactRemovedEvent;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.event.LifecycleEvent;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.event.TransportInactiveEvent;
import org.briarproject.bramble.api.record.Record;
import org.briarproject.bramble.api.sync.Ack;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.SyncConstants;
import org.briarproject.bramble.api.sync.SyncRecordWriter;
import org.briarproject.bramble.api.sync.SyncSession;
import org.briarproject.bramble.api.sync.Versions;
import org.briarproject.bramble.api.sync.event.CloseSyncConnectionsEvent;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.Collection;
import javax.annotation.concurrent.ThreadSafe;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.STOPPING;
import static org.briarproject.bramble.api.record.Record.RECORD_HEADER_BYTES;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_IDS;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_LENGTH;
import static org.briarproject.bramble.api.sync.SyncConstants.SUPPORTED_VERSIONS;

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
