package org.briarproject.bramble.api.transport;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.crypto.pcs.PqRatchetState;
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
	private final boolean pcsEnabled;
	@Nullable
	private final PcsSessionState pcsState;
	@Nullable
	private final PqRatchetState pqRatchetState;

	/**
	 * Creates a StreamContext with the default (extended) record format.
	 * Use this constructor for established Zerion↔Zerion contacts.
	 */
	public StreamContext(@Nullable ContactId contactId,
			@Nullable PendingContactId pendingContactId,
			TransportId transportId, SecretKey tagKey, SecretKey headerKey,
			long streamNumber, boolean handshakeMode) {
		this(contactId, pendingContactId, transportId, tagKey, headerKey,
				streamNumber, handshakeMode, false, false, null, null);
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
		this(contactId, pendingContactId, transportId, tagKey, headerKey,
				streamNumber, handshakeMode, classical, false, null, null);
	}

	/**
	 * Creates a StreamContext with full configuration including PCS support.
	 *
	 * @param classical true for Briar-compatible 4-byte header format,
	 *                  false for extended 6-byte header format
	 * @param pcsEnabled true if Post-Compromise Security is enabled
	 * @param pcsState the PCS session state (required if pcsEnabled is true)
	 */
	public StreamContext(@Nullable ContactId contactId,
			@Nullable PendingContactId pendingContactId,
			TransportId transportId, SecretKey tagKey, SecretKey headerKey,
			long streamNumber, boolean handshakeMode, boolean classical,
			boolean pcsEnabled, @Nullable PcsSessionState pcsState) {
		this(contactId, pendingContactId, transportId, tagKey, headerKey,
				streamNumber, handshakeMode, classical, pcsEnabled, pcsState, null);
	}

	/**
	 * Creates a StreamContext with full configuration including Mode 3 support.
	 *
	 * @param classical true for Briar-compatible 4-byte header format,
	 *                  false for extended 6-byte header format
	 * @param pcsEnabled true if Post-Compromise Security is enabled
	 * @param pcsState the PCS session state (required if pcsEnabled is true)
	 * @param pqRatchetState the PQ ratchet state for Mode 3 (optional)
	 */
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

	/**
	 * Returns true if this stream uses Briar-compatible classical record format
	 * (4-byte header, uint16 length, 48KB max), or false for extended format
	 * (6-byte header, uint32 length, 10MB max).
	 */
	public boolean isClassical() {
		return classical;
	}

	/**
	 * Returns true if Post-Compromise Security (PCS) is enabled for this stream.
	 * <p>
	 * When PCS is enabled, each frame is encrypted with a unique key derived
	 * from the PCS symmetric ratchet, providing forward secrecy per message.
	 */
	public boolean isPcsEnabled() {
		return pcsEnabled;
	}

	/**
	 * Returns the PCS session state, or null if PCS is not enabled.
	 */
	@Nullable
	public PcsSessionState getPcsState() {
		return pcsState;
	}

	/**
	 * Returns the PQ ratchet state for Mode 3, or null if Mode 3 is not active.
	 */
	@Nullable
	public PqRatchetState getPqRatchetState() {
		return pqRatchetState;
	}
}
