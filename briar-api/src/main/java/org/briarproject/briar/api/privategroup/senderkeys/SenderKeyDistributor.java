package org.briarproject.briar.api.privategroup.senderkeys;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

/**
 * Handles distribution of SenderKeys to group members via pairwise channels.
 */
@NotNullByDefault
public interface SenderKeyDistributor {

	/**
	 * Distributes the local user's SenderKey to all members of a group.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @param senderKey The SenderKey to distribute
	 */
	void distributeSenderKey(
			Transaction txn,
			GroupId groupId,
			SenderKey senderKey
	) throws DbException;

	/**
	 * Distributes the local user's SenderKey to a specific member.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @param senderKey The SenderKey to distribute
	 * @param contactId The contact to send to
	 */
	void distributeSenderKeyToMember(
			Transaction txn,
			GroupId groupId,
			SenderKey senderKey,
			ContactId contactId
	) throws DbException;

	/**
	 * Handles a received SenderKey distribution message.
	 *
	 * @param txn The database transaction
	 * @param senderContactId The contact who sent the distribution
	 * @param parsed The parsed distribution data
	 */
	void handleReceivedSenderKey(
			Transaction txn,
			ContactId senderContactId,
			SenderKeyDistributionFactory.ParsedSenderKeyDistribution parsed
	) throws DbException;

	/**
	 * Triggers a rekey for all members after a membership change.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @param reason The reason for rekeying
	 * @param affectedMember The member who joined/left (null for EPOCH)
	 */
	void triggerRekey(
			Transaction txn,
			GroupId groupId,
			RekeyRequest.Reason reason,
			AuthorId affectedMember
	) throws DbException;

	/**
	 * Gets all contacts who are members of a group.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @return Collection of ContactIds
	 */
	Collection<ContactId> getGroupMemberContacts(
			Transaction txn,
			GroupId groupId
	) throws DbException;
}
