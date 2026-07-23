package org.zerionproject.core.record;

import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordWriter;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;

import javax.annotation.concurrent.NotThreadSafe;

import static org.zerionproject.core.api.record.Record.MAX_RECORD_PAYLOAD_BYTES_CLASSICAL;
import static org.zerionproject.core.api.record.Record.RECORD_HEADER_BYTES_CLASSICAL;

@NotThreadSafe
@NotNullByDefault
class ClassicalRecordWriterImpl implements RecordWriter {

	private final OutputStream out;
	private final byte[] header = new byte[RECORD_HEADER_BYTES_CLASSICAL];

	private long bytesWritten = 0;

	ClassicalRecordWriterImpl(OutputStream out) {
		this.out = out;
	}

	@Override
	public void writeRecord(Record r) throws IOException {
		byte[] payload = r.getPayload();
		if (payload.length > MAX_RECORD_PAYLOAD_BYTES_CLASSICAL) {
			throw new IllegalArgumentException(
					"Payload too large for classical format: " + payload.length +
					" > " + MAX_RECORD_PAYLOAD_BYTES_CLASSICAL);
		}
		header[0] = r.getProtocolVersion();
		header[1] = r.getRecordType();
		ByteUtils.writeUint16(payload.length, header, 2);
		out.write(header);
		out.write(payload);
		bytesWritten += RECORD_HEADER_BYTES_CLASSICAL + payload.length;
	}

	@Override
	public void flush() throws IOException {
		out.flush();
	}

	@Override
	public void close() throws IOException {
		out.close();
	}

	@Override
	public long getBytesWritten() {
		return bytesWritten;
	}
}
