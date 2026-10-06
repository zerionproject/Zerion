package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface OverlayTransport {

	long DIAL_NOT_CONNECTED = -1L;

	TransportId getTransportId();

	String getAddressPropertyKey();

	long dial(int contactId, String peerAddress, boolean fast);

	default boolean isValidAddress(String peerAddress) {
		return true;
	}

	void setNetworkEnabled(boolean enabled);

	default boolean isNetworkDegraded() {
		return false;
	}

	default void restartNetwork() {
	}

	default void refreshPeerDescriptors() {
	}
}
