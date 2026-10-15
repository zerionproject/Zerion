package org.zerionproject.core.api.connection;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class ContactConnectionSnapshot {

	public static final long UNKNOWN = -1;

	public enum Tor {
		CONNECTED,
		CONNECTING,
		NO_INTERNET,
		CLOCK_WRONG,
		ENTRY_RELAY_FAILING,
		OFF,
		PAUSED,
		OFFLINE_MODE;

		public boolean isUp() {
			return this == CONNECTED || this == ENTRY_RELAY_FAILING;
		}
	}

	public enum Reach {
		ONLINE,
		DIALING,
		RETRY_LATER,
		RETRY_SOON,
		WAITING_FOR_CONTACT,
		NO_ADDRESS,
		NEEDS_TOR
	}

	public enum PeerAddress {
		CURRENT,
		NEW_ANNOUNCED,
		NEW_RETRY_LATER
	}

	public enum OwnRotation {
		IDLE,
		PREPARING,
		CONTACT_MOVED,
		CONTACT_NOT_MOVED
	}

	public enum Authorization {
		NOT_SUPPORTED,
		SETTING_UP,
		AWAITING_CONFIRMATION,
		REPAIRING,
		ACTIVE,
		RENEWING,
		REACHING,
		REVOKED
	}

	private final Tor tor;
	private final Reach reach;
	private final long nextAttemptInMs;
	private final long lastConnectedAgoMs;
	private final PeerAddress peerAddress;
	private final long peerRetryInMs;
	private final OwnRotation ownRotation;
	private final Authorization authorization;
	private final int repairAttempts;
	private final long ownGeneration;
	private final long contactGeneration;

	public ContactConnectionSnapshot(Tor tor, Reach reach,
			long nextAttemptInMs, long lastConnectedAgoMs,
			PeerAddress peerAddress, long peerRetryInMs,
			OwnRotation ownRotation, Authorization authorization,
			int repairAttempts, long ownGeneration, long contactGeneration) {
		this.tor = tor;
		this.reach = reach;
		this.nextAttemptInMs = nextAttemptInMs;
		this.lastConnectedAgoMs = lastConnectedAgoMs;
		this.peerAddress = peerAddress;
		this.peerRetryInMs = peerRetryInMs;
		this.ownRotation = ownRotation;
		this.authorization = authorization;
		this.repairAttempts = repairAttempts;
		this.ownGeneration = ownGeneration;
		this.contactGeneration = contactGeneration;
	}

	public Tor getTor() {
		return tor;
	}

	public Reach getReach() {
		return reach;
	}

	public long getNextAttemptInMs() {
		return nextAttemptInMs;
	}

	public long getLastConnectedAgoMs() {
		return lastConnectedAgoMs;
	}

	public PeerAddress getPeerAddress() {
		return peerAddress;
	}

	public long getPeerRetryInMs() {
		return peerRetryInMs;
	}

	public OwnRotation getOwnRotation() {
		return ownRotation;
	}

	public Authorization getAuthorization() {
		return authorization;
	}

	public int getRepairAttempts() {
		return repairAttempts;
	}

	public long getOwnGeneration() {
		return ownGeneration;
	}

	public long getContactGeneration() {
		return contactGeneration;
	}
}
