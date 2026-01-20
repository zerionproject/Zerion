package org.briarproject.bramble.api.record;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class Record {

	/**
	 * Header size for classical (Briar-compatible) record format.
	 * Format: [version:1][type:1][length_hi:1][length_lo:1]
	 */
	public static final int RECORD_HEADER_BYTES_CLASSICAL = 4;

	/**
	 * Header size for extended (post-quantum) record format.
	 * Format: [version:1][type:1][length:4]
	 */
	public static final int RECORD_HEADER_BYTES_EXTENDED = 6;

	/**
	 * Default header size - use extended for Zerion's larger payloads.
	 * Classical format should be explicitly requested when needed.
	 */
	public static final int RECORD_HEADER_BYTES = RECORD_HEADER_BYTES_EXTENDED;

	/**
	 * Max payload for classical format (Briar-compatible): 48 KiB.
	 */
	public static final int MAX_RECORD_PAYLOAD_BYTES_CLASSICAL = 48 * 1024;

	/**
	 * Max payload for extended format: 10 MiB.
	 */
	public static final int MAX_RECORD_PAYLOAD_BYTES_EXTENDED = 10 * 1024 * 1024;

	/**
	 * Default max payload - use extended for Zerion's larger payloads.
	 * Classical format should be explicitly requested when needed.
	 */
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
