package org.briarproject.briar.privategroup.senderkeys;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.api.privategroup.senderkeys.GroupCapability;
import org.briarproject.briar.api.privategroup.senderkeys.GroupCryptoMode;
import org.briarproject.briar.api.privategroup.senderkeys.GroupCryptoState;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKey;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyManager;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.briar.api.privategroup.senderkeys.SenderKeyManager.EPOCH_MESSAGE_THRESHOLD;
import static org.briarproject.briar.api.privategroup.senderkeys.SenderKeyManager.EPOCH_TIME_THRESHOLD_MS;

@Immutable
@NotNullByDefault
public class SenderKeyManagerImpl implements SenderKeyManager {

	private final CryptoComponent crypto;
	private final DatabaseComponent db;
	private final IdentityManager identityManager;
	private final Clock clock;

	@Inject
	public SenderKeyManagerImpl(
			CryptoComponent crypto,
			DatabaseComponent db,
			IdentityManager identityManager,
			Clock clock
	) {
		this.crypto = crypto;
		this.db = db;
		this.identityManager = identityManager;
		this.clock = clock;
	}

	@Override
	public SenderKey generateSenderKey(Transaction txn, GroupId groupId)
			throws DbException {
		AuthorId localAuthorId = identityManager.getLocalAuthor(txn).getId();
		byte[] randomBytes = new byte[32];
		crypto.getSecureRandom().nextBytes(randomBytes);

		byte[] salt = new byte[groupId.getBytes().length + localAuthorId.getBytes().length];
		System.arraycopy(groupId.getBytes(), 0, salt, 0, groupId.getBytes().length);
		System.arraycopy(localAuthorId.getBytes(), 0, salt, groupId.getBytes().length,
				localAuthorId.getBytes().length);

		SecretKey chainKey = crypto.deriveKey(SENDER_KEY_INIT_LABEL,
				new SecretKey(randomBytes), salt);

		long now = clock.currentTimeMillis();
		SenderKey senderKey = new SenderKey(
				groupId,
				localAuthorId,
				chainKey,
				0,
				0,
				now,
				true,
				SenderKeyState.ACTIVE
		);

		storeSenderKey(txn, senderKey);
		return senderKey;
	}

