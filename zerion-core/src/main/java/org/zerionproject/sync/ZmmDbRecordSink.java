package org.zerionproject.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.SyncRecordReader;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmReassembler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

@NotNullByDefault
public class ZmmDbRecordSink implements ZppRecordSink {

	private final DatabaseComponent db;
	private final ZmmSyncCodec codec;
	private final ZmmReassembler reassembler = new ZmmReassembler();

	private final LocalMessageLog localMessages;

	public ZmmDbRecordSink(DatabaseComponent db, ZmmSyncCodec codec) {
		this(db, codec, new LocalMessageLog());
	}

	@Inject
	public ZmmDbRecordSink(DatabaseComponent db, ZmmSyncCodec codec,
			LocalMessageLog localMessages) {
		this.db = db;
		this.codec = codec;
		this.localMessages = localMessages;
	}

	@Override
	public void deliver(int contactId, long sessionId, int type,
			byte[] payload) {
		ZmmReassembler.Message record = reassembler.receive(contactId,
				sessionId, type, payload);
		if (record == null || record.type != ZmmConstants.TYPE_SYNC) return;
		try {
			applyRecord(new ContactId(contactId), record.payload);
		} catch (IOException | DbException | RuntimeException e) {
		}
	}

	@Override
	public void onDisconnected(int contactId, long sessionId) {
		reassembler.sessionClosed(sessionId);
	}

	private void applyRecord(ContactId c, byte[] recordBytes)
			throws IOException, DbException {
		SyncRecordReader reader = codec.newReader(recordBytes);
		if (reader.hasAck()) {
			Ack a = reader.readAck();
			db.transaction(false, txn -> db.receiveAck(txn, c, a));
			localMessages.noteAcked(a.getMessageIds());
		} else if (reader.hasMessage()) {
			Message m = reader.readMessage();
			db.transaction(false, txn -> db.receiveMessage(txn, c, m));
		} else if (reader.hasOffer()) {
			Offer o = reader.readOffer();
			db.transaction(false, txn -> db.receiveOffer(txn, c, o));
		} else if (reader.hasRequest()) {
			Request r = reader.readRequest();
			localMessages.noteRequested(c, unseenUserTraffic(c, r));
			db.transaction(false, txn -> db.receiveRequest(txn, c, r));
		}
	}

	private List<MessageId> unseenUserTraffic(ContactId c, Request r) {
		List<MessageId> unseen = new ArrayList<>();
		for (MessageId id : r.getMessageIds()) {
			if (!localMessages.isUserTraffic(id)) continue;
			try {
				if (!db.transactionWithResult(true, txn ->
						db.getMessageStatus(txn, c, id)).isSeen()) {
					unseen.add(id);
				}
			} catch (DbException | RuntimeException e) {
			}
		}
		return unseen;
	}
}
