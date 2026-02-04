package org.briarproject.briar.api.privategroup.senderkeys;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * A message containing a SenderKey distribution for group encryption.
 * Sent via pairwise channel to share sender keys with group members.
 */
@Immutable
@NotNullByDefault
public class SenderKeyDistribution {

	private final Message message;
	private final GroupId targetGroupId;
	private final SenderKey senderKey;
	private final byte[] signature;

	public SenderKeyDistribution(
			Message message,
			GroupId targetGroupId,
			SenderKey senderKey,
			byte[] signature
	) {
		this.message = message;
		this.targetGroupId = targetGroupId;
		this.senderKey = senderKey;
		this.signature = signature;
	}

	/**
	 * Returns the underlying Message for transport.
	 */
	public Message getMessage() {
		return message;
	}

	/**
	 * Returns the ID of the group this SenderKey belongs to.
	 */
	public GroupId getTargetGroupId() {
		return targetGroupId;
	}

	/**
	 * Returns the SenderKey being distributed.
	 */
	public SenderKey getSenderKey() {
		return senderKey;
	}

	/**
	 * Returns the signature over groupId || senderKey.
	 */
	public byte[] getSignature() {
		return signature;
	}
}
