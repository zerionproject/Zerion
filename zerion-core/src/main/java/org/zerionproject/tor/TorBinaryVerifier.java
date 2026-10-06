package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;

@NotNullByDefault
public interface TorBinaryVerifier {

	void verify(File tor, File lyrebird) throws IOException;
}
