package org.briarproject.briar.api.privategroup.senderkeys;

import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * A request to rekey a group, typically triggered by membership changes.
 * Sent via pairwise channel to notify group members.
 */
@Immutable
@NotNullByDefault
public class RekeyRequest {

	/**
	 * Reasons for rekeying a group.
	 */
	public enum Reason {
		JOIN(1),
		LEAVE(2),
		KICK(3),
		EPOCH(4);

		private final int value;

		Reason(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}

		public static Reason fromValue(int value) {
			for (Reason r : values()) {
				if (r.value == value) return r;
			}
			throw new IllegalArgumentException("Unknown reason: " + value);
		}
	}

	private final Message message;
	private final GroupId targetGroupId;
	private final Reason reason;
	@Nullable
	private final AuthorId affectedMember;
	private final SenderKey newSenderKey;
	private final byte[] signature;

	public RekeyRequest(
			Message message,
			GroupId targetGroupId,
			Reason reason,
			@Nullable AuthorId affectedMember,
			SenderKey newSenderKey,
			byte[] signature
	) {
		this.message = message;
		this.targetGroupId = targetGroupId;
		this.reason = reason;
		this.affectedMember = affectedMember;
		this.newSenderKey = newSenderKey;
		this.signature = signature;
	}

	public Message getMessage() {
		return message;
	}

	public GroupId getTargetGroupId() {
		return targetGroupId;
	}

	public Reason getReason() {
		return reason;
	}

	/**
	 * Returns the member who joined/left/was kicked, or null for EPOCH reason.
	 */
	@Nullable
	public AuthorId getAffectedMember() {
		return affectedMember;
	}

	/**
	 * Returns the requester's new SenderKey.
	 */
	public SenderKey getNewSenderKey() {
		return newSenderKey;
	}

	public byte[] getSignature() {
		return signature;
	}
}
