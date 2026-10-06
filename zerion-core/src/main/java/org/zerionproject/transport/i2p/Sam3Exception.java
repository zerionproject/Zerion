package org.zerionproject.transport.i2p;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;

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
