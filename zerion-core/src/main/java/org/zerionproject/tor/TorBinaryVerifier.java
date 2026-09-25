package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;

/**
 * Checks the Tor and lyrebird executables against the values the build
 * pinned before the wrapper executes them. A verifier that cannot vouch for
 * a file throws, and the wrapper then refuses to start Tor; there is no
 * path on which an unverified binary runs.
 */
@NotNullByDefault
public interface TorBinaryVerifier {

	/**
	 * @param tor the file that is about to be executed as Tor
	 * @param lyrebird the file Tor will execute for pluggable transports
	 * @throws IOException if either file cannot be read or does not match
	 * a pinned value
	 */
	void verify(File tor, File lyrebird) throws IOException;
}
