package org.briarproject.briar.privategroup.senderkeys;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.privategroup.senderkeys.CapabilityManager;
import org.briarproject.briar.api.privategroup.senderkeys.GroupCapability;
import org.briarproject.briar.api.privategroup.senderkeys.GroupCryptoMode;
import org.briarproject.briar.api.privategroup.senderkeys.GroupCryptoState;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKey;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyDistributor;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

/**
 * Manages capability negotiation for group encryption features.
 * <p>
 * Contact capabilities are persisted in the contactCapabilities table
 * and used to determine the appropriate encryption mode for each group.
 * Capability negotiation follows the principle of minimum common capability:
 * a group can only use features supported by ALL members.
 */
@Immutable
@NotNullByDefault
public class CapabilityManagerImpl implements CapabilityManager {

	private final Clock clock;
	private final ContactManager contactManager;
	private final IdentityManager identityManager;
	private final SenderKeyManager senderKeyManager;
	private final SenderKeyDistributor senderKeyDistributor;

	@Inject
	public CapabilityManagerImpl(
			Clock clock,
			ContactManager contactManager,
			IdentityManager identityManager,
			SenderKeyManager senderKeyManager,
			SenderKeyDistributor senderKeyDistributor
	) {
		this.clock = clock;
		this.contactManager = contactManager;
		this.identityManager = identityManager;
		this.senderKeyManager = senderKeyManager;
		this.senderKeyDistributor = senderKeyDistributor;
	}

	@Override
	public int getLocalCapability() {
		return GroupCapability.FULL_CAPABILITY;
	}

	@Override
	public void storeContactCapability(Transaction txn, ContactId contactId, int capability)
			throws DbException {
		Connection conn = getConnection(txn);
		String sql = "INSERT OR REPLACE INTO contactCapabilities "
				+ "(contactId, capability, advertisedAt) VALUES (?, ?, ?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, contactId.getInt());
			ps.setInt(2, capability);
			ps.setLong(3, clock.currentTimeMillis());
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	public int getContactCapability(Transaction txn, ContactId contactId)
			throws DbException {
		Connection conn = getConnection(txn);
		String sql = "SELECT capability FROM contactCapabilities WHERE contactId = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, contactId.getInt());
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) {
					return 0;
				}
				return rs.getInt(1);
			}
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	public GroupCryptoMode determineGroupMode(Transaction txn, GroupId groupId)
			throws DbException {
		Map<AuthorId, Integer> capabilities = getGroupMemberCapabilities(txn, groupId);

		if (capabilities.isEmpty()) {
			return GroupCryptoMode.NONE;
		}

		int minCapability = GroupCapability.FULL_CAPABILITY;
		for (int cap : capabilities.values()) {
			minCapability = GroupCapability.minimumCommon(minCapability, cap);
		}

		if (!GroupCapability.hasSenderKeys(minCapability)) {
			return GroupCryptoMode.NONE;
		}

		return GroupCryptoMode.SENDER_KEYS;
	}

	@Override
	public Map<AuthorId, Integer> getGroupMemberCapabilities(Transaction txn, GroupId groupId)
			throws DbException {
		Map<AuthorId, SenderKey> members = senderKeyManager.getAllSenderKeys(txn, groupId);
		Map<AuthorId, Integer> capabilities = new HashMap<>();

		AuthorId localAuthorId = identityManager.getLocalAuthor(txn).getId();
		capabilities.put(localAuthorId, getLocalCapability());

		for (AuthorId authorId : members.keySet()) {
			if (!authorId.equals(localAuthorId)) {
				try {
					Contact contact = contactManager.getContact(txn, authorId, localAuthorId);
					int capability = getContactCapability(txn, contact.getId());
					capabilities.put(authorId, capability);
				} catch (DbException e) {
					capabilities.put(authorId, 0);
				}
			}
		}

		return capabilities;
	}

	@Override
	public boolean canUpgradeToSenderKeys(Transaction txn, GroupId groupId)
			throws DbException {
		GroupCryptoState state = senderKeyManager.getGroupCryptoState(txn, groupId);
		if (state != null && state.getCryptoMode() == GroupCryptoMode.SENDER_KEYS) {
			return false;
		}

		GroupCryptoMode mode = determineGroupMode(txn, groupId);
		return mode == GroupCryptoMode.SENDER_KEYS;
	}

	@Override
	public void initiateUpgrade(Transaction txn, GroupId groupId)
			throws DbException {
		if (!canUpgradeToSenderKeys(txn, groupId)) {
			throw new DbException();
		}

		SenderKey localKey = senderKeyManager.generateSenderKey(txn, groupId);

		GroupCryptoState state = senderKeyManager.getGroupCryptoState(txn, groupId);
		if (state != null) {
			senderKeyManager.updateGroupCryptoState(txn,
					new GroupCryptoState(
							groupId,
							GroupCryptoMode.SENDER_KEYS,
							state.getLastRekeyTime(),
							null,
							GroupCapability.SENDER_KEYS_V1
					));
		} else {
			senderKeyManager.initializeGroupCryptoState(txn, groupId,
					GroupCryptoMode.SENDER_KEYS, GroupCapability.SENDER_KEYS_V1);
		}

		senderKeyDistributor.distributeSenderKey(txn, groupId, localKey);
	}

	@Override
	public void handleCapabilityMismatch(
			Transaction txn,
			GroupId groupId,
			AuthorId memberId,
			int memberCapability
	) throws DbException {
		GroupCryptoState state = senderKeyManager.getGroupCryptoState(txn, groupId);
		if (state == null) {
			return;
		}

		if (!GroupCapability.hasSenderKeys(memberCapability) &&
				state.getCryptoMode() == GroupCryptoMode.SENDER_KEYS) {
			senderKeyManager.updateGroupCryptoState(txn,
					new GroupCryptoState(
							groupId,
							GroupCryptoMode.DEGRADED,
							state.getLastRekeyTime(),
							state.getRekeyReason(),
							memberCapability
					));
		}
	}

	private Connection getConnection(Transaction txn) {
		return (Connection) txn.unbox();
	}
}
