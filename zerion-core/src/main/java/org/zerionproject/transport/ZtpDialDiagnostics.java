package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class ZtpDialDiagnostics {

	private final boolean dialing;
	private final long nextDialAtMs;

	public ZtpDialDiagnostics(boolean dialing, long nextDialAtMs) {
		this.dialing = dialing;
		this.nextDialAtMs = nextDialAtMs;
	}

	public boolean isDialing() {
		return dialing;
	}

	public long getNextDialAtMs() {
		return nextDialAtMs;
	}
}
