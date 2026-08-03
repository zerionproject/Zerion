package org.zerionproject.transport.i2p;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;

/**
 * Thrown when a SAM v3 bridge returns a non-OK result. {@link #getResult}
 * carries the SAM RESULT token (for example {@code CANT_REACH_PEER},
 * {@code DUPLICATED_ID}, {@code INVALID_KEY}) so callers can distinguish a
 * transient dial failure from a configuration error.
 */
@NotNullByDefault
public class Sam3Exception extends IOException {

	private final String result;

	public Sam3Exception(String result, @Nullable String message) {
		super("SAM result " + result
				+ (message == null ? "" : ": " + message));
		this.result = result;
	}

	public String getResult() {
		return result;
	}
}
