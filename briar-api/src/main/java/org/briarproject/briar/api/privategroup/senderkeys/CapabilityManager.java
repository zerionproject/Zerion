package org.briarproject.briar.api.privategroup.senderkeys;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;

/**
 * Manages capability negotiation for group encryption features.
 */
@NotNullByDefault
public interface CapabilityManager {

	/**
	 * Returns the local user's full capability set.
	 */
	int getLocalCapability();

	/**
	 * Stores a contact's advertised capability.
	 *
	 * @param txn The database transaction
	 * @param contactId The contact
	 * @param capability The contact's capability bitmask
	 */
	void storeContactCapability(Transaction txn, ContactId contactId, int capability)
			throws DbException;

	/**
	 * Gets a contact's stored capability.
	 *
	 * @param txn The database transaction
	 * @param contactId The contact
	 * @return The capability bitmask, or 0 if not stored
	 */
	int getContactCapability(Transaction txn, ContactId contactId)
			throws DbException;

	/**
	 * Determines the crypto mode for a group based on member capabilities.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @return The appropriate GroupCryptoMode
	 */
	GroupCryptoMode determineGroupMode(Transaction txn, GroupId groupId)
			throws DbException;

	/**
	 * Gets all member capabilities for a group.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @return Map of AuthorId to capability bitmask
	 */
	Map<AuthorId, Integer> getGroupMemberCapabilities(Transaction txn, GroupId groupId)
			throws DbException;

	/**
	 * Checks if a group can be upgraded to SENDER_KEYS mode.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @return true if all members support SENDER_KEYS_V1
	 */
	boolean canUpgradeToSenderKeys(Transaction txn, GroupId groupId)
			throws DbException;

	/**
	 * Initiates upgrade of a group to SENDER_KEYS mode.
	 * Only the group creator can initiate an upgrade.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 */
	void initiateUpgrade(Transaction txn, GroupId groupId)
			throws DbException;

	/**
	 * Handles a capability downgrade warning when a new member
	 * has lower capability than the current group mode requires.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @param memberId The member with lower capability
	 * @param memberCapability The member's capability
	 */
	void handleCapabilityMismatch(
			Transaction txn,
			GroupId groupId,
			AuthorId memberId,
			int memberCapability
	) throws DbException;
}
