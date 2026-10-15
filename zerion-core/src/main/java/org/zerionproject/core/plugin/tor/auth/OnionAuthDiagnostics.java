package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class OnionAuthDiagnostics {

	private final State state;
	private final boolean commitSent;
	private final int repairs;
	private final boolean rekeying;
	private final int silenceRepairs;
	private final int openDials;
	private final long ownGeneration;
	private final long contactGeneration;

	public OnionAuthDiagnostics(State state, boolean commitSent, int repairs,
			boolean rekeying, int silenceRepairs, int openDials,
			long ownGeneration, long contactGeneration) {
		this.state = state;
		this.commitSent = commitSent;
		this.repairs = repairs;
		this.rekeying = rekeying;
		this.silenceRepairs = silenceRepairs;
		this.openDials = openDials;
		this.ownGeneration = ownGeneration;
		this.contactGeneration = contactGeneration;
	}

	public State getState() {
		return state;
	}

	public boolean isCommitSent() {
		return commitSent;
	}

	public int getRepairs() {
		return repairs;
	}

	public boolean isRepairing() {
		return state == State.AUTH_CONFIRMED && commitSent && repairs > 0
				&& repairs < OnionClientAuthManagerImpl.MAX_REPAIRS;
	}

	public boolean isRenewing() {
		return state == State.AUTH_REQUIRED && rekeying;
	}

	public int getReachRepairs() {
		return silenceRepairs + openDials;
	}

	public boolean isReaching() {
		return state == State.AUTH_REQUIRED && !rekeying
				&& getReachRepairs() > 0;
	}

	public long getOwnGeneration() {
		return ownGeneration;
	}

	public long getContactGeneration() {
		return contactGeneration;
	}
}
