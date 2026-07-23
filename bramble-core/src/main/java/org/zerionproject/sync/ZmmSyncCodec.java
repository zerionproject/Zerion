package org.zerionproject.sync;

import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.SyncRecordReader;
import org.zerionproject.core.api.sync.SyncRecordReaderFactory;
import org.zerionproject.core.api.sync.SyncRecordWriter;
import org.zerionproject.core.api.sync.SyncRecordWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.inject.Inject;

/**
 * Serialises the delivery-DAG sync records (Message/Ack/Offer/Request) to and
 * from the opaque payload of a {@link ZmmConstants#TYPE_SYNC} record, reusing the
 * existing {@link SyncRecordWriter}/{@link SyncRecordReader} so the on-wire record
 * format is unchanged from the proven sync layer. ZPP carries these as opaque
 * bytes; the record's own kind is read back from its framing, not the ZMM type.
 */
@NotNullByDefault
public class ZmmSyncCodec {

	private final SyncRecordWriterFactory writerFactory;
	private final SyncRecordReaderFactory readerFactory;

	@Inject
	public ZmmSyncCodec(SyncRecordWriterFactory writerFactory,
			SyncRecordReaderFactory readerFactory) {
		this.writerFactory = writerFactory;
		this.readerFactory = readerFactory;
	}

	public byte[] encodeMessage(Message m) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SyncRecordWriter w = writerFactory.createRecordWriter(out, false);
		w.writeMessage(m);
		w.flush();
		return out.toByteArray();
	}

	public byte[] encodeAck(Ack a) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SyncRecordWriter w = writerFactory.createRecordWriter(out, false);
		w.writeAck(a);
		w.flush();
		return out.toByteArray();
	}

	public byte[] encodeOffer(Offer o) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SyncRecordWriter w = writerFactory.createRecordWriter(out, false);
		w.writeOffer(o);
		w.flush();
		return out.toByteArray();
	}

	public byte[] encodeRequest(Request r) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		SyncRecordWriter w = writerFactory.createRecordWriter(out, false);
		w.writeRequest(r);
		w.flush();
		return out.toByteArray();
	}

	/** A reader positioned at the start of one serialised sync record. */
	public SyncRecordReader newReader(byte[] payload) {
		return readerFactory.createRecordReader(
				new ByteArrayInputStream(payload), false);
	}
}
