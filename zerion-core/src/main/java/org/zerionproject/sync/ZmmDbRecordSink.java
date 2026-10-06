package org.zerionproject.sync;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.SyncRecordReader;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmReassembler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.inject.Inject;

@NotNullByDefault
public class ZmmDbRecordSink implements ZppRecordSink {

	private final DatabaseComponent db;
	private final ZmmSyncCodec codec;
	private final ZmmReassembler reassembler = new ZmmReassembler();

	@Inject
	public ZmmDbRecordSink(DatabaseComponent db, ZmmSyncCodec codec) {
		this.db = db;
		this.codec = codec;
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
		} else if (reader.hasMessage()) {
			Message m = reader.readMessage();
			db.transaction(false, txn -> db.receiveMessage(txn, c, m));
		} else if (reader.hasOffer()) {
			Offer o = reader.readOffer();
			db.transaction(false, txn -> db.receiveOffer(txn, c, o));
		} else if (reader.hasRequest()) {
			Request r = reader.readRequest();
			db.transaction(false, txn -> db.receiveRequest(txn, c, r));
		}
	}
}
