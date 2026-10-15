package org.zerionproject.core.plugin.tor;

import org.zerionproject.core.plugin.tor.B4OnionRotation.PeerRotationState;
import org.zerionproject.core.plugin.tor.B4OnionRotation.RotationPhase;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class B4RotationDiagnostics {

	private final RotationPhase phase;
	private final boolean announced;
	private final PeerRotationState peerState;
	private final boolean contactAddressPending;
	private final long contactRetryAtMs;

	public B4RotationDiagnostics(RotationPhase phase, boolean announced,
			PeerRotationState peerState, boolean contactAddressPending,
			long contactRetryAtMs) {
		this.phase = phase;
		this.announced = announced;
		this.peerState = peerState;
		this.contactAddressPending = contactAddressPending;
		this.contactRetryAtMs = contactRetryAtMs;
	}

	public RotationPhase getPhase() {
		return phase;
	}

	public boolean isAnnounced() {
		return announced;
	}

	public PeerRotationState getPeerState() {
		return peerState;
	}

	public boolean isContactAddressPending() {
		return contactAddressPending;
	}

	public long getContactRetryAtMs() {
		return contactRetryAtMs;
	}
}
