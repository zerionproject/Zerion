package org.zerionproject.core.api.sync;

import org.zerionproject.core.api.UniqueId;

import java.util.List;

import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.zerionproject.core.api.record.Record.MAX_RECORD_PAYLOAD_BYTES;

public interface SyncConstants {

	byte PROTOCOL_VERSION = 0;

	List<Byte> SUPPORTED_VERSIONS = singletonList(PROTOCOL_VERSION);

	int MAX_GROUP_DESCRIPTOR_LENGTH = 16 * 1024;

	int MESSAGE_HEADER_LENGTH = UniqueId.LENGTH + 8;

	int MAX_MESSAGE_BODY_LENGTH = MAX_RECORD_PAYLOAD_BYTES - MESSAGE_HEADER_LENGTH;

	int MAX_MESSAGE_LENGTH = MAX_RECORD_PAYLOAD_BYTES;

	int MAX_MESSAGE_IDS = MAX_RECORD_PAYLOAD_BYTES / UniqueId.LENGTH;

	int MAX_SUPPORTED_VERSIONS = 10;

	int PRIORITY_NONCE_BYTES = 16;

	long MAX_TRANSPORT_LATENCY = DAYS.toMillis(365);
}
