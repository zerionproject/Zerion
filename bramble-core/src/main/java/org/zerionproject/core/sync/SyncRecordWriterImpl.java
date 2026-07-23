package org.zerionproject.core.sync;

import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordWriter;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Priority;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.SyncRecordWriter;
import org.zerionproject.core.api.sync.Versions;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.annotation.concurrent.NotThreadSafe;

import static org.zerionproject.core.api.sync.RecordTypes.ACK;
import static org.zerionproject.core.api.sync.RecordTypes.COVER;
import static org.zerionproject.core.api.sync.RecordTypes.MESSAGE;
import static org.zerionproject.core.api.sync.RecordTypes.MESSAGE_FRAGMENT;
import static org.zerionproject.core.api.sync.RecordTypes.OFFER;
import static org.zerionproject.core.api.sync.RecordTypes.PRIORITY;
import static org.zerionproject.core.api.sync.RecordTypes.REQUEST;
import static org.zerionproject.core.api.sync.RecordTypes.VERSIONS;
import static org.zerionproject.core.api.sync.SyncConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.api.UniqueId.LENGTH;

@NotThreadSafe
@NotNullByDefault
class SyncRecordWriterImpl implements SyncRecordWriter {

	private final MessageFactory messageFactory;
	private final RecordWriter writer;
	private final ByteArrayOutputStream payload = new ByteArrayOutputStream();

	SyncRecordWriterImpl(MessageFactory messageFactory, RecordWriter writer) {
		this.messageFactory = messageFactory;
		this.writer = writer;
	}

	private void writeRecord(byte recordType) throws IOException {
		writer.writeRecord(new Record(PROTOCOL_VERSION, recordType,
				payload.toByteArray()));
		payload.reset();
	}

	@Override
	public void writeAck(Ack a) throws IOException {
		for (MessageId m : a.getMessageIds()) payload.write(m.getBytes());
		writeRecord(ACK);
	}

	private static final int FRAGMENT_HEADER_LEN = LENGTH + 4;
	private static final int FRAGMENT_THRESHOLD_BYTES = 900;
	private static final int FRAGMENT_PAYLOAD_BYTES = 768;

	@Override
	public void writeMessage(Message m) throws IOException {
		byte[] raw = messageFactory.getRawMessage(m);
		if (raw.length <= FRAGMENT_THRESHOLD_BYTES) {
			writer.writeRecord(new Record(PROTOCOL_VERSION, MESSAGE, raw));
			return;
		}
		byte[] id = m.getId().getBytes();
		int total = (raw.length + FRAGMENT_PAYLOAD_BYTES - 1)
				/ FRAGMENT_PAYLOAD_BYTES;
		if (total > 0xFFFF) throw new IOException("message too large");
		for (int i = 0; i < total; i++) {
			int start = i * FRAGMENT_PAYLOAD_BYTES;
			int end = Math.min(start + FRAGMENT_PAYLOAD_BYTES, raw.length);
			int chunkLen = end - start;
			byte[] fp = new byte[FRAGMENT_HEADER_LEN + chunkLen];
			System.arraycopy(id, 0, fp, 0, LENGTH);
			fp[LENGTH] = (byte) ((i >> 8) & 0xFF);
			fp[LENGTH + 1] = (byte) (i & 0xFF);
			fp[LENGTH + 2] = (byte) ((total >> 8) & 0xFF);
			fp[LENGTH + 3] = (byte) (total & 0xFF);
			System.arraycopy(raw, start, fp, FRAGMENT_HEADER_LEN, chunkLen);
			writer.writeRecord(
					new Record(PROTOCOL_VERSION, MESSAGE_FRAGMENT, fp));
		}
	}

	@Override
	public void writeOffer(Offer o) throws IOException {
		for (MessageId m : o.getMessageIds()) payload.write(m.getBytes());
		writeRecord(OFFER);
	}

	@Override
	public void writeRequest(Request r) throws IOException {
		for (MessageId m : r.getMessageIds()) payload.write(m.getBytes());
		writeRecord(REQUEST);
	}

	@Override
	public void writeVersions(Versions v) throws IOException {
		for (byte b : v.getSupportedVersions()) payload.write(b);
		writeRecord(VERSIONS);
	}

	@Override
	public void writePriority(Priority p) throws IOException {
		writer.writeRecord(
				new Record(PROTOCOL_VERSION, PRIORITY, p.getNonce()));
	}

	@Override
	public void writeCover(int paddingBytes) throws IOException {
		if (paddingBytes < 0) paddingBytes = 0;
		byte[] padding = new byte[paddingBytes];
		java.util.concurrent.ThreadLocalRandom.current().nextBytes(padding);
		writer.writeRecord(new Record(PROTOCOL_VERSION, COVER, padding));
	}

	@Override
	public void flush() throws IOException {
		writer.flush();
	}

	@Override
	public long getBytesWritten() {
		return writer.getBytesWritten();
	}
}
