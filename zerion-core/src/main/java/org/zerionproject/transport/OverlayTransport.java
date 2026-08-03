package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * A network transport that can dial a contact by a string address and run the
 * resulting connection through the shared session/ratchet stack. The poller
 * talks to a transport only through this interface, so a new transport (I2P,
 * mesh) needs to supply its id, the property key that holds a peer's address,
 * a dial method, and a network-enabled toggle; the session layer below is
 * unchanged.
 */
@NotNullByDefault
public interface OverlayTransport {

	/** Returned by {@link #dial} when the socket never connected. */
	long DIAL_NOT_CONNECTED = -1L;

	/** This transport's id, used for property lookups and connection tagging. */
	TransportId getTransportId();

	/** The {@link org.zerionproject.core.api.properties.TransportProperties}
	 * key under which a peer's (and our own) address is published. */
	String getAddressPropertyKey();

	/**
	 * Dials a contact at {@code peerAddress} and runs the connection until it
	 * ends. Returns the session duration in milliseconds, or
	 * {@link #DIAL_NOT_CONNECTED} if the socket never connected. The connect
	 * phase is excluded from the duration.
	 *
	 * @param fast use the shorter burst connect timeout for a re-dial right
	 * after a drop, rather than the full first-connect timeout.
	 */
	long dial(int contactId, String peerAddress, boolean fast);

	/** Enables or disables this transport's network on a connectivity change. */
	void setNetworkEnabled(boolean enabled);
}
