package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface OverlayTransport {

	long DIAL_NOT_CONNECTED = -1L;

	final class DialResult {

		public static final DialResult NOT_CONNECTED =
				new DialResult(false, DIAL_NOT_CONNECTED);

		public static final DialResult NOT_ATTEMPTED =
				new DialResult(false, DIAL_NOT_CONNECTED, false);

		private final boolean authenticated;
		private final long sessionMs;
		private final boolean attempted;

		public DialResult(boolean authenticated, long sessionMs) {
			this(authenticated, sessionMs, true);
		}

		private DialResult(boolean authenticated, long sessionMs,
				boolean attempted) {
			this.authenticated = authenticated;
			this.sessionMs = sessionMs;
			this.attempted = attempted;
		}

		public boolean isAttempted() {
			return attempted;
		}

		public boolean isAuthenticated() {
			return authenticated;
		}

		public long getSessionMs() {
			return sessionMs;
		}
	}

	TransportId getTransportId();

	String getAddressPropertyKey();

	DialResult dial(int contactId, String peerAddress, boolean fast);

	default DialResult dial(int contactId, String peerAddress, boolean fast,
			boolean refusalExpected) {
		return dial(contactId, peerAddress, fast);
	}

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
