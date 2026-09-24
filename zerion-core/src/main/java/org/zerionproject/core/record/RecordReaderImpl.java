package org.zerionproject.core.record;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static org.zerionproject.core.api.record.Record.MAX_RECORD_PAYLOAD_BYTES;
import static org.zerionproject.core.api.record.Record.RECORD_HEADER_BYTES;

@NotThreadSafe
@NotNullByDefault
class RecordReaderImpl implements RecordReader {

	/**
	 * Records skipped by one accept-or-ignore call before it gives up: a
	 * peer may not hold the reader in a loop of unknown record types.
	 */
	static final int MAX_IGNORED_RECORDS = 64;

	private final DataInputStream in;
	private final byte[] header = new byte[RECORD_HEADER_BYTES];
	private final int maxPayloadBytes;

	RecordReaderImpl(InputStream in) {
		this(in, MAX_RECORD_PAYLOAD_BYTES);
	}

	RecordReaderImpl(InputStream in, int maxPayloadBytes) {
		if (!in.markSupported()) in = new BufferedInputStream(in, 1);
		this.in = new DataInputStream(in);
		this.maxPayloadBytes = Math.min(maxPayloadBytes,
				MAX_RECORD_PAYLOAD_BYTES);
	}

	@Override
	public Record readRecord() throws IOException {
		in.readFully(header);
		byte protocolVersion = header[0];
		byte recordType = header[1];
		long payloadLengthLong = ByteUtils.readUint32(header, 2);
		if (payloadLengthLong < 0 || payloadLengthLong > maxPayloadBytes)
			throw new FormatException();
		int payloadLength = (int) payloadLengthLong;
		byte[] payload = new byte[payloadLength];
		in.readFully(payload);
		return new Record(protocolVersion, recordType, payload);
	}

	@Nullable
	@Override
	public Record readRecord(RecordPredicate accept, RecordPredicate ignore)
			throws IOException {
		int ignored = 0;
		while (true) {
			if (eof()) return null;
			Record r = readRecord();
			if (accept.test(r)) return r;
			if (!ignore.test(r)) throw new FormatException();
			if (++ignored > MAX_IGNORED_RECORDS) throw new FormatException();
		}
	}

	@Override
	public void close() throws IOException {
		in.close();
	}

	private boolean eof() throws IOException {
		in.mark(1);
		int next = in.read();
		in.reset();
		return next == -1;
	}
}
