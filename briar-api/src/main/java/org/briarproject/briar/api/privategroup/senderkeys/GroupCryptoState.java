package org.briarproject.briar.api.privategroup.senderkeys;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Cryptographic state metadata for a group.
 * <p>
 * Tracks the current encryption mode, last rekey time, and minimum
 * capability level of all members.
 */
@Immutable
@NotNullByDefault
public class GroupCryptoState {

	private final GroupId groupId;
	private final GroupCryptoMode cryptoMode;
	private final long lastRekeyTime;
	@Nullable
	private final RekeyReason rekeyReason;
	private final int minCapability;

	public GroupCryptoState(
			GroupId groupId,
			GroupCryptoMode cryptoMode,
			long lastRekeyTime,
			@Nullable RekeyReason rekeyReason,
			int minCapability
	) {
		this.groupId = groupId;
		this.cryptoMode = cryptoMode;
		this.lastRekeyTime = lastRekeyTime;
		this.rekeyReason = rekeyReason;
		this.minCapability = minCapability;
	}

	public GroupId getGroupId() {
		return groupId;
	}

	public GroupCryptoMode getCryptoMode() {
		return cryptoMode;
	}

	public long getLastRekeyTime() {
		return lastRekeyTime;
	}

	@Nullable
	public RekeyReason getRekeyReason() {
		return rekeyReason;
	}

	/**
	 * Returns the minimum capability level across all group members.
	 */
	public int getMinCapability() {
		return minCapability;
	}

	/**
	 * Returns a new state with updated crypto mode.
	 */
	public GroupCryptoState withCryptoMode(GroupCryptoMode newMode) {
		return new GroupCryptoState(
				groupId, newMode, lastRekeyTime, rekeyReason, minCapability
		);
	}

	/**
	 * Returns a new state after a rekey operation.
	 */
	public GroupCryptoState withRekey(long rekeyTime, RekeyReason reason) {
		return new GroupCryptoState(
				groupId, cryptoMode, rekeyTime, reason, minCapability
		);
	}

	/**
	 * Returns a new state with updated minimum capability.
	 */
	public GroupCryptoState withMinCapability(int capability) {
		return new GroupCryptoState(
				groupId, cryptoMode, lastRekeyTime, rekeyReason, capability
		);
	}

	/**
	 * Reason for the last rekey operation.
	 */
	public enum RekeyReason {
		/**
		 * Member joined the group.
		 */
		JOIN(1),

		/**
		 * Member left the group voluntarily.
		 */
		LEAVE(2),

		/**
		 * Member was kicked from the group.
		 */
		KICK(3),

		/**
		 * Periodic epoch rotation.
		 */
		EPOCH(4);

		private final int value;

		RekeyReason(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}

		public static RekeyReason fromValue(int value) {
			for (RekeyReason reason : values()) {
				if (reason.value == value) return reason;
			}
			throw new IllegalArgumentException("Unknown RekeyReason: " + value);
		}
	}
}
