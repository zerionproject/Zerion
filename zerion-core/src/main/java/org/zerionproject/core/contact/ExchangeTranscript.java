package org.zerionproject.core.contact;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.contact.ContactExchangeConstants.EXCHANGE_ROLE_ALICE;
import static org.zerionproject.core.contact.ContactExchangeConstants.EXCHANGE_ROLE_BOB;

@Immutable
@NotNullByDefault
final class ExchangeTranscript {

	private static final byte[] EMPTY = new byte[0];

	private final byte[] author;
	private final byte[] mlDsaSigPub;
	private final byte[] properties;
	private final long timestamp;
	private final byte[] staticPqPub;
	private final byte[] sessionId;

	ExchangeTranscript(byte[] author, byte[] mlDsaSigPub, byte[] properties,
			long timestamp, @Nullable byte[] staticPqPub,
			@Nullable byte[] sessionId) {
		this.author = author;
		this.mlDsaSigPub = mlDsaSigPub;
		this.properties = properties;
		this.timestamp = timestamp;
		this.staticPqPub = staticPqPub == null ? EMPTY : staticPqPub;
		this.sessionId = sessionId == null ? EMPTY : sessionId;
	}

	byte[][] inputs(boolean alice) {
		byte[] role = {alice ? EXCHANGE_ROLE_ALICE : EXCHANGE_ROLE_BOB};
		byte[] time = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
				.putLong(timestamp).array();
		return new byte[][] {role, author, mlDsaSigPub, properties, time,
				staticPqPub, sessionId};
	}
}
