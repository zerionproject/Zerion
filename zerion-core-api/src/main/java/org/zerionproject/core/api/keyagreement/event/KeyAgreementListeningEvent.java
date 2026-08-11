package org.zerionproject.core.api.keyagreement.event;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.keyagreement.Payload;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class KeyAgreementListeningEvent extends Event {

	private final Payload localPayload;

	public KeyAgreementListeningEvent(Payload localPayload) {
		this.localPayload = localPayload;
	}

	public Payload getLocalPayload() {
		return localPayload;
	}
}
