package org.briarproject.bramble.api.transport;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.briarproject.nullsafety.NullSafety.requireExactlyOneNull;

@Immutable
@NotNullByDefault
public class StreamContext {

	@Nullable
	private final ContactId contactId;
	@Nullable
	private final PendingContactId pendingContactId;
	private final TransportId transportId;
	private final SecretKey tagKey, headerKey;
	private final long streamNumber;
	private final boolean handshakeMode;
	private final boolean classical;

	/**
	 * Creates a StreamContext with the default (extended) record format.
	 * Use this constructor for established Zerion↔Zerion contacts.
	 */
	public StreamContext(@Nullable ContactId contactId,
			@Nullable PendingContactId pendingContactId,
			TransportId transportId, SecretKey tagKey, SecretKey headerKey,
			long streamNumber, boolean handshakeMode) {
		this(contactId, pendingContactId, transportId, tagKey, headerKey,
				streamNumber, handshakeMode, false);
	}

	/**
	 * Creates a StreamContext with the specified record format.
	 *
	 * @param classical true for Briar-compatible 4-byte header format,
	 *                  false for extended 6-byte header format
	 */
	public StreamContext(@Nullable ContactId contactId,
			@Nullable PendingContactId pendingContactId,
			TransportId transportId, SecretKey tagKey, SecretKey headerKey,
			long streamNumber, boolean handshakeMode, boolean classical) {
		requireExactlyOneNull(contactId, pendingContactId);
		this.contactId = contactId;
		this.pendingContactId = pendingContactId;
		this.transportId = transportId;
		this.tagKey = tagKey;
		this.headerKey = headerKey;
		this.streamNumber = streamNumber;
		this.handshakeMode = handshakeMode;
		this.classical = classical;
	}

	@Nullable
	public ContactId getContactId() {
		return contactId;
	}

	@Nullable
	public PendingContactId getPendingContactId() {
		return pendingContactId;
	}

	public TransportId getTransportId() {
		return transportId;
	}

	public SecretKey getTagKey() {
		return tagKey;
	}

	public SecretKey getHeaderKey() {
		return headerKey;
	}

	public long getStreamNumber() {
		return streamNumber;
	}

	public boolean isHandshakeMode() {
		return handshakeMode;
	}

	/**
	 * Returns true if this stream uses Briar-compatible classical record format
	 * (4-byte header, uint16 length, 48KB max), or false for extended format
	 * (6-byte header, uint32 length, 10MB max).
	 */
	public boolean isClassical() {
		return classical;
	}
}
