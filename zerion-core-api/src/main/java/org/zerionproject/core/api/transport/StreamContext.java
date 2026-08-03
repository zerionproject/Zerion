package org.zerionproject.core.api.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.PqRatchetState;
import org.zerionproject.core.api.plugin.TransportId;
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
	private final boolean pcsEnabled;
	@Nullable
	private final PcsSessionState pcsState;
	@Nullable
	private final PqRatchetState pqRatchetState;

	public StreamContext(@Nullable ContactId contactId,
			@Nullable PendingContactId pendingContactId,
			TransportId transportId, SecretKey tagKey, SecretKey headerKey,
			long streamNumber, boolean handshakeMode) {
		this(contactId, pendingContactId, transportId, tagKey, headerKey,
				streamNumber, handshakeMode, false, false, null, null);
	}

	public StreamContext(@Nullable ContactId contactId,
			@Nullable PendingContactId pendingContactId,
			TransportId transportId, SecretKey tagKey, SecretKey headerKey,
			long streamNumber, boolean handshakeMode, boolean classical) {
		this(contactId, pendingContactId, transportId, tagKey, headerKey,
				streamNumber, handshakeMode, classical, false, null, null);
	}

	public StreamContext(@Nullable ContactId contactId,
			@Nullable PendingContactId pendingContactId,
			TransportId transportId, SecretKey tagKey, SecretKey headerKey,
			long streamNumber, boolean handshakeMode, boolean classical,
			boolean pcsEnabled, @Nullable PcsSessionState pcsState) {
		this(contactId, pendingContactId, transportId, tagKey, headerKey,
				streamNumber, handshakeMode, classical, pcsEnabled, pcsState, null);
	}

	public StreamContext(@Nullable ContactId contactId,
			@Nullable PendingContactId pendingContactId,
			TransportId transportId, SecretKey tagKey, SecretKey headerKey,
			long streamNumber, boolean handshakeMode, boolean classical,
			boolean pcsEnabled, @Nullable PcsSessionState pcsState,
			@Nullable PqRatchetState pqRatchetState) {
		requireExactlyOneNull(contactId, pendingContactId);
		if (pcsEnabled && pcsState == null) {
			throw new IllegalArgumentException(
					"PCS state required when PCS is enabled");
		}
		this.contactId = contactId;
		this.pendingContactId = pendingContactId;
		this.transportId = transportId;
		this.tagKey = tagKey;
		this.headerKey = headerKey;
		this.streamNumber = streamNumber;
		this.handshakeMode = handshakeMode;
		this.classical = classical;
		this.pcsEnabled = pcsEnabled;
		this.pcsState = pcsState;
		this.pqRatchetState = pqRatchetState;
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

	public boolean isClassical() {
		return classical;
	}

	public boolean isPcsEnabled() {
		return pcsEnabled;
	}

	@Nullable
	public PcsSessionState getPcsState() {
		return pcsState;
	}

	@Nullable
	public PqRatchetState getPqRatchetState() {
		return pqRatchetState;
	}
}