	@Override
	public void storeSenderKey(Transaction txn, SenderKey senderKey)
			throws DbException {
		Connection conn = getConnection(txn);
		String sql = "INSERT OR REPLACE INTO groupSenderKeys "
				+ "(groupId, authorId, chainKey, epoch, messageIndex, createdAt, isLocal, state) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setBytes(1, senderKey.getGroupId().getBytes());
			ps.setBytes(2, senderKey.getAuthorId().getBytes());
			ps.setBytes(3, senderKey.getChainKey().getBytes());
			ps.setInt(4, senderKey.getEpoch());
			ps.setInt(5, senderKey.getMessageIndex());
			ps.setLong(6, senderKey.getCreatedAt());
			ps.setInt(7, senderKey.isLocal() ? 1 : 0);
			ps.setInt(8, senderKey.getState().getValue());
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	@Nullable
	public SenderKey getLocalSenderKey(Transaction txn, GroupId groupId)
			throws DbException {
		AuthorId localAuthorId = identityManager.getLocalAuthor(txn).getId();
		return getSenderKey(txn, groupId, localAuthorId);
	}

	@Override
	@Nullable
	public SenderKey getSenderKey(Transaction txn, GroupId groupId, AuthorId authorId)
			throws DbException {
		Connection conn = getConnection(txn);
		String sql = "SELECT chainKey, epoch, messageIndex, createdAt, isLocal, state "
				+ "FROM groupSenderKeys WHERE groupId = ? AND authorId = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setBytes(1, groupId.getBytes());
			ps.setBytes(2, authorId.getBytes());
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return null;
				return new SenderKey(
						groupId,
						authorId,
						new SecretKey(rs.getBytes(1)),
						rs.getInt(2),
						rs.getInt(3),
						rs.getLong(4),
						rs.getInt(5) == 1,
						SenderKeyState.fromValue(rs.getInt(6))
				);
			}
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	public Map<AuthorId, SenderKey> getAllSenderKeys(Transaction txn, GroupId groupId)
			throws DbException {
		Connection conn = getConnection(txn);
		String sql = "SELECT authorId, chainKey, epoch, messageIndex, createdAt, isLocal, state "
				+ "FROM groupSenderKeys WHERE groupId = ?";
		Map<AuthorId, SenderKey> keys = new HashMap<>();
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setBytes(1, groupId.getBytes());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					AuthorId authorId = new AuthorId(rs.getBytes(1));
					SenderKey key = new SenderKey(
							groupId,
							authorId,
							new SecretKey(rs.getBytes(2)),
							rs.getInt(3),
							rs.getInt(4),
							rs.getLong(5),
							rs.getInt(6) == 1,
							SenderKeyState.fromValue(rs.getInt(7))
					);
					keys.put(authorId, key);
				}
			}
		} catch (SQLException e) {
			throw new DbException(e);
		}
		return keys;
	}

	@Override
	public void updateSenderKey(Transaction txn, SenderKey senderKey)
			throws DbException {
		storeSenderKey(txn, senderKey);
	}

	@Override
	public void revokeSenderKey(Transaction txn, GroupId groupId, AuthorId authorId)
			throws DbException {
		Connection conn = getConnection(txn);
		String sql = "UPDATE groupSenderKeys SET state = ? WHERE groupId = ? AND authorId = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, SenderKeyState.REVOKED.getValue());
			ps.setBytes(2, groupId.getBytes());
			ps.setBytes(3, authorId.getBytes());
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void cacheMessageKey(
			Transaction txn,
			GroupId groupId,
			AuthorId authorId,
			int epoch,
			int messageIndex,
			byte[] messageKey
	) throws DbException {
		Connection conn = getConnection(txn);
		long expiresAt = clock.currentTimeMillis() + REVOKED_KEY_GRACE_PERIOD_MS;
		String sql = "INSERT OR REPLACE INTO groupKeyHistory "
				+ "(groupId, authorId, epoch, messageIndex, messageKey, expiresAt) "
				+ "VALUES (?, ?, ?, ?, ?, ?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setBytes(1, groupId.getBytes());
			ps.setBytes(2, authorId.getBytes());
			ps.setInt(3, epoch);
			ps.setInt(4, messageIndex);
			ps.setBytes(5, messageKey);
			ps.setLong(6, expiresAt);
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	@Nullable
	public byte[] getCachedMessageKey(
			Transaction txn,
			GroupId groupId,
			AuthorId authorId,
			int epoch,
			int messageIndex
	) throws DbException {
		Connection conn = getConnection(txn);
		String sql = "SELECT messageKey FROM groupKeyHistory "
				+ "WHERE groupId = ? AND authorId = ? AND epoch = ? AND messageIndex = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setBytes(1, groupId.getBytes());
			ps.setBytes(2, authorId.getBytes());
			ps.setInt(3, epoch);
			ps.setInt(4, messageIndex);
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return null;
				return rs.getBytes(1);
			}
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void removeCachedMessageKey(
			Transaction txn,
			GroupId groupId,
			AuthorId authorId,
			int epoch,
			int messageIndex
	) throws DbException {
		Connection conn = getConnection(txn);
		String sql = "DELETE FROM groupKeyHistory "
				+ "WHERE groupId = ? AND authorId = ? AND epoch = ? AND messageIndex = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setBytes(1, groupId.getBytes());
			ps.setBytes(2, authorId.getBytes());
			ps.setInt(3, epoch);
			ps.setInt(4, messageIndex);
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void cleanupExpiredMessageKeys(Transaction txn, long now)
			throws DbException {
		Connection conn = getConnection(txn);
		String sql = "DELETE FROM groupKeyHistory WHERE expiresAt < ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setLong(1, now);
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	@Nullable
	public GroupCryptoState getGroupCryptoState(Transaction txn, GroupId groupId)
			throws DbException {
		Connection conn = getConnection(txn);
		String sql = "SELECT cryptoMode, lastRekeyTime, rekeyReason, minCapability "
				+ "FROM groupCryptoState WHERE groupId = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setBytes(1, groupId.getBytes());
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return null;
				int reasonValue = rs.getInt(3);
				GroupCryptoState.RekeyReason reason = rs.wasNull() ? null :
						GroupCryptoState.RekeyReason.fromValue(reasonValue);
				return new GroupCryptoState(
						groupId,
						GroupCryptoMode.fromValue(rs.getInt(1)),
						rs.getLong(2),
						reason,
						rs.getInt(4)
				);
			}
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void initializeGroupCryptoState(
			Transaction txn,
			GroupId groupId,
			GroupCryptoMode mode,
			int capability
	) throws DbException {
		Connection conn = getConnection(txn);
		String sql = "INSERT INTO groupCryptoState "
				+ "(groupId, cryptoMode, lastRekeyTime, rekeyReason, minCapability) "
				+ "VALUES (?, ?, ?, NULL, ?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setBytes(1, groupId.getBytes());
			ps.setInt(2, mode.getValue());
			ps.setLong(3, clock.currentTimeMillis());
			ps.setInt(4, capability);
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void updateGroupCryptoState(Transaction txn, GroupCryptoState state)
			throws DbException {
		Connection conn = getConnection(txn);
		String sql = "UPDATE groupCryptoState "
				+ "SET cryptoMode = ?, lastRekeyTime = ?, rekeyReason = ?, minCapability = ? "
				+ "WHERE groupId = ?";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setInt(1, state.getCryptoMode().getValue());
			ps.setLong(2, state.getLastRekeyTime());
			if (state.getRekeyReason() != null) {
				ps.setInt(3, state.getRekeyReason().getValue());
			} else {
				ps.setNull(3, java.sql.Types.INTEGER);
			}
			ps.setInt(4, state.getMinCapability());
			ps.setBytes(5, state.getGroupId().getBytes());
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new DbException(e);
		}
	}

	@Override
	public SenderKey rekeyGroup(
			Transaction txn,
			GroupId groupId,
			GroupCryptoState.RekeyReason reason
	) throws DbException {
		SenderKey currentKey = getLocalSenderKey(txn, groupId);
		if (currentKey != null) {
			SenderKey rotatingKey = currentKey.withState(SenderKeyState.ROTATING);
			updateSenderKey(txn, rotatingKey);
		}

		SenderKey newKey = generateSenderKey(txn, groupId);

		GroupCryptoState state = getGroupCryptoState(txn, groupId);
		if (state != null) {
			updateGroupCryptoState(txn, state.withRekey(clock.currentTimeMillis(), reason));
		}

		return newKey;
	}

	@Override
	public boolean shouldRotateEpoch(SenderKey senderKey, long now) {
		if (senderKey.getMessageIndex() >= EPOCH_MESSAGE_THRESHOLD) {
			return true;
		}
		long timeSinceCreation = now - senderKey.getCreatedAt();
		return timeSinceCreation >= EPOCH_TIME_THRESHOLD_MS;
	}

	@Override
	public Collection<AuthorId> getMembersForDistribution(Transaction txn, GroupId groupId)
			throws DbException {
		AuthorId localAuthorId = identityManager.getLocalAuthor(txn).getId();
		Map<AuthorId, SenderKey> allKeys = getAllSenderKeys(txn, groupId);
		Collection<AuthorId> members = new ArrayList<>(allKeys.keySet());
		members.remove(localAuthorId);
		return members;
	}

	private Connection getConnection(Transaction txn) {
		return (Connection) txn.unbox();
	}
}
