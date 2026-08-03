package org.zerionproject.core.api.record;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class Record {

	public static final int RECORD_HEADER_BYTES_CLASSICAL = 4;

	public static final int RECORD_HEADER_BYTES_EXTENDED = 6;

	public static final int RECORD_HEADER_BYTES = RECORD_HEADER_BYTES_EXTENDED;

	public static final int MAX_RECORD_PAYLOAD_BYTES_CLASSICAL = 48 * 1024;

	public static final int MAX_RECORD_PAYLOAD_BYTES_EXTENDED = 10 * 1024 * 1024;

	public static final int MAX_RECORD_PAYLOAD_BYTES = MAX_RECORD_PAYLOAD_BYTES_EXTENDED;

	private final byte protocolVersion, recordType;
	private final byte[] payload;

	public Record(byte protocolVersion, byte recordType, byte[] payload) {
		if (payload.length > MAX_RECORD_PAYLOAD_BYTES)
			throw new IllegalArgumentException();
		this.protocolVersion = protocolVersion;
		this.recordType = recordType;
		this.payload = payload;
	}

	public byte getProtocolVersion() {
		return protocolVersion;
	}

	public byte getRecordType() {
		return recordType;
	}

	public byte[] getPayload() {
		return payload;
	}
}
