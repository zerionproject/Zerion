package org.briarproject.briar.api.privategroup.senderkeys;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Factory for creating SenderKey distribution messages.
 */
@NotNullByDefault
public interface SenderKeyDistributionFactory {

	/**
	 * Creates a SenderKeyDistribution message for the given pairwise conversation.
	 *
	 * @param conversationGroupId The GroupId of the pairwise conversation
	 * @param targetGroupId The GroupId of the private group
	 * @param senderKey The SenderKey to distribute
	 * @param privateKey The sender's signing private key
	 * @return The SenderKeyDistribution message ready for transport
	 */
	SenderKeyDistribution createSenderKeyDistribution(
			GroupId conversationGroupId,
			GroupId targetGroupId,
			SenderKey senderKey,
			PrivateKey privateKey
	) throws FormatException;

	/**
	 * Parses a received SenderKeyDistribution message.
	 *
	 * @param body The message body bytes
	 * @return The parsed SenderKeyDistribution data
	 */
	ParsedSenderKeyDistribution parseSenderKeyDistribution(byte[] body)
			throws FormatException;

	/**
	 * Parsed data from a received SenderKeyDistribution message.
	 */
	interface ParsedSenderKeyDistribution {
		GroupId getTargetGroupId();
		byte[] getChainKey();
		int getEpoch();
		int getMessageIndex();
		long getCreatedAt();
		byte[] getAuthorId();
		byte[] getSignature();
	}
}
