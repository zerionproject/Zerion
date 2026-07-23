package org.zerionproject.core.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

@NotNullByDefault
public interface StreamEncrypter {

	void writeFrame(byte[] payload, int payloadLength, int paddingLength,
			boolean finalFrame) throws IOException;

	int getMaxPayloadLength();

	void flush() throws IOException;
}
