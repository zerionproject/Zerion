package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class OnionAuthRecord {

	public final ContactId contactId;
	public State state;
	public long localGen;
	public long peerGen;
	@Nullable
	public byte[] dialPrivateKey;
	@Nullable
	public byte[] dialPublicKey;
	@Nullable
	public String peerOnion;
	@Nullable
	public byte[] peerPublicKey;
	public boolean readyReceived;
	public boolean probeSucceeded;
	public boolean peerProbeSucceeded;
	public boolean commitSent;
	public boolean peerCommitReceived;
	public boolean probing;
	public long probeUntilMs;
	public long negotiationStartedMs;
	public boolean rotateAcked;

	public OnionAuthRecord(ContactId contactId) {
		this.contactId = contactId;
		this.state = State.LEGACY;
	}

	public boolean hasPeerService() {
		return peerOnion != null && dialPrivateKey != null;
	}

	public boolean authorizesPeer() {
		return peerPublicKey != null && state != State.LEGACY
				&& state != State.REVOKED;
	}
}
