package org.zerionproject.core.api.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_ID_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.X25519_BYTES;

@Immutable
@NotNullByDefault
public final class InviteLink {

	public enum Flow {
		REQUEST, MUTUAL_V1, MUTUAL_CLASSICAL
	}

	private final int type;
	private final byte[] body;
	@Nullable
	private final String inbox;
	private final Flow flow;

	public InviteLink(int type, byte[] body, @Nullable String inbox,
			Flow flow) {
		this.type = type;
		this.body = body.clone();
		this.inbox = inbox;
		this.flow = flow;
	}

	public int getType() {
		return type;
	}

	public byte[] getBody() {
		return body.clone();
	}

	@Nullable
	public String getInbox() {
		return inbox;
	}

	public Flow getFlow() {
		return flow;
	}

	public byte[] getCommitment() {
		if (flow == Flow.MUTUAL_CLASSICAL) throw new IllegalStateException();
		return Arrays.copyOfRange(body, 1, 1 + LINK_ID_BYTES);
	}

	public byte[] getX25519() {
		if (flow == Flow.MUTUAL_CLASSICAL) throw new IllegalStateException();
		return Arrays.copyOfRange(body, 1 + LINK_ID_BYTES,
				1 + LINK_ID_BYTES + X25519_BYTES);
	}

	public byte[] getKeyBlob() {
		return Arrays.copyOfRange(body, 1, body.length);
	}
}
