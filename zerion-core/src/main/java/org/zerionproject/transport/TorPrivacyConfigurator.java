package org.zerionproject.transport;

import java.io.IOException;

/**
 * Applies the privacy options the transport relies on to the running Tor
 * process and proves from Tor's own effective configuration that they are
 * active. Startup must fail when the proof fails, so that the transport
 * never runs on a Tor whose SOCKS listener does not isolate streams by the
 * credentials the application presents or whose link padding is off.
 */
public interface TorPrivacyConfigurator {

	/**
	 * Configures and verifies the SOCKS isolation flags and connection
	 * padding on the running Tor process.
	 *
	 * @throws IOException if the control connection fails or Tor reports an
	 * effective configuration without the required options.
	 */
	void applyAndVerify() throws IOException;
}
