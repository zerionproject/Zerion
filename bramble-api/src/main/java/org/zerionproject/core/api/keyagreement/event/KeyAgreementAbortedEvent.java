package org.zerionproject.core.api.keyagreement.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class KeyAgreementAbortedEvent extends Event {

	private final boolean remoteAborted;

	public KeyAgreementAbortedEvent(boolean remoteAborted) {
		this.remoteAborted = remoteAborted;
	}

	public boolean didRemoteAbort() {
		return remoteAborted;
	}
}
