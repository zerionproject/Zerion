package org.zerionproject.core.sync;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.event.LifecycleEvent;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Priority;
import org.zerionproject.core.api.sync.PriorityHandler;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.SyncRecordReader;
import org.zerionproject.core.api.sync.SyncSession;
import org.zerionproject.core.api.sync.Versions;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import javax.annotation.concurrent.ThreadSafe;
import static org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState.STOPPING;

@ThreadSafe
@NotNullByDefault
class IncomingSession implements SyncSession, EventListener {

	private final DatabaseComponent db;
	private final Executor dbExecutor;
	private final EventBus eventBus;
	private final ContactId contactId;
	private final SyncRecordReader recordReader;
	private final PriorityHandler priorityHandler;

	private volatile boolean interrupted = false;

	IncomingSession(DatabaseComponent db, Executor dbExecutor,
			EventBus eventBus, ContactId contactId,
			SyncRecordReader recordReader, PriorityHandler priorityHandler) {
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.eventBus = eventBus;
		this.contactId = contactId;
		this.recordReader = recordReader;
		this.priorityHandler = priorityHandler;
	}

	@IoExecutor
	@Override
	public void run() throws IOException {
		eventBus.addListener(this);
		try {
			while (!interrupted) {
				if (recordReader.eof()) {
					return;
				}
				if (recordReader.hasAck()) {
					Ack a = recordReader.readAck();
					dbExecutor.execute(new ReceiveAck(a));
				} else if (recordReader.hasMessage()) {
					Message m = recordReader.readMessage();
					dbExecutor.execute(new ReceiveMessage(m));
				} else if (recordReader.hasOffer()) {
					Offer o = recordReader.readOffer();
					dbExecutor.execute(new ReceiveOffer(o));
				} else if (recordReader.hasRequest()) {
					Request r = recordReader.readRequest();
					dbExecutor.execute(new ReceiveRequest(r));
				} else if (recordReader.hasVersions()) {
					Versions v = recordReader.readVersions();
					dbExecutor.execute(new ReceiveVersions(v));
				} else if (recordReader.hasPriority()) {
					Priority p = recordReader.readPriority();
					priorityHandler.handle(p);
				} else {
					throw new FormatException();
				}
			}
		} finally {
			eventBus.removeListener(this);
		}
	}

	@Override
	public void interrupt() {
		interrupted = true;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactRemovedEvent) {
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			if (c.getContactId().equals(contactId)) interrupt();
		} else if (e instanceof LifecycleEvent) {
			LifecycleEvent l = (LifecycleEvent) e;
			if (l.getLifecycleState() == STOPPING) interrupt();
		}
	}

	private class ReceiveAck implements Runnable {

		private final Ack ack;

		private ReceiveAck(Ack ack) {
			this.ack = ack;
		}

		@DatabaseExecutor
		@Override
		public void run() {
			try {
				db.transaction(false, txn ->
						db.receiveAck(txn, contactId, ack));
			} catch (DbException e) {
				interrupt();
			}
		}
	}

	private class ReceiveMessage implements Runnable {

		private final Message message;

		private ReceiveMessage(Message message) {
			this.message = message;
		}

		@DatabaseExecutor
		@Override
		public void run() {
			try {
				db.transaction(false, txn ->
						db.receiveMessage(txn, contactId, message));
			} catch (DbException e) {
				interrupt();
			}
		}
	}

	private class ReceiveOffer implements Runnable {

		private final Offer offer;

		private ReceiveOffer(Offer offer) {
			this.offer = offer;
		}

		@DatabaseExecutor
		@Override
		public void run() {
			try {
				db.transaction(false, txn ->
						db.receiveOffer(txn, contactId, offer));
			} catch (DbException e) {
				interrupt();
			}
		}
	}

	private class ReceiveRequest implements Runnable {

		private final Request request;

		private ReceiveRequest(Request request) {
			this.request = request;
		}

		@DatabaseExecutor
		@Override
		public void run() {
			try {
				db.transaction(false, txn ->
						db.receiveRequest(txn, contactId, request));
			} catch (DbException e) {
				interrupt();
			}
		}
	}

	private class ReceiveVersions implements Runnable {

		private final Versions versions;

		private ReceiveVersions(Versions versions) {
			this.versions = versions;
		}

		@DatabaseExecutor
		@Override
		public void run() {
			try {
				List<Byte> supported = versions.getSupportedVersions();
				db.transaction(false,
						txn -> db.setSyncVersions(txn, contactId, supported));
			} catch (DbException e) {
				interrupt();
			}
		}
	}
}
