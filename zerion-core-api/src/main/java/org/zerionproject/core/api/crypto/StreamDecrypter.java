package org.zerionproject.core.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

@NotNullByDefault
public interface StreamDecrypter {

	int readFrame(byte[] payload) throws IOException;
}
