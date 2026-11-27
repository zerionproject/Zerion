package org.zerionproject.core.api.contact.request.event;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.event.Event;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ContactLinkFloodedEvent extends Event {

	private final byte[] linkId;
	private final int kind;

	public ContactLinkFloodedEvent(byte[] linkId, int kind) {
		this.linkId = linkId.clone();
		this.kind = kind;
	}

	public byte[] getLinkId() {
		return linkId.clone();
	}

	public int getKind() {
		return kind;
	}
}
