package org.zerionproject.core.db;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.AgreementPrivateKey;
import org.zerionproject.core.api.contact.HandshakeLinkConstants;
import org.zerionproject.core.api.crypto.AgreementPublicKey;
import org.zerionproject.core.api.crypto.HybridCommitmentPublicKey;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.db.DataTooNewException;
import org.zerionproject.core.api.db.DataTooOldException;
import org.zerionproject.core.api.db.DbClosedException;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.MessageDeletedException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.MigrationListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Group.Visibility;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.MessageStatus;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.transport.IncomingKeys;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.transport.OutgoingKeys;
import org.zerionproject.core.api.transport.TransportKeySet;
import org.zerionproject.core.api.transport.TransportKeys;
import org.briarproject.nullsafety.NotNullByDefault;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import static java.sql.Types.BINARY;
import static java.sql.Types.BOOLEAN;
import static java.sql.Types.INTEGER;
import static java.sql.Types.VARCHAR;
import static java.util.Arrays.asList;
import static org.zerionproject.core.api.db.DatabaseComponent.NO_CLEANUP_DEADLINE;
import static org.zerionproject.core.api.db.DatabaseComponent.TIMER_NOT_STARTED;
import static org.zerionproject.core.api.db.Metadata.REMOVE;
import static org.zerionproject.core.api.record.Record.RECORD_HEADER_BYTES;
import static org.zerionproject.core.api.sync.Group.Visibility.INVISIBLE;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.api.sync.Group.Visibility.VISIBLE;
import static org.zerionproject.core.api.sync.SyncConstants.MESSAGE_HEADER_LENGTH;
import static org.zerionproject.core.api.sync.validation.MessageState.DELIVERED;
import static org.zerionproject.core.api.sync.validation.MessageState.PENDING;
import static org.zerionproject.core.api.sync.validation.MessageState.UNKNOWN;
import static org.zerionproject.core.db.DatabaseConstants.DB_SETTINGS_NAMESPACE;
import static org.zerionproject.core.db.DatabaseConstants.DIRTY_KEY;
import static org.zerionproject.core.db.DatabaseConstants.SCHEMA_VERSION_KEY;
import static org.zerionproject.core.db.ExponentialBackoff.calculateExpiry;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

@NotNullByDefault
abstract class JdbcDatabase implements Database<Connection> {

	static final int CODE_SCHEMA_VERSION = 66;

	private static final int MAX_CONNECTION_POOL_SIZE = 8;
	private static final int OFFSET_PREV = -1;
	private static final int OFFSET_CURR = 0;
	private static final int OFFSET_NEXT = 1;

	private static final String CREATE_SETTINGS =
			"CREATE TABLE settings"
					+ " (namespace _STRING NOT NULL,"
					+ " settingKey _STRING NOT NULL,"
					+ " value _STRING NOT NULL,"
					+ " PRIMARY KEY (namespace, settingKey))";

	private static final String CREATE_LOCAL_AUTHORS =
			"CREATE TABLE localAuthors"
					+ " (authorId _HASH NOT NULL,"
					+ " formatVersion INT NOT NULL,"
					+ " name _STRING NOT NULL,"
					+ " publicKey _BINARY NOT NULL,"
					+ " privateKey _BINARY NOT NULL,"
					+ " handshakePublicKey _BINARY,"
					+ " handshakePrivateKey _BINARY,"
					+ " hybridHandshakePublicKey _BINARY,"
					+ " hybridHandshakePrivateKey _BINARY,"
					+ " mlDsaSigPublicKey _BINARY,"
					+ " mlDsaSigPrivateKey _BINARY,"
					+ " created BIGINT NOT NULL,"
					+ " PRIMARY KEY (authorId))";

	private static final String CREATE_CONTACTS =
			"CREATE TABLE contacts"
					+ " (contactId _COUNTER,"
					+ " authorId _HASH NOT NULL,"
					+ " formatVersion INT NOT NULL,"
					+ " name _STRING NOT NULL,"
					+ " alias _STRING,"
					+ " publicKey _BINARY NOT NULL,"
					+ " handshakePublicKey _BINARY,"
					+ " localAuthorId _HASH NOT NULL,"
					+ " verified BOOLEAN NOT NULL,"
					+ " postQuantum BOOLEAN DEFAULT FALSE NOT NULL,"
					+ " pcsEnabled BOOLEAN DEFAULT FALSE NOT NULL,"
					+ " mlDsaSigPublicKey _BINARY,"
					+ " syncVersions _BINARY DEFAULT '00' NOT NULL,"
					+ " PRIMARY KEY (contactId),"
					+ " FOREIGN KEY (localAuthorId)"
					+ " REFERENCES localAuthors (authorId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_CONTACT_CAPABILITIES =
			"CREATE TABLE contactCapabilities"
					+ " (contactId INT NOT NULL PRIMARY KEY,"
					+ " capability INTEGER NOT NULL,"
					+ " advertisedAt BIGINT NOT NULL)";

	private static final String CREATE_GROUPS =
			"CREATE TABLE groups"
					+ " (groupId _HASH NOT NULL,"
					+ " clientId _STRING NOT NULL,"
					+ " majorVersion INT NOT NULL,"
					+ " descriptor _BINARY NOT NULL,"
					+ " PRIMARY KEY (groupId))";

	private static final String CREATE_GROUP_METADATA =
			"CREATE TABLE groupMetadata"
					+ " (groupId _HASH NOT NULL,"
					+ " metaKey _STRING NOT NULL,"
					+ " value _BINARY NOT NULL,"
					+ " PRIMARY KEY (groupId, metaKey),"
					+ " FOREIGN KEY (groupId)"
					+ " REFERENCES groups (groupId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_GROUP_VISIBILITIES =
			"CREATE TABLE groupVisibilities"
					+ " (contactId INT NOT NULL,"
					+ " groupId _HASH NOT NULL,"
					+ " shared BOOLEAN NOT NULL,"
					+ " PRIMARY KEY (contactId, groupId),"
					+ " FOREIGN KEY (contactId)"
					+ " REFERENCES contacts (contactId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (groupId)"
					+ " REFERENCES groups (groupId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_MESSAGES =
			"CREATE TABLE messages"
					+ " (messageId _HASH NOT NULL,"
					+ " groupId _HASH NOT NULL,"
					+ " timestamp BIGINT NOT NULL,"
					+ " state INT NOT NULL,"
					+ " shared BOOLEAN NOT NULL,"
					+ " temporary BOOLEAN NOT NULL,"
					+ " cleanupTimerDuration BIGINT,"
					+ " cleanupDeadline BIGINT,"
					+ " length INT NOT NULL,"
					+ " raw BLOB,"
					+ " PRIMARY KEY (messageId),"
					+ " FOREIGN KEY (groupId)"
					+ " REFERENCES groups (groupId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_MESSAGE_METADATA =
			"CREATE TABLE messageMetadata"
					+ " (messageId _HASH NOT NULL,"
					+ " groupId _HASH NOT NULL,"
					+ " state INT NOT NULL,"
					+ " metaKey _STRING NOT NULL,"
					+ " value _BINARY NOT NULL,"
					+ " PRIMARY KEY (messageId, metaKey),"
					+ " FOREIGN KEY (messageId)"
					+ " REFERENCES messages (messageId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (groupId)"
					+ " REFERENCES groups (groupId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_MESSAGE_DEPENDENCIES =
			"CREATE TABLE messageDependencies"
					+ " (groupId _HASH NOT NULL,"
					+ " messageId _HASH NOT NULL,"
					+ " dependencyId _HASH NOT NULL,"
					+ " messageState INT NOT NULL,"
					+ " dependencyState INT,"
					+ " FOREIGN KEY (groupId)"
					+ " REFERENCES groups (groupId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (messageId)"
					+ " REFERENCES messages (messageId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_OFFERS =
			"CREATE TABLE offers"
					+ " (messageId _HASH NOT NULL,"
					+ " contactId INT NOT NULL,"
					+ " PRIMARY KEY (messageId, contactId),"
					+ " FOREIGN KEY (contactId)"
					+ " REFERENCES contacts (contactId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_STATUSES =
			"CREATE TABLE statuses"
					+ " (messageId _HASH NOT NULL,"
					+ " contactId INT NOT NULL,"
					+ " groupId _HASH NOT NULL,"
					+ " timestamp BIGINT NOT NULL,"
					+ " length INT NOT NULL,"
					+ " state INT NOT NULL,"
					+ " groupShared BOOLEAN NOT NULL,"
					+ " messageShared BOOLEAN NOT NULL,"
					+ " deleted BOOLEAN NOT NULL,"
					+ " ack BOOLEAN NOT NULL,"
					+ " seen BOOLEAN NOT NULL,"
					+ " requested BOOLEAN NOT NULL,"
					+ " expiry BIGINT NOT NULL,"
					+ " txCount INT NOT NULL,"
					+ " maxLatency BIGINT,"
					+ " PRIMARY KEY (messageId, contactId),"
					+ " FOREIGN KEY (messageId)"
					+ " REFERENCES messages (messageId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (contactId)"
					+ " REFERENCES contacts (contactId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (groupId)"
					+ " REFERENCES groups (groupId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_TRANSPORTS =
			"CREATE TABLE transports"
					+ " (transportId _STRING NOT NULL,"
					+ " maxLatency BIGINT NOT NULL,"
					+ " PRIMARY KEY (transportId))";

	private static final String CREATE_PENDING_CONTACTS =
			"CREATE TABLE pendingContacts"
					+ " (pendingContactId _HASH NOT NULL,"
					+ " publicKey _BINARY NOT NULL,"
					+ " alias _STRING NOT NULL,"
					+ " timestamp BIGINT NOT NULL,"
					+ " formatVersion INT DEFAULT 0 NOT NULL,"
					+ " PRIMARY KEY (pendingContactId))";

	private static final String CREATE_OUTGOING_KEYS =
			"CREATE TABLE outgoingKeys"
					+ " (transportId _STRING NOT NULL,"
					+ " keySetId _COUNTER,"
					+ " timePeriod BIGINT NOT NULL,"
					+ " contactId INT,"
					+ " pendingContactId _HASH,"
					+ " tagKey _SECRET NOT NULL,"
					+ " headerKey _SECRET NOT NULL,"
					+ " stream BIGINT NOT NULL,"
					+ " active BOOLEAN NOT NULL,"
					+ " rootKey _SECRET,"
					+ " alice BOOLEAN,"
					+ " PRIMARY KEY (transportId, keySetId),"
					+ " FOREIGN KEY (transportId)"
					+ " REFERENCES transports (transportId)"
					+ " ON DELETE CASCADE,"
					+ " UNIQUE (keySetId),"
					+ " FOREIGN KEY (contactId)"
					+ " REFERENCES contacts (contactId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (pendingContactId)"
					+ " REFERENCES pendingContacts (pendingContactId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_INCOMING_KEYS =
			"CREATE TABLE incomingKeys"
					+ " (transportId _STRING NOT NULL,"
					+ " keySetId INT NOT NULL,"
					+ " timePeriod BIGINT NOT NULL,"
					+ " tagKey _SECRET NOT NULL,"
					+ " headerKey _SECRET NOT NULL,"
					+ " base BIGINT NOT NULL,"
					+ " bitmap _BINARY NOT NULL,"
					+ " periodOffset INT NOT NULL,"
					+ " PRIMARY KEY (transportId, keySetId, periodOffset),"
					+ " FOREIGN KEY (transportId)"
					+ " REFERENCES transports (transportId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (keySetId)"
					+ " REFERENCES outgoingKeys (keySetId)"
					+ " ON DELETE CASCADE)";
	private static final String CREATE_PCS_SESSION_STATE =
			"CREATE TABLE pcsSessionState"
					+ " (contactId INT NOT NULL,"
					+ " direction SMALLINT NOT NULL,"
					+ " chainKey _SECRET NOT NULL,"
					+ " messageNumber INT NOT NULL,"
					+ " previousChainLength INT NOT NULL,"
					+ " mode2Enabled BOOLEAN DEFAULT FALSE NOT NULL,"
					+ " rootKey _SECRET,"
					+ " dhPrivateKey _SECRET,"
					+ " dhPublicKey _BINARY,"
					+ " dhRemotePublicKey _BINARY,"
					+ " mode3Enabled BOOLEAN DEFAULT FALSE NOT NULL,"
					+ " pqEpoch BIGINT DEFAULT 0 NOT NULL,"
					+ " mode3FullStateBlob _BINARY,"
					+ " PRIMARY KEY (contactId, direction),"
					+ " FOREIGN KEY (contactId)"
					+ " REFERENCES contacts (contactId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_PCS_SKIPPED_KEYS =
			"CREATE TABLE pcsSkippedKeys"
					+ " (contactId INT NOT NULL,"
					+ " direction SMALLINT NOT NULL,"
					+ " messageNumber INT NOT NULL,"
					+ " messageKey _SECRET NOT NULL,"
					+ " timestamp BIGINT NOT NULL,"
					+ " chainId _HASH,"
					+ " PRIMARY KEY (contactId, direction, messageNumber),"
					+ " FOREIGN KEY (contactId)"
					+ " REFERENCES contacts (contactId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_PQ_RATCHET_STATE =
			"CREATE TABLE pqRatchetState"
					+ " (contactId INT NOT NULL,"
					+ " currentEpoch BIGINT DEFAULT 0 NOT NULL,"
					+ " epochStartTime BIGINT NOT NULL,"
					+ " messagesSinceEpoch INT DEFAULT 0 NOT NULL,"
					+ " state INT DEFAULT 0 NOT NULL,"
					+ " isInitiator BOOLEAN DEFAULT FALSE NOT NULL,"
					+ " chunksSent INT DEFAULT 0 NOT NULL,"
					+ " chunksReceived INT DEFAULT 0 NOT NULL,"
					+ " ourEkSeed _BINARY,"
					+ " ourEkVector _BINARY,"
					+ " ourDecapsKey _SECRET,"
					+ " theirEkSeed _BINARY,"
					+ " theirEkHash _BINARY,"
					+ " theirEkVector _BINARY,"
					+ " ciphertext _BINARY,"
					+ " pendingChunks _BINARY,"
					+ " PRIMARY KEY (contactId),"
					+ " FOREIGN KEY (contactId)"
					+ " REFERENCES contacts (contactId)"
					+ " ON DELETE CASCADE)";

	private static final String CREATE_GROUP_SENDER_KEYS =
			"CREATE TABLE groupSenderKeys"
					+ " (groupId _HASH NOT NULL,"
					+ " authorId _HASH NOT NULL,"
					+ " chainKey _SECRET NOT NULL,"
					+ " epoch INTEGER NOT NULL,"
					+ " messageIndex INTEGER NOT NULL,"
					+ " createdAt BIGINT NOT NULL,"
					+ " isLocal INTEGER NOT NULL,"
					+ " state INTEGER NOT NULL,"
					+ " PRIMARY KEY (groupId, authorId))";

	private static final String CREATE_GROUP_KEY_HISTORY =
			"CREATE TABLE groupKeyHistory"
					+ " (groupId _HASH NOT NULL,"
					+ " authorId _HASH NOT NULL,"
					+ " epoch INTEGER NOT NULL,"
					+ " messageIndex INTEGER NOT NULL,"
					+ " messageKey _SECRET NOT NULL,"
					+ " expiresAt BIGINT NOT NULL,"
					+ " PRIMARY KEY (groupId, authorId, epoch, messageIndex))";

	private static final String CREATE_GROUP_CRYPTO_STATE =
			"CREATE TABLE groupCryptoState"
					+ " (groupId _HASH NOT NULL PRIMARY KEY,"
					+ " cryptoMode INTEGER NOT NULL,"
					+ " lastRekeyTime BIGINT NOT NULL,"
					+ " rekeyReason INTEGER,"
					+ " minCapability INTEGER NOT NULL)";

	private static final String INDEX_GROUP_KEY_HISTORY_BY_EXPIRY =
			"CREATE INDEX IF NOT EXISTS groupKeyHistoryExpiry"
					+ " ON groupKeyHistory (expiresAt)";

	private static final String INDEX_GROUP_SENDER_KEYS_BY_GROUP =
			"CREATE INDEX IF NOT EXISTS groupSenderKeysByGroup"
					+ " ON groupSenderKeys (groupId)";

	private static final String INDEX_PCS_SKIPPED_KEYS_BY_TIMESTAMP =
			"CREATE INDEX IF NOT EXISTS pcsSkippedKeysByTimestamp"
					+ " ON pcsSkippedKeys (contactId, timestamp)";

	private static final String INDEX_PCS_SKIPPED_KEYS_BY_CHAIN_ID =
			"CREATE INDEX IF NOT EXISTS pcsSkippedKeysByChainId"
					+ " ON pcsSkippedKeys (chainId, messageNumber)";

	private static final String INDEX_CONTACTS_BY_AUTHOR_ID =
			"CREATE INDEX IF NOT EXISTS contactsByAuthorId"
					+ " ON contacts (authorId)";

	private static final String INDEX_GROUPS_BY_CLIENT_ID_MAJOR_VERSION =
			"CREATE INDEX IF NOT EXISTS groupsByClientIdMajorVersion"
					+ " ON groups (clientId, majorVersion)";

	private static final String INDEX_MESSAGE_METADATA_BY_GROUP_ID_STATE =
			"CREATE INDEX IF NOT EXISTS messageMetadataByGroupIdState"
					+ " ON messageMetadata (groupId, state)";

	private static final String INDEX_MESSAGE_DEPENDENCIES_BY_DEPENDENCY_ID =
			"CREATE INDEX IF NOT EXISTS messageDependenciesByDependencyId"
					+ " ON messageDependencies (dependencyId)";

	private static final String INDEX_STATUSES_BY_CONTACT_ID_GROUP_ID =
			"CREATE INDEX IF NOT EXISTS statusesByContactIdGroupId"
					+ " ON statuses (contactId, groupId)";

	private static final String INDEX_STATUSES_BY_CONTACT_ID_TIMESTAMP =
			"CREATE INDEX IF NOT EXISTS statusesByContactIdTimestamp"
					+ " ON statuses (contactId, timestamp)";

	private static final String
			INDEX_STATUSES_BY_CONTACT_ID_TX_COUNT_TIMESTAMP =
			"CREATE INDEX IF NOT EXISTS statusesByContactIdTxCountTimestamp"
					+ " ON statuses (contactId, txCount, timestamp)";

	private static final String INDEX_MESSAGES_BY_CLEANUP_DEADLINE =
			"CREATE INDEX IF NOT EXISTS messagesByCleanupDeadline"
					+ " ON messages (cleanupDeadline)";
	private static final String INDEX_MESSAGES_BY_TEMPORARY =
			"CREATE INDEX IF NOT EXISTS messagesByTemporary"
					+ " ON messages (temporary)";
	private final MessageFactory messageFactory;
	private final Clock clock;
	private final DatabaseTypes dbTypes;

	private final Lock connectionsLock = new ReentrantLock();
	private final Condition connectionsChanged = connectionsLock.newCondition();

	@GuardedBy("connectionsLock")
	private final LinkedList<Connection> connectionPool = new LinkedList<>();

	@GuardedBy("connectionsLock")
	private int openConnections = 0;
	@GuardedBy("connectionsLock")
	private boolean closed = false;

	private volatile boolean wasDirtyOnInitialisation = false;

	protected abstract Connection createConnection()
			throws DbException, SQLException;
	protected abstract void compactAndClose() throws DbException;

	JdbcDatabase(DatabaseTypes databaseTypes, MessageFactory messageFactory,
			Clock clock) {
		this.dbTypes = databaseTypes;
		this.messageFactory = messageFactory;
		this.clock = clock;
	}

	protected void open(String driverClass, boolean reopen,
			@SuppressWarnings("unused") SecretKey key,
			@Nullable MigrationListener listener) throws DbException {
		try {
			Class.forName(driverClass);
		} catch (ClassNotFoundException e) {
			throw new DbException(e);
		}
		connectionsLock.lock();
		try {
			closed = false;
		} finally {
			connectionsLock.unlock();
		}
		boolean compact;
		Connection txn = startTransaction();
		try {
			if (reopen) {
				Settings s = getSettings(txn, DB_SETTINGS_NAMESPACE);
				wasDirtyOnInitialisation = isDirty(s);
				boolean migrated = migrateSchema(txn, s, listener);
				compact = wasDirtyOnInitialisation || migrated;
			} else {
				wasDirtyOnInitialisation = false;
				createTables(txn);
				initialiseSettings(txn);
				compact = false;
			}
			createIndexes(txn);
			setDirty(txn, true);
			commitTransaction(txn);
		} catch (DbException e) {
			abortTransaction(txn);
			throw e;
		}
		if (compact) {
			if (listener != null) listener.onDatabaseCompaction();
			compactAndClose();
			connectionsLock.lock();
			try {
				closed = false;
			} finally {
				connectionsLock.unlock();
			}
		}
	}

	@Override
	public boolean wasDirtyOnInitialisation() {
		return wasDirtyOnInitialisation;
	}

	private boolean migrateSchema(Connection txn, Settings s,
			@Nullable MigrationListener listener) throws DbException {
		int dataSchemaVersion = s.getInt(SCHEMA_VERSION_KEY, -1);
		if (dataSchemaVersion == -1) throw new DbException();
		if (dataSchemaVersion == CODE_SCHEMA_VERSION) return false;
		if (CODE_SCHEMA_VERSION < dataSchemaVersion)
			throw new DataTooNewException();
		for (Migration<Connection> m : getMigrations()) {
			int start = m.getStartVersion(), end = m.getEndVersion();
			if (start == dataSchemaVersion) {
				if (listener != null) listener.onDatabaseMigration();
				m.migrate(txn);
				storeSchemaVersion(txn, end);
				dataSchemaVersion = end;
			}
		}
		if (dataSchemaVersion != CODE_SCHEMA_VERSION)
			throw new DataTooOldException();
		return true;
	}

	List<Migration<Connection>> getMigrations() {
		return asList(
				new Migration38_39(),
				new Migration39_40(),
				new Migration40_41(dbTypes),
				new Migration41_42(dbTypes),
				new Migration42_43(dbTypes),
				new Migration43_44(dbTypes),
				new Migration44_45(),
				new Migration45_46(),
				new Migration46_47(dbTypes),
				new Migration47_48(),
				new Migration48_49(),
				new Migration49_50(),
				new Migration50_51(),
				new Migration51_52(),
				new Migration52_53(),
				new Migration53_54(),
				new Migration54_55(dbTypes),
				new Migration55_56(),
				new Migration56_57(dbTypes),
				new Migration57_58(dbTypes),
				new Migration58_59(),
				new Migration59_60(),
				new Migration60_61(dbTypes),
				new Migration61_62(),
				new Migration62_63(),
				new Migration63_64(),
				new Migration64_65(dbTypes),
				new Migration65_66()
		);
	}

	private void storeSchemaVersion(Connection txn, int version)
			throws DbException {
		Settings s = new Settings();
		s.putInt(SCHEMA_VERSION_KEY, version);
		mergeSettings(txn, s, DB_SETTINGS_NAMESPACE);
	}

	private boolean isDirty(Settings s) {
		return s.getBoolean(DIRTY_KEY, false);
	}

	protected void setDirty(Connection txn, boolean dirty) throws DbException {
		Settings s = new Settings();
		s.putBoolean(DIRTY_KEY, dirty);
		mergeSettings(txn, s, DB_SETTINGS_NAMESPACE);
	}

	private void initialiseSettings(Connection txn) throws DbException {
		Settings s = new Settings();
		s.putInt(SCHEMA_VERSION_KEY, CODE_SCHEMA_VERSION);
		mergeSettings(txn, s, DB_SETTINGS_NAMESPACE);
	}

	private void createTables(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.executeUpdate(dbTypes.replaceTypes(CREATE_SETTINGS));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_LOCAL_AUTHORS));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_CONTACTS));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_CONTACT_CAPABILITIES));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_GROUPS));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_GROUP_METADATA));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_GROUP_VISIBILITIES));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_MESSAGES));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_MESSAGE_METADATA));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_MESSAGE_DEPENDENCIES));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_OFFERS));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_STATUSES));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_TRANSPORTS));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_PENDING_CONTACTS));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_OUTGOING_KEYS));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_INCOMING_KEYS));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_PCS_SESSION_STATE));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_PCS_SKIPPED_KEYS));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_PQ_RATCHET_STATE));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_GROUP_SENDER_KEYS));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_GROUP_KEY_HISTORY));
			s.executeUpdate(dbTypes.replaceTypes(CREATE_GROUP_CRYPTO_STATE));
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}

	private void createIndexes(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.executeUpdate(INDEX_CONTACTS_BY_AUTHOR_ID);
			s.executeUpdate(INDEX_GROUPS_BY_CLIENT_ID_MAJOR_VERSION);
			s.executeUpdate(INDEX_MESSAGE_METADATA_BY_GROUP_ID_STATE);
			s.executeUpdate(INDEX_MESSAGE_DEPENDENCIES_BY_DEPENDENCY_ID);
			s.executeUpdate(INDEX_STATUSES_BY_CONTACT_ID_GROUP_ID);
			s.executeUpdate(INDEX_STATUSES_BY_CONTACT_ID_TIMESTAMP);
			s.executeUpdate(INDEX_STATUSES_BY_CONTACT_ID_TX_COUNT_TIMESTAMP);
			s.executeUpdate(INDEX_MESSAGES_BY_CLEANUP_DEADLINE);
			s.executeUpdate(INDEX_MESSAGES_BY_TEMPORARY);
			s.executeUpdate(INDEX_PCS_SKIPPED_KEYS_BY_TIMESTAMP);
			s.executeUpdate(INDEX_PCS_SKIPPED_KEYS_BY_CHAIN_ID);
			s.executeUpdate(INDEX_GROUP_KEY_HISTORY_BY_EXPIRY);
			s.executeUpdate(INDEX_GROUP_SENDER_KEYS_BY_GROUP);
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}

	@Override
	public Connection startTransaction() throws DbException {
		Connection txn;
		connectionsLock.lock();
		try {
			if (closed) throw new DbClosedException();
			txn = connectionPool.poll();
			logConnectionCounts();
		} finally {
			connectionsLock.unlock();
		}
		try {
			if (txn == null) {
				txn = createConnection();
				connectionsLock.lock();
				try {
					if (closed) {
						tryToClose(txn);
						throw new DbClosedException();
					}
					openConnections++;
					logConnectionCounts();
					connectionsChanged.signalAll();
				} finally {
					connectionsLock.unlock();
				}
			}
			txn.setAutoCommit(false);
		} catch (SQLException e) {
			throw new DbException(e);
		}
		return txn;
	}

	@GuardedBy("connectionsLock")
	private void logConnectionCounts() {
	}

	@Override
	public void abortTransaction(Connection txn) {
		try {
			txn.rollback();
		} catch (SQLException e) {
		}
		closeConnection(txn);
	}

	private void closeConnection(Connection txn) {
		tryToClose(txn);
		connectionsLock.lock();
		try {
			openConnections--;
			logConnectionCounts();
			connectionsChanged.signalAll();
		} finally {
			connectionsLock.unlock();
		}
	}

	@Override
	public void commitTransaction(Connection txn) throws DbException {
		try {
			txn.commit();
			returnConnectionToPool(txn);
		} catch (SQLException e) {
			closeConnection(txn);
			throw new DbException(e);
		}
	}

	private void returnConnectionToPool(Connection txn) {
		boolean shouldClose;
		connectionsLock.lock();
		try {
			shouldClose = connectionPool.size() >= MAX_CONNECTION_POOL_SIZE;
			if (shouldClose) openConnections--;
			else connectionPool.add(txn);
			logConnectionCounts();
			connectionsChanged.signalAll();
		} finally {
			connectionsLock.unlock();
		}
		if (shouldClose) tryToClose(txn);
	}

	/**
	 * Adds an already-open connection to the pool so the first transaction of
	 * {@link #open} reuses it instead of opening a fresh connection. Lets a
	 * subclass validate the database with a real connection and then hand that
	 * same connection to open, avoiding a second key derivation. The connection
	 * must be idle (auto-commit, no open transaction), exactly as a freshly
	 * created one is.
	 */
	protected void seedPooledConnection(Connection c) {
		connectionsLock.lock();
		try {
			closed = false;
			connectionPool.add(c);
			openConnections++;
			connectionsChanged.signalAll();
		} finally {
			connectionsLock.unlock();
		}
	}

	void closeAllConnections() {
		boolean interrupted = false;
		connectionsLock.lock();
		try {
			closed = true;
			for (Connection c : connectionPool) tryToClose(c);
			openConnections -= connectionPool.size();
			connectionPool.clear();
			while (openConnections > 0) {
				try {
					connectionsChanged.await();
				} catch (InterruptedException e) {
					interrupted = true;
				}
				for (Connection c : connectionPool) tryToClose(c);
				openConnections -= connectionPool.size();
				connectionPool.clear();
			}
		} finally {
			connectionsLock.unlock();
		}

		if (interrupted) Thread.currentThread().interrupt();
	}

	@Override
	public ContactId addContact(Connection txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified)
			throws DbException {
		return addContact(txn, remote, local, handshake, verified, false, false);
	}

	@Override
	public ContactId addContact(Connection txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified, boolean postQuantum)
			throws DbException {
		return addContact(txn, remote, local, handshake, verified, postQuantum,
				false);
	}

	@Override
	public ContactId addContact(Connection txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified, boolean postQuantum,
			boolean pcsEnabled) throws DbException {
		return addContact(txn, remote, local, handshake, verified, postQuantum,
				pcsEnabled, (byte[]) null);
	}

	@Override
	public ContactId addContact(Connection txn, Author remote, AuthorId local,
			@Nullable PublicKey handshake, boolean verified, boolean postQuantum,
			boolean pcsEnabled,
			@Nullable byte[] mlDsaSigPublicKey) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "INSERT INTO contacts"
					+ " (authorId, formatVersion, name, publicKey,"
					+ " localAuthorId, handshakePublicKey, verified, postQuantum,"
					+ " pcsEnabled, mlDsaSigPublicKey)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, remote.getId().getBytes());
			ps.setInt(2, remote.getFormatVersion());
			ps.setString(3, remote.getName());
			ps.setBytes(4, remote.getPublicKey().getEncoded());
			ps.setBytes(5, local.getBytes());
			if (handshake == null) ps.setNull(6, BINARY);
			else ps.setBytes(6, handshake.getEncoded());
			ps.setBoolean(7, verified);
			ps.setBoolean(8, postQuantum);
			ps.setBoolean(9, pcsEnabled);
			if (mlDsaSigPublicKey == null) ps.setNull(10, BINARY);
			else ps.setBytes(10, mlDsaSigPublicKey);
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
			sql = "SELECT contactId FROM contacts"
					+ " ORDER BY contactId DESC LIMIT 1";
			ps = txn.prepareStatement(sql);
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			ContactId c = new ContactId(rs.getInt(1));
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return c;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void addGroup(Connection txn, Group g) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "INSERT INTO groups"
					+ " (groupId, clientId, majorVersion, descriptor)"
					+ " VALUES (?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getId().getBytes());
			ps.setString(2, g.getClientId().getString());
			ps.setInt(3, g.getMajorVersion());
			ps.setBytes(4, g.getDescriptor());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void addGroupVisibility(Connection txn, ContactId c, GroupId g,
			boolean groupShared) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "INSERT INTO groupVisibilities"
					+ " (contactId, groupId, shared)"
					+ " VALUES (?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setBytes(2, g.getBytes());
			ps.setBoolean(3, groupShared);
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
			addStatus(txn, c, g, groupShared);
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	private void addStatus(Connection txn, ContactId c, GroupId g,
			boolean groupShared) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId, timestamp, state, shared,"
					+ " length, raw IS NULL"
					+ " FROM messages"
					+ " WHERE groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			rs = ps.executeQuery();
			while (rs.next()) {
				MessageId id = new MessageId(rs.getBytes(1));
				long timestamp = rs.getLong(2);
				MessageState state = MessageState.fromValue(rs.getInt(3));
				boolean messageShared = rs.getBoolean(4);
				int length = rs.getInt(5);
				boolean deleted = rs.getBoolean(6);
				boolean seen = removeOfferedMessage(txn, c, id);
				addStatus(txn, id, c, g, timestamp, length, state, groupShared,
						messageShared, deleted, seen);
			}
			rs.close();
			ps.close();
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void addIdentity(Connection txn, Identity i) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "INSERT INTO localAuthors"
					+ " (authorId, formatVersion, name, publicKey, privateKey,"
					+ " handshakePublicKey, handshakePrivateKey,"
					+ " hybridHandshakePublicKey, hybridHandshakePrivateKey,"
					+ " mlDsaSigPublicKey, mlDsaSigPrivateKey,"
					+ " created)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			LocalAuthor local = i.getLocalAuthor();
			ps.setBytes(1, local.getId().getBytes());
			ps.setInt(2, local.getFormatVersion());
			ps.setString(3, local.getName());
			ps.setBytes(4, local.getPublicKey().getEncoded());
			ps.setBytes(5, local.getPrivateKey().getEncoded());
			if (i.getHandshakePublicKey() == null) ps.setNull(6, BINARY);
			else ps.setBytes(6, i.getHandshakePublicKey().getEncoded());
			if (i.getHandshakePrivateKey() == null) ps.setNull(7, BINARY);
			else ps.setBytes(7, i.getHandshakePrivateKey().getEncoded());
			if (i.getHybridHandshakePublicKey() == null) ps.setNull(8, BINARY);
			else ps.setBytes(8, i.getHybridHandshakePublicKey().getEncoded());
			if (i.getHybridHandshakePrivateKey() == null) ps.setNull(9, BINARY);
			else ps.setBytes(9, i.getHybridHandshakePrivateKey().getEncoded());
			if (i.getMlDsaSigPublicKey() == null) ps.setNull(10, BINARY);
			else ps.setBytes(10, i.getMlDsaSigPublicKey());
			if (i.getMlDsaSigPrivateKey() == null) ps.setNull(11, BINARY);
			else ps.setBytes(11, i.getMlDsaSigPrivateKey());
			ps.setLong(12, i.getTimeCreated());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void addMessage(Connection txn, Message m, MessageState state,
			boolean shared, boolean temporary, @Nullable ContactId sender)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "INSERT INTO messages (messageId, groupId, timestamp,"
					+ " state, shared, temporary, length, raw)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getId().getBytes());
			ps.setBytes(2, m.getGroupId().getBytes());
			ps.setLong(3, m.getTimestamp());
			ps.setInt(4, state.getValue());
			ps.setBoolean(5, shared);
			ps.setBoolean(6, temporary);
			byte[] raw = messageFactory.getRawMessage(m);
			ps.setInt(7, raw.length);
			ps.setBytes(8, raw);
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
			Map<ContactId, Boolean> visibility =
					getGroupVisibility(txn, m.getGroupId());
			for (Entry<ContactId, Boolean> e : visibility.entrySet()) {
				ContactId c = e.getKey();
				boolean offered = removeOfferedMessage(txn, c, m.getId());
				boolean seen = offered || c.equals(sender);
				addStatus(txn, m.getId(), c, m.getGroupId(), m.getTimestamp(),
						raw.length, state, e.getValue(), shared, false, seen);
			}
			sql = "UPDATE messageDependencies SET dependencyState = ?"
					+ " WHERE groupId = ? AND dependencyId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, state.getValue());
			ps.setBytes(2, m.getGroupId().getBytes());
			ps.setBytes(3, m.getId().getBytes());
			affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void addOfferedMessage(Connection txn, ContactId c, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM offers"
					+ " WHERE messageId = ? AND contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setInt(2, c.getInt());
			rs = ps.executeQuery();
			boolean found = rs.next();
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			if (found) return;
			sql = "INSERT INTO offers (messageId, contactId) VALUES (?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setInt(2, c.getInt());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	private void addStatus(Connection txn, MessageId m, ContactId c, GroupId g,
			long timestamp, int length, MessageState state, boolean groupShared,
			boolean messageShared, boolean deleted, boolean seen)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "INSERT INTO statuses (messageId, contactId, groupId,"
					+ " timestamp, length, state, groupShared, messageShared,"
					+ " deleted, ack, seen, requested, expiry, txCount,"
					+ " maxLatency)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, 0, 0,"
					+ " NULL)";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setInt(2, c.getInt());
			ps.setBytes(3, g.getBytes());
			ps.setLong(4, timestamp);
			ps.setInt(5, length);
			ps.setInt(6, state.getValue());
			ps.setBoolean(7, groupShared);
			ps.setBoolean(8, messageShared);
			ps.setBoolean(9, deleted);
			ps.setBoolean(10, seen);
			ps.setBoolean(11, seen);
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void addMessageDependency(Connection txn, Message dependent,
			MessageId dependency, MessageState dependentState)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT state FROM messages"
					+ " WHERE messageId = ? AND groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, dependency.getBytes());
			ps.setBytes(2, dependent.getGroupId().getBytes());
			rs = ps.executeQuery();
			MessageState dependencyState = null;
			if (rs.next()) {
				dependencyState = MessageState.fromValue(rs.getInt(1));
				if (rs.next()) throw new DbStateException();
			}
			rs.close();
			ps.close();
			sql = "INSERT INTO messageDependencies"
					+ " (groupId, messageId, dependencyId, messageState,"
					+ " dependencyState)"
					+ " VALUES (?, ?, ?, ? ,?)";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, dependent.getGroupId().getBytes());
			ps.setBytes(2, dependent.getId().getBytes());
			ps.setBytes(3, dependency.getBytes());
			ps.setInt(4, dependentState.getValue());
			if (dependencyState == null) ps.setNull(5, INTEGER);
			else ps.setInt(5, dependencyState.getValue());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void addPendingContact(Connection txn, PendingContact p)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "INSERT INTO pendingContacts (pendingContactId,"
					+ " publicKey, alias, timestamp, formatVersion)"
					+ " VALUES (?, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, p.getId().getBytes());
			ps.setBytes(2, p.getPublicKey().getEncoded());
			ps.setString(3, p.getAlias());
			ps.setLong(4, p.getTimestamp());
			ps.setInt(5, p.getFormatVersion());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void addTransport(Connection txn, TransportId t, long maxLatency)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "INSERT INTO transports (transportId, maxLatency)"
					+ " VALUES (?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setString(1, t.getString());
			ps.setLong(2, maxLatency);
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public KeySetId addTransportKeys(Connection txn, ContactId c,
			TransportKeys k) throws DbException {
		return addTransportKeys(txn, c, null, k);
	}

	@Override
	public KeySetId addTransportKeys(Connection txn,
			PendingContactId p, TransportKeys k) throws DbException {
		return addTransportKeys(txn, null, p, k);
	}

	private KeySetId addTransportKeys(Connection txn,
			@Nullable ContactId c, @Nullable PendingContactId p,
			TransportKeys k) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {

			String sql = "SELECT COALESCE(MAX(keySetId), 0) + 1"
					+ " FROM outgoingKeys";
			ps = txn.prepareStatement(sql);
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			int nextKeySetId = rs.getInt(1);
			rs.close();
			ps.close();
			sql = "INSERT INTO outgoingKeys (transportId, keySetId,"
					+ " timePeriod, contactId, pendingContactId, tagKey,"
					+ " headerKey, stream, active, rootKey, alice)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setString(1, k.getTransportId().getString());
			ps.setInt(2, nextKeySetId);
			ps.setLong(3, k.getTimePeriod());
			if (c == null) ps.setNull(4, INTEGER);
			else ps.setInt(4, c.getInt());
			if (p == null) ps.setNull(5, BINARY);
			else ps.setBytes(5, p.getBytes());
			OutgoingKeys outCurr = k.getCurrentOutgoingKeys();
			ps.setBytes(6, outCurr.getTagKey().getBytes());
			ps.setBytes(7, outCurr.getHeaderKey().getBytes());
			ps.setLong(8, outCurr.getStreamCounter());
			ps.setBoolean(9, outCurr.isActive());
			if (k.isHandshakeMode()) {
				ps.setBytes(10, k.getRootKey().getBytes());
				ps.setBoolean(11, k.isAlice());
			} else {
				ps.setNull(10, BINARY);
				ps.setNull(11, BOOLEAN);
			}
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
			KeySetId keySetId = new KeySetId(nextKeySetId);
			sql = "INSERT INTO incomingKeys (transportId, keySetId,"
					+ " timePeriod, tagKey, headerKey, base, bitmap,"
					+ " periodOffset)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setString(1, k.getTransportId().getString());
			ps.setInt(2, keySetId.getInt());
			IncomingKeys inPrev = k.getPreviousIncomingKeys();
			ps.setLong(3, inPrev.getTimePeriod());
			ps.setBytes(4, inPrev.getTagKey().getBytes());
			ps.setBytes(5, inPrev.getHeaderKey().getBytes());
			ps.setLong(6, inPrev.getWindowBase());
			ps.setBytes(7, inPrev.getWindowBitmap());
			ps.setInt(8, OFFSET_PREV);
			ps.addBatch();
			IncomingKeys inCurr = k.getCurrentIncomingKeys();
			ps.setLong(3, inCurr.getTimePeriod());
			ps.setBytes(4, inCurr.getTagKey().getBytes());
			ps.setBytes(5, inCurr.getHeaderKey().getBytes());
			ps.setLong(6, inCurr.getWindowBase());
			ps.setBytes(7, inCurr.getWindowBitmap());
			ps.setInt(8, OFFSET_CURR);
			ps.addBatch();
			IncomingKeys inNext = k.getNextIncomingKeys();
			ps.setLong(3, inNext.getTimePeriod());
			ps.setBytes(4, inNext.getTagKey().getBytes());
			ps.setBytes(5, inNext.getHeaderKey().getBytes());
			ps.setLong(6, inNext.getWindowBase());
			ps.setBytes(7, inNext.getWindowBitmap());
			ps.setInt(8, OFFSET_NEXT);
			ps.addBatch();
			int[] batchAffected = ps.executeBatch();
			if (batchAffected.length != 3) throw new DbStateException();
			for (int rows : batchAffected)
				if (rows != 1) throw new DbStateException();
			ps.close();
			return keySetId;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsAcksToSend(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM statuses"
					+ " WHERE contactId = ? AND ack = TRUE";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			rs = ps.executeQuery();
			boolean acksToSend = rs.next();
			rs.close();
			ps.close();
			return acksToSend;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsContact(Connection txn, AuthorId remote,
			AuthorId local) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM contacts"
					+ " WHERE authorId = ? AND localAuthorId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, remote.getBytes());
			ps.setBytes(2, local.getBytes());
			rs = ps.executeQuery();
			boolean found = rs.next();
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return found;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsContact(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM contacts WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			rs = ps.executeQuery();
			boolean found = rs.next();
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return found;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsGroup(Connection txn, GroupId g)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM groups WHERE groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			rs = ps.executeQuery();
			boolean found = rs.next();
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return found;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsIdentity(Connection txn, AuthorId a)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM localAuthors WHERE authorId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, a.getBytes());
			rs = ps.executeQuery();
			boolean found = rs.next();
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return found;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsMessage(Connection txn, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM messages WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			rs = ps.executeQuery();
			boolean found = rs.next();
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return found;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsMessagesToSend(Connection txn, ContactId c,
			long maxLatency, boolean eager) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			if (eager) {
				String sql = "SELECT NULL from statuses"
						+ " WHERE contactId = ? AND state = ?"
						+ " AND groupShared = TRUE AND messageShared = TRUE"
						+ " AND deleted = FALSE AND seen = FALSE";
				ps = txn.prepareStatement(sql);
				ps.setInt(1, c.getInt());
				ps.setInt(2, DELIVERED.getValue());
			} else {
				long now = clock.currentTimeMillis();
				String sql = "SELECT NULL FROM statuses"
						+ " WHERE contactId = ? AND state = ?"
						+ " AND groupShared = TRUE AND messageShared = TRUE"
						+ " AND deleted = FALSE AND seen = FALSE"
						+ " AND (expiry <= ? OR maxLatency IS NULL"
						+ " OR ? < maxLatency)";
				ps = txn.prepareStatement(sql);
				ps.setInt(1, c.getInt());
				ps.setInt(2, DELIVERED.getValue());
				ps.setLong(3, now);
				ps.setLong(4, maxLatency);
			}
			rs = ps.executeQuery();
			boolean messagesToSend = rs.next();
			rs.close();
			ps.close();
			return messagesToSend;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsPendingContact(Connection txn, PendingContactId p)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM pendingContacts"
					+ " WHERE pendingContactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, p.getBytes());
			rs = ps.executeQuery();
			boolean found = rs.next();
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return found;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsTransport(Connection txn, TransportId t)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM transports WHERE transportId = ?";
			ps = txn.prepareStatement(sql);
			ps.setString(1, t.getString());
			rs = ps.executeQuery();
			boolean found = rs.next();
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return found;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsTransportKeys(Connection txn, ContactId c,
			TransportId t) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM outgoingKeys"
					+ " WHERE contactId = ? AND transportId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setString(2, t.getString());
			rs = ps.executeQuery();
			boolean found = rs.next();
			rs.close();
			ps.close();
			return found;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsVisibleMessage(Connection txn, ContactId c,
			MessageId m) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM statuses"
					+ " WHERE messageId = ? AND contactId = ?"
					+ " AND messageShared = TRUE";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setInt(2, c.getInt());
			rs = ps.executeQuery();
			boolean found = rs.next();
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return found;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public int countOfferedMessages(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT COUNT (messageId) FROM offers "
					+ " WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbException();
			int count = rs.getInt(1);
			if (rs.next()) throw new DbException();
			rs.close();
			ps.close();
			return count;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void deleteMessage(Connection txn, MessageId m) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE messages"
					+ " SET raw = NULL, cleanupDeadline = NULL"
					+ " WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			if (affected > 1) throw new DbStateException();
			ps.close();
			sql = "UPDATE statuses SET deleted = TRUE WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void deleteMessageMetadata(Connection txn, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM messageMetadata WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Contact getContact(Connection txn, ContactId c) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT authorId, formatVersion, name, alias,"
					+ " publicKey, handshakePublicKey, localAuthorId, verified,"
					+ " postQuantum, pcsEnabled, mlDsaSigPublicKey"
					+ " FROM contacts"
					+ " WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			AuthorId authorId = new AuthorId(rs.getBytes(1));
			int formatVersion = rs.getInt(2);
			String name = rs.getString(3);
			String alias = rs.getString(4);
			PublicKey publicKey = new SignaturePublicKey(rs.getBytes(5));
			byte[] handshakePub = rs.getBytes(6);
			AuthorId localAuthorId = new AuthorId(rs.getBytes(7));
			boolean verified = rs.getBoolean(8);
			boolean postQuantum = rs.getBoolean(9);
			boolean pcsEnabled = rs.getBoolean(10);
			byte[] mlDsaSigPublicKey = rs.getBytes(11);
			rs.close();
			ps.close();
			Author author =
					new Author(authorId, formatVersion, name, publicKey);
			PublicKey handshakePublicKey = handshakePub == null ?
					null : new AgreementPublicKey(handshakePub);
			return new Contact(c, author, localAuthorId, alias,
					handshakePublicKey, verified, postQuantum, pcsEnabled,
					mlDsaSigPublicKey);
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<Contact> getContacts(Connection txn) throws DbException {
		Statement s = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT contactId, authorId, formatVersion, name,"
					+ " alias, publicKey, handshakePublicKey, localAuthorId,"
					+ " verified, postQuantum, pcsEnabled, mlDsaSigPublicKey"
					+ " FROM contacts";
			s = txn.createStatement();
			rs = s.executeQuery(sql);
			List<Contact> contacts = new ArrayList<>();
			while (rs.next()) {
				ContactId contactId = new ContactId(rs.getInt(1));
				AuthorId authorId = new AuthorId(rs.getBytes(2));
				int formatVersion = rs.getInt(3);
				String name = rs.getString(4);
				String alias = rs.getString(5);
				PublicKey publicKey = new SignaturePublicKey(rs.getBytes(6));
				byte[] handshakePub = rs.getBytes(7);
				AuthorId localAuthorId = new AuthorId(rs.getBytes(8));
				boolean verified = rs.getBoolean(9);
				boolean postQuantum = rs.getBoolean(10);
				boolean pcsEnabled = rs.getBoolean(11);
				byte[] mlDsaSigPublicKey = rs.getBytes(12);
				Author author =
						new Author(authorId, formatVersion, name, publicKey);
				PublicKey handshakePublicKey = handshakePub == null ?
						null : new AgreementPublicKey(handshakePub);
				contacts.add(new Contact(contactId, author, localAuthorId,
						alias, handshakePublicKey, verified, postQuantum,
						pcsEnabled, mlDsaSigPublicKey));
			}
			rs.close();
			s.close();
			return contacts;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(s);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<ContactId> getContacts(Connection txn, AuthorId local)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT contactId FROM contacts"
					+ " WHERE localAuthorId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, local.getBytes());
			rs = ps.executeQuery();
			List<ContactId> ids = new ArrayList<>();
			while (rs.next()) ids.add(new ContactId(rs.getInt(1)));
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<Contact> getContactsByAuthorId(Connection txn,
			AuthorId remote) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT contactId, formatVersion, name, alias,"
					+ " publicKey, handshakePublicKey, localAuthorId, verified,"
					+ " postQuantum, pcsEnabled, mlDsaSigPublicKey"
					+ " FROM contacts"
					+ " WHERE authorId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, remote.getBytes());
			rs = ps.executeQuery();
			List<Contact> contacts = new ArrayList<>();
			while (rs.next()) {
				ContactId contactId = new ContactId(rs.getInt(1));
				int formatVersion = rs.getInt(2);
				String name = rs.getString(3);
				String alias = rs.getString(4);
				PublicKey publicKey = new SignaturePublicKey(rs.getBytes(5));
				byte[] handshakePub = rs.getBytes(6);
				AuthorId localAuthorId = new AuthorId(rs.getBytes(7));
				boolean verified = rs.getBoolean(8);
				boolean postQuantum = rs.getBoolean(9);
				boolean pcsEnabled = rs.getBoolean(10);
				byte[] mlDsaSigPublicKey = rs.getBytes(11);
				Author author =
						new Author(remote, formatVersion, name, publicKey);
				PublicKey handshakePublicKey = handshakePub == null ?
						null : new AgreementPublicKey(handshakePub);
				contacts.add(new Contact(contactId, author, localAuthorId,
						alias, handshakePublicKey, verified, postQuantum,
						pcsEnabled, mlDsaSigPublicKey));
			}
			rs.close();
			ps.close();
			return contacts;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Nullable
	@Override
	public Contact getContact(Connection txn, PublicKey handshakePublicKey,
			AuthorId localAuthorId) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT contactId, authorId, formatVersion, name,"
					+ " alias, publicKey, verified, postQuantum, pcsEnabled,"
					+ " mlDsaSigPublicKey"
					+ " FROM contacts"
					+ " WHERE handshakePublicKey = ? AND localAuthorId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, handshakePublicKey.getEncoded());
			ps.setBytes(2, localAuthorId.getBytes());
			rs = ps.executeQuery();
			if (!rs.next()) {
				rs.close();
				ps.close();
				return null;
			}
			ContactId contactId = new ContactId(rs.getInt(1));
			AuthorId authorId = new AuthorId(rs.getBytes(2));
			int formatVersion = rs.getInt(3);
			String name = rs.getString(4);
			String alias = rs.getString(5);
			PublicKey publicKey = new SignaturePublicKey(rs.getBytes(6));
			boolean verified = rs.getBoolean(7);
			boolean postQuantum = rs.getBoolean(8);
			boolean pcsEnabled = rs.getBoolean(9);
			byte[] mlDsaSigPublicKey = rs.getBytes(10);
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			Author author =
					new Author(authorId, formatVersion, name, publicKey);
			return new Contact(contactId, author, localAuthorId, alias,
					handshakePublicKey, verified, postQuantum, pcsEnabled,
					mlDsaSigPublicKey);
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Group getGroup(Connection txn, GroupId g) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT clientId, majorVersion, descriptor"
					+ " FROM groups WHERE groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			ClientId clientId = new ClientId(rs.getString(1));
			int majorVersion = rs.getInt(2);
			byte[] descriptor = rs.getBytes(3);
			rs.close();
			ps.close();
			return new Group(g, clientId, majorVersion, descriptor);
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public GroupId getGroupId(Connection txn, MessageId m) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT groupId FROM messages WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			GroupId g = new GroupId(rs.getBytes(1));
			rs.close();
			ps.close();
			return g;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<Group> getGroups(Connection txn, ClientId c,
			int majorVersion) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT groupId, descriptor FROM groups"
					+ " WHERE clientId = ? AND majorVersion = ?";
			ps = txn.prepareStatement(sql);
			ps.setString(1, c.getString());
			ps.setInt(2, majorVersion);
			rs = ps.executeQuery();
			List<Group> groups = new ArrayList<>();
			while (rs.next()) {
				GroupId id = new GroupId(rs.getBytes(1));
				byte[] descriptor = rs.getBytes(2);
				groups.add(new Group(id, c, majorVersion, descriptor));
			}
			rs.close();
			ps.close();
			return groups;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Visibility getGroupVisibility(Connection txn, ContactId c, GroupId g)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT shared FROM groupVisibilities"
					+ " WHERE contactId = ? AND groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setBytes(2, g.getBytes());
			rs = ps.executeQuery();
			Visibility v;
			if (rs.next()) v = rs.getBoolean(1) ? SHARED : VISIBLE;
			else v = INVISIBLE;
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return v;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Map<ContactId, Boolean> getGroupVisibility(Connection txn, GroupId g)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT contactId, shared FROM groupVisibilities"
					+ " WHERE groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			rs = ps.executeQuery();
			Map<ContactId, Boolean> visible = new HashMap<>();
			while (rs.next())
				visible.put(new ContactId(rs.getInt(1)), rs.getBoolean(2));
			rs.close();
			ps.close();
			return visible;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Identity getIdentity(Connection txn, AuthorId a) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT formatVersion, name, publicKey, privateKey,"
					+ " handshakePublicKey, handshakePrivateKey,"
					+ " hybridHandshakePublicKey, hybridHandshakePrivateKey,"
					+ " mlDsaSigPublicKey, mlDsaSigPrivateKey,"
					+ " created"
					+ " FROM localAuthors"
					+ " WHERE authorId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, a.getBytes());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			int formatVersion = rs.getInt(1);
			String name = rs.getString(2);
			PublicKey publicKey = new SignaturePublicKey(rs.getBytes(3));
			PrivateKey privateKey = new SignaturePrivateKey(rs.getBytes(4));
			byte[] handshakePub = rs.getBytes(5);
			byte[] handshakePriv = rs.getBytes(6);
			byte[] hybridPub = rs.getBytes(7);
			byte[] hybridPriv = rs.getBytes(8);
			byte[] mlDsaPub = rs.getBytes(9);
			byte[] mlDsaPriv = rs.getBytes(10);
			long created = rs.getLong(11);
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			LocalAuthor local = new LocalAuthor(a, formatVersion, name,
					publicKey, privateKey);
			PublicKey handshakePublicKey = handshakePub == null ?
					null : new AgreementPublicKey(handshakePub);
			PrivateKey handshakePrivateKey = handshakePriv == null ?
					null : new AgreementPrivateKey(handshakePriv);
			PublicKey hybridHandshakePublicKey = hybridPub == null ?
					null : new HybridAgreementPublicKey(hybridPub);
			PrivateKey hybridHandshakePrivateKey = hybridPriv == null ?
					null : new HybridAgreementPrivateKey(hybridPriv);
			return new Identity(local, handshakePublicKey, handshakePrivateKey,
					hybridHandshakePublicKey, hybridHandshakePrivateKey,
					mlDsaPub, mlDsaPriv, created);
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<Identity> getIdentities(Connection txn)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT authorId, formatVersion, name, publicKey,"
					+ " privateKey, handshakePublicKey, handshakePrivateKey,"
					+ " hybridHandshakePublicKey, hybridHandshakePrivateKey,"
					+ " mlDsaSigPublicKey, mlDsaSigPrivateKey,"
					+ " created"
					+ " FROM localAuthors";
			ps = txn.prepareStatement(sql);
			rs = ps.executeQuery();
			List<Identity> identities = new ArrayList<>();
			while (rs.next()) {
				AuthorId authorId = new AuthorId(rs.getBytes(1));
				int formatVersion = rs.getInt(2);
				String name = rs.getString(3);
				PublicKey publicKey = new SignaturePublicKey(rs.getBytes(4));
				PrivateKey privateKey = new SignaturePrivateKey(rs.getBytes(5));
				byte[] handshakePub = rs.getBytes(6);
				byte[] handshakePriv = rs.getBytes(7);
				byte[] hybridPub = rs.getBytes(8);
				byte[] hybridPriv = rs.getBytes(9);
				byte[] mlDsaPub = rs.getBytes(10);
				byte[] mlDsaPriv = rs.getBytes(11);
				long created = rs.getLong(12);
				LocalAuthor local = new LocalAuthor(authorId, formatVersion,
						name, publicKey, privateKey);
				PublicKey handshakePublicKey = handshakePub == null ?
						null : new AgreementPublicKey(handshakePub);
				PrivateKey handshakePrivateKey = handshakePriv == null ?
						null : new AgreementPrivateKey(handshakePriv);
				PublicKey hybridHandshakePublicKey = hybridPub == null ?
						null : new HybridAgreementPublicKey(hybridPub);
				PrivateKey hybridHandshakePrivateKey = hybridPriv == null ?
						null : new HybridAgreementPrivateKey(hybridPriv);
				identities.add(new Identity(local, handshakePublicKey,
						handshakePrivateKey, hybridHandshakePublicKey,
						hybridHandshakePrivateKey, mlDsaPub, mlDsaPriv,
						created));
			}
			rs.close();
			ps.close();
			return identities;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Message getMessage(Connection txn, MessageId m) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT groupId, timestamp, raw FROM messages"
					+ " WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			GroupId g = new GroupId(rs.getBytes(1));
			long timestamp = rs.getLong(2);
			byte[] raw = rs.getBytes(3);
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			if (raw == null) throw new MessageDeletedException();
			if (raw.length <= MESSAGE_HEADER_LENGTH) throw new AssertionError();
			byte[] body = new byte[raw.length - MESSAGE_HEADER_LENGTH];
			System.arraycopy(raw, MESSAGE_HEADER_LENGTH, body, 0, body.length);
			return new Message(m, g, timestamp, body);
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageId> getMessageIds(Connection txn, GroupId g)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId FROM messages"
					+ " WHERE groupId = ? AND state = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			ps.setInt(2, DELIVERED.getValue());
			rs = ps.executeQuery();
			List<MessageId> ids = new ArrayList<>();
			while (rs.next()) ids.add(new MessageId(rs.getBytes(1)));
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageId> getAllMessageIds(Connection txn, GroupId g)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId FROM messages"
					+ " WHERE groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			rs = ps.executeQuery();
			List<MessageId> ids = new ArrayList<>();
			while (rs.next()) ids.add(new MessageId(rs.getBytes(1)));
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageId> getMessageIds(Connection txn, GroupId g,
			Metadata query) throws DbException {
		if (query.isEmpty()) return getMessageIds(txn, g);
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			Set<MessageId> intersection = null;
			String sql = "SELECT messageId FROM messageMetadata"
					+ " WHERE groupId = ? AND state = ?"
					+ " AND metaKey = ? AND value = ?";
			for (Entry<String, byte[]> e : query.entrySet()) {
				ps = txn.prepareStatement(sql);
				ps.setBytes(1, g.getBytes());
				ps.setInt(2, DELIVERED.getValue());
				ps.setString(3, e.getKey());
				ps.setBytes(4, e.getValue());
				rs = ps.executeQuery();
				Set<MessageId> ids = new HashSet<>();
				while (rs.next()) ids.add(new MessageId(rs.getBytes(1)));
				rs.close();
				ps.close();
				if (intersection == null) intersection = ids;
				else intersection.retainAll(ids);
				if (intersection.isEmpty()) return Collections.emptySet();
			}
			return intersection;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public int getMessageLength(Connection txn, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT length from messages"
					+ " WHERE messageId = ? AND state = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setInt(2, DELIVERED.getValue());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			int length = rs.getInt(1);
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return length;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Map<MessageId, Metadata> getMessageMetadata(Connection txn,
			GroupId g) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId, metaKey, value"
					+ " FROM messageMetadata"
					+ " WHERE groupId = ? AND state = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			ps.setInt(2, DELIVERED.getValue());
			rs = ps.executeQuery();
			Map<MessageId, Metadata> all = new HashMap<>();
			while (rs.next()) {
				MessageId messageId = new MessageId(rs.getBytes(1));
				Metadata metadata = all.get(messageId);
				if (metadata == null) {
					metadata = new Metadata();
					all.put(messageId, metadata);
				}
				metadata.put(rs.getString(2), rs.getBytes(3));
			}
			rs.close();
			ps.close();
			return all;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Map<MessageId, Metadata> getMessageMetadata(Connection txn,
			GroupId g, Metadata query) throws DbException {
		Collection<MessageId> matches = getMessageIds(txn, g, query);
		if (matches.isEmpty()) return Collections.emptyMap();
		Map<MessageId, Metadata> all = new HashMap<>(matches.size());
		for (MessageId m : matches) all.put(m, getMessageMetadata(txn, m));
		return all;
	}

	@Override
	public Metadata getGroupMetadata(Connection txn, GroupId g)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT metaKey, value FROM groupMetadata"
					+ " WHERE groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			rs = ps.executeQuery();
			Metadata metadata = new Metadata();
			while (rs.next()) metadata.put(rs.getString(1), rs.getBytes(2));
			rs.close();
			ps.close();
			return metadata;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Metadata getMessageMetadata(Connection txn, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT metaKey, value FROM messageMetadata"
					+ " WHERE state = ? AND messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, DELIVERED.getValue());
			ps.setBytes(2, m.getBytes());
			rs = ps.executeQuery();
			Metadata metadata = new Metadata();
			while (rs.next()) metadata.put(rs.getString(1), rs.getBytes(2));
			rs.close();
			ps.close();
			return metadata;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Metadata getMessageMetadataForValidator(Connection txn, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT metaKey, value FROM messageMetadata"
					+ " WHERE (state = ? OR state = ?)"
					+ " AND messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, DELIVERED.getValue());
			ps.setInt(2, PENDING.getValue());
			ps.setBytes(3, m.getBytes());
			rs = ps.executeQuery();
			Metadata metadata = new Metadata();
			while (rs.next()) metadata.put(rs.getString(1), rs.getBytes(2));
			rs.close();
			ps.close();
			return metadata;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageStatus> getMessageStatus(Connection txn,
			ContactId c, GroupId g) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId, txCount > 0, seen FROM statuses"
					+ " WHERE groupId = ? AND contactId = ? AND state = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			ps.setInt(2, c.getInt());
			ps.setInt(3, DELIVERED.getValue());
			rs = ps.executeQuery();
			List<MessageStatus> statuses = new ArrayList<>();
			while (rs.next()) {
				MessageId messageId = new MessageId(rs.getBytes(1));
				boolean sent = rs.getBoolean(2);
				boolean seen = rs.getBoolean(3);
				statuses.add(new MessageStatus(messageId, c, sent, seen));
			}
			rs.close();
			ps.close();
			return statuses;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	@Nullable
	public MessageStatus getMessageStatus(Connection txn, ContactId c,
			MessageId m) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT txCount > 0, seen FROM statuses"
					+ " WHERE messageId = ? AND contactId = ? AND state = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setInt(2, c.getInt());
			ps.setInt(3, DELIVERED.getValue());
			rs = ps.executeQuery();
			MessageStatus status = null;
			if (rs.next()) {
				boolean sent = rs.getBoolean(1);
				boolean seen = rs.getBoolean(2);
				status = new MessageStatus(m, c, sent, seen);
			}
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return status;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Map<MessageId, MessageState> getMessageDependencies(Connection txn,
			MessageId m) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT dependencyId, dependencyState"
					+ " FROM messageDependencies"
					+ " WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			rs = ps.executeQuery();
			Map<MessageId, MessageState> dependencies = new HashMap<>();
			while (rs.next()) {
				MessageId dependency = new MessageId(rs.getBytes(1));
				MessageState state = MessageState.fromValue(rs.getInt(2));
				if (rs.wasNull())
					state = UNKNOWN;
				dependencies.put(dependency, state);
			}
			rs.close();
			ps.close();
			return dependencies;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Map<MessageId, MessageState> getMessageDependents(Connection txn,
			MessageId m) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId, messageState"
					+ " FROM messageDependencies"
					+ " WHERE dependencyId = ?"
					+ " AND dependencyState IS NOT NULL";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			rs = ps.executeQuery();
			Map<MessageId, MessageState> dependents = new HashMap<>();
			while (rs.next()) {
				MessageId dependent = new MessageId(rs.getBytes(1));
				MessageState state = MessageState.fromValue(rs.getInt(2));
				dependents.put(dependent, state);
			}
			rs.close();
			ps.close();
			return dependents;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public MessageState getMessageState(Connection txn, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT state FROM messages WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			MessageState state = MessageState.fromValue(rs.getInt(1));
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return state;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageId> getMessagesToAck(Connection txn, ContactId c,
			int maxMessages) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId FROM statuses"
					+ " WHERE contactId = ? AND ack = TRUE"
					+ " LIMIT ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, maxMessages);
			rs = ps.executeQuery();
			List<MessageId> ids = new ArrayList<>();
			while (rs.next()) ids.add(new MessageId(rs.getBytes(1)));
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageId> getMessagesToOffer(Connection txn,
			ContactId c, int maxMessages, long maxLatency) throws DbException {
		long now = clock.currentTimeMillis();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId FROM statuses"
					+ " WHERE contactId = ? AND state = ?"
					+ " AND groupShared = TRUE AND messageShared = TRUE"
					+ " AND deleted = FALSE"
					+ " AND seen = FALSE AND requested = FALSE"
					+ " AND (expiry <= ? OR maxLatency IS NULL"
					+ " OR ? < maxLatency)"
					+ " ORDER BY timestamp LIMIT ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, DELIVERED.getValue());
			ps.setLong(3, now);
			ps.setLong(4, maxLatency);
			ps.setInt(5, maxMessages);
			rs = ps.executeQuery();
			List<MessageId> ids = new ArrayList<>();
			while (rs.next()) ids.add(new MessageId(rs.getBytes(1)));
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageId> getMessagesToRequest(Connection txn,
			ContactId c, int maxMessages) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId FROM offers"
					+ " WHERE contactId = ?"
					+ " LIMIT ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, maxMessages);
			rs = ps.executeQuery();
			List<MessageId> ids = new ArrayList<>();
			while (rs.next()) ids.add(new MessageId(rs.getBytes(1)));
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageId> getMessagesToSend(Connection txn,
			ContactId c, long capacity, long maxLatency) throws DbException {
		long now = clock.currentTimeMillis();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT length, messageId FROM statuses"
					+ " WHERE contactId = ? AND state = ?"
					+ " AND groupShared = TRUE AND messageShared = TRUE"
					+ " AND deleted = FALSE"
					+ " AND seen = FALSE"
					+ " AND (expiry <= ? OR maxLatency IS NULL"
					+ " OR ? < maxLatency)"
					+ " ORDER BY timestamp";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, DELIVERED.getValue());
			ps.setLong(3, now);
			ps.setLong(4, maxLatency);
			rs = ps.executeQuery();
			List<MessageId> ids = new ArrayList<>();
			while (rs.next()) {
				int length = rs.getInt(1);
				if (capacity < RECORD_HEADER_BYTES + length) break;
				ids.add(new MessageId(rs.getBytes(2)));
				capacity -= RECORD_HEADER_BYTES + length;
			}
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageId> getUnackedMessagesToSend(Connection txn,
			ContactId c) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId FROM statuses"
					+ " WHERE contactId = ? AND state = ?"
					+ " AND groupShared = TRUE AND messageShared = TRUE"
					+ " AND deleted = FALSE AND seen = FALSE"
					+ " ORDER BY txCount, timestamp";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, DELIVERED.getValue());
			rs = ps.executeQuery();
			List<MessageId> ids = new ArrayList<>();
			while (rs.next()) ids.add(new MessageId(rs.getBytes(1)));
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public long getUnackedMessageBytesToSend(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT SUM(length) FROM statuses"
					+ " WHERE contactId = ? AND state = ?"
					+ " AND groupShared = TRUE AND messageShared = TRUE"
					+ " AND deleted = FALSE AND seen = FALSE";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, DELIVERED.getValue());
			rs = ps.executeQuery();
			rs.next();
			long total = rs.getLong(1);
			rs.close();
			ps.close();
			return total;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageId> getMessagesToValidate(Connection txn)
			throws DbException {
		return getMessagesInState(txn, UNKNOWN);
	}

	@Override
	public Collection<MessageId> getPendingMessages(Connection txn)
			throws DbException {
		return getMessagesInState(txn, PENDING);
	}

	private Collection<MessageId> getMessagesInState(Connection txn,
			MessageState state) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId FROM messages"
					+ " WHERE state = ? AND raw IS NOT NULL";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, state.getValue());
			rs = ps.executeQuery();
			List<MessageId> ids = new ArrayList<>();
			while (rs.next()) ids.add(new MessageId(rs.getBytes(1)));
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageId> getMessagesToShare(Connection txn)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT m.messageId FROM messages AS m"
					+ " JOIN messageDependencies AS d"
					+ " ON m.messageId = d.dependencyId"
					+ " JOIN messages AS m1"
					+ " ON d.messageId = m1.messageId"
					+ " WHERE m.state = ?"
					+ " AND m.shared = FALSE AND m1.shared = TRUE";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, DELIVERED.getValue());
			rs = ps.executeQuery();
			List<MessageId> ids = new ArrayList<>();
			while (rs.next()) ids.add(new MessageId(rs.getBytes(1)));
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Map<GroupId, Collection<MessageId>> getMessagesToDelete(
			Connection txn) throws DbException {
		long now = clock.currentTimeMillis();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageId, groupId FROM messages"
					+ " WHERE cleanupDeadline <= ?";
			ps = txn.prepareStatement(sql);
			ps.setLong(1, now);
			rs = ps.executeQuery();
			Map<GroupId, Collection<MessageId>> ids = new HashMap<>();
			while (rs.next()) {
				MessageId m = new MessageId(rs.getBytes(1));
				GroupId g = new GroupId(rs.getBytes(2));
				Collection<MessageId> messageIds = ids.get(g);
				if (messageIds == null) {
					messageIds = new ArrayList<>();
					ids.put(g, messageIds);
				}
				messageIds.add(m);
			}
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public long getNextSendTime(Connection txn, ContactId c, long maxLatency)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT NULL FROM statuses"
					+ " WHERE contactId = ? AND state = ?"
					+ " AND groupShared = TRUE AND messageShared = TRUE"
					+ " AND deleted = FALSE AND seen = FALSE"
					+ " AND (maxLatency IS NULL OR ? < maxLatency)";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, DELIVERED.getValue());
			ps.setLong(3, maxLatency);
			rs = ps.executeQuery();
			boolean found = rs.next();
			rs.close();
			ps.close();
			if (found) return 0;
			sql = "SELECT expiry FROM statuses"
					+ " WHERE contactId = ? AND state = ?"
					+ " AND groupShared = TRUE AND messageShared = TRUE"
					+ " AND deleted = FALSE AND seen = FALSE"
					+ " ORDER BY expiry LIMIT 1";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, DELIVERED.getValue());
			rs = ps.executeQuery();
			long nextSendTime = Long.MAX_VALUE;
			if (rs.next()) {
				nextSendTime = rs.getLong(1);
				if (rs.next()) throw new AssertionError();
			}
			rs.close();
			ps.close();
			return nextSendTime;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public long getNextCleanupDeadline(Connection txn) throws DbException {
		Statement s = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT cleanupDeadline FROM messages"
					+ " WHERE cleanupDeadline IS NOT NULL"
					+ " ORDER BY cleanupDeadline LIMIT 1";
			s = txn.createStatement();
			rs = s.executeQuery(sql);
			long nextDeadline = NO_CLEANUP_DEADLINE;
			if (rs.next()) {
				nextDeadline = rs.getLong(1);
				if (rs.next()) throw new AssertionError();
			}
			rs.close();
			s.close();
			return nextDeadline;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(s);
			throw new DbException(e);
		}
	}

	private PublicKey parsePendingContactKey(byte[] encoded, int formatVersion) {
		if (formatVersion == HandshakeLinkConstants.FORMAT_VERSION_CLASSICAL) {
			return new AgreementPublicKey(encoded);
		}
		return new HybridCommitmentPublicKey(encoded);
	}

	@Override
	public PendingContact getPendingContact(Connection txn, PendingContactId p)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT publicKey, alias, timestamp, formatVersion"
					+ " FROM pendingContacts"
					+ " WHERE pendingContactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, p.getBytes());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			byte[] publicKeyBytes = rs.getBytes(1);
			String alias = rs.getString(2);
			long timestamp = rs.getLong(3);
			int formatVersion = rs.getInt(4);
			PublicKey publicKey = parsePendingContactKey(publicKeyBytes,
					formatVersion);
			return new PendingContact(p, publicKey, alias, timestamp, formatVersion);
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<PendingContact> getPendingContacts(Connection txn)
			throws DbException {
		Statement s = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT pendingContactId, publicKey, alias, timestamp,"
					+ " formatVersion FROM pendingContacts";
			s = txn.createStatement();
			rs = s.executeQuery(sql);
			List<PendingContact> pendingContacts = new ArrayList<>();
			while (rs.next()) {
				PendingContactId id = new PendingContactId(rs.getBytes(1));
				byte[] publicKeyBytes = rs.getBytes(2);
				String alias = rs.getString(3);
				long timestamp = rs.getLong(4);
				int formatVersion = rs.getInt(5);
				PublicKey publicKey = parsePendingContactKey(publicKeyBytes,
						formatVersion);
				pendingContacts.add(new PendingContact(id, publicKey, alias,
						timestamp, formatVersion));
			}
			rs.close();
			s.close();
			return pendingContacts;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(s);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<MessageId> getRequestedMessagesToSend(Connection txn,
			ContactId c, long capacity, long maxLatency) throws DbException {
		long now = clock.currentTimeMillis();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT length, messageId FROM statuses"
					+ " WHERE contactId = ? AND state = ?"
					+ " AND groupShared = TRUE AND messageShared = TRUE"
					+ " AND deleted = FALSE"
					+ " AND seen = FALSE AND requested = TRUE"
					+ " AND (expiry <= ? OR maxLatency IS NULL"
					+ " OR ? < maxLatency)"
					+ " ORDER BY timestamp";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, DELIVERED.getValue());
			ps.setLong(3, now);
			ps.setLong(4, maxLatency);
			rs = ps.executeQuery();
			List<MessageId> ids = new ArrayList<>();
			while (rs.next()) {
				int length = rs.getInt(1);
				if (capacity < RECORD_HEADER_BYTES + length) break;
				ids.add(new MessageId(rs.getBytes(2)));
				capacity -= RECORD_HEADER_BYTES + length;
			}
			rs.close();
			ps.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Settings getSettings(Connection txn, String namespace)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT settingKey, value FROM settings"
					+ " WHERE namespace = ?";
			ps = txn.prepareStatement(sql);
			ps.setString(1, namespace);
			rs = ps.executeQuery();
			Settings s = new Settings();
			while (rs.next()) s.put(rs.getString(1), rs.getString(2));
			rs.close();
			ps.close();
			return s;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public List<Byte> getSyncVersions(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT syncVersions FROM contacts"
					+ " WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			byte[] bytes = rs.getBytes(1);
			List<Byte> supported = new ArrayList<>(bytes.length);
			for (byte b : bytes) supported.add(b);
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return supported;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Collection<TransportKeySet> getTransportKeys(Connection txn,
			TransportId t) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT timePeriod, tagKey, headerKey, base, bitmap"
					+ " FROM incomingKeys"
					+ " WHERE transportId = ?"
					+ " ORDER BY keySetId, periodOffset";
			ps = txn.prepareStatement(sql);
			ps.setString(1, t.getString());
			rs = ps.executeQuery();
			List<IncomingKeys> inKeys = new ArrayList<>();
			while (rs.next()) {
				long timePeriod = rs.getLong(1);
				SecretKey tagKey = new SecretKey(rs.getBytes(2));
				SecretKey headerKey = new SecretKey(rs.getBytes(3));
				long windowBase = rs.getLong(4);
				byte[] windowBitmap = rs.getBytes(5);
				inKeys.add(new IncomingKeys(tagKey, headerKey, timePeriod,
						windowBase, windowBitmap));
			}
			rs.close();
			ps.close();
			sql = "SELECT keySetId, timePeriod, contactId, pendingContactId,"
					+ " tagKey, headerKey, stream, active, rootKey, alice"
					+ " FROM outgoingKeys"
					+ " WHERE transportId = ?"
					+ " ORDER BY keySetId";
			ps = txn.prepareStatement(sql);
			ps.setString(1, t.getString());
			rs = ps.executeQuery();
			Collection<TransportKeySet> keys = new ArrayList<>();
			for (int i = 0; rs.next(); i++) {
				if (inKeys.size() < (i + 1) * 3) throw new DbStateException();
				KeySetId keySetId = new KeySetId(rs.getInt(1));
				long timePeriod = rs.getLong(2);
				int cId = rs.getInt(3);
				ContactId contactId = rs.wasNull() ? null : new ContactId(cId);
				byte[] pId = rs.getBytes(4);
				PendingContactId pendingContactId = pId == null ?
						null : new PendingContactId(pId);
				SecretKey tagKey = new SecretKey(rs.getBytes(5));
				SecretKey headerKey = new SecretKey(rs.getBytes(6));
				long streamCounter = rs.getLong(7);
				boolean active = rs.getBoolean(8);
				byte[] rootKey = rs.getBytes(9);
				boolean alice = rs.getBoolean(10);
				OutgoingKeys outCurr = new OutgoingKeys(tagKey, headerKey,
						timePeriod, streamCounter, active);
				IncomingKeys inPrev = inKeys.get(i * 3);
				IncomingKeys inCurr = inKeys.get(i * 3 + 1);
				IncomingKeys inNext = inKeys.get(i * 3 + 2);
				TransportKeys transportKeys;
				if (rootKey == null) {
					transportKeys = new TransportKeys(t, inPrev, inCurr,
							inNext, outCurr);
				} else {
					transportKeys = new TransportKeys(t, inPrev, inCurr,
							inNext, outCurr, new SecretKey(rootKey), alice);
				}
				keys.add(new TransportKeySet(keySetId, contactId,
						pendingContactId, transportKeys));
			}
			rs.close();
			ps.close();
			return keys;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public Map<ContactId, Collection<TransportId>> getTransportsWithKeys(
			Connection txn) throws DbException {
		Statement s = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT DISTINCT contactId, transportId"
					+ " FROM outgoingKeys";
			s = txn.createStatement();
			rs = s.executeQuery(sql);
			Map<ContactId, Collection<TransportId>> ids = new HashMap<>();
			while (rs.next()) {
				ContactId c = new ContactId(rs.getInt(1));
				TransportId t = new TransportId(rs.getString(2));
				Collection<TransportId> transportIds = ids.get(c);
				if (transportIds == null) {
					transportIds = new ArrayList<>();
					ids.put(c, transportIds);
				}
				transportIds.add(t);
			}
			rs.close();
			s.close();
			return ids;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(s);
			tryToClose(s);
			throw new DbException(e);
		}
	}

	@Override
	public void incrementStreamCounter(Connection txn, TransportId t,
			KeySetId k) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE outgoingKeys SET stream = stream + 1"
					+ " WHERE transportId = ? AND keySetId = ?";
			ps = txn.prepareStatement(sql);
			ps.setString(1, t.getString());
			ps.setInt(2, k.getInt());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void lowerAckFlag(Connection txn, ContactId c,
			Collection<MessageId> acked) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE statuses SET ack = FALSE"
					+ " WHERE messageId = ? AND contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(2, c.getInt());
			for (MessageId m : acked) {
				ps.setBytes(1, m.getBytes());
				ps.addBatch();
			}
			int[] batchAffected = ps.executeBatch();
			if (batchAffected.length != acked.size())
				throw new DbStateException();
			for (int rows : batchAffected) {
				if (rows < 0) throw new DbStateException();
				if (rows > 1) throw new DbStateException();
			}
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void lowerRequestedFlag(Connection txn, ContactId c,
			Collection<MessageId> requested) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE statuses SET requested = FALSE"
					+ " WHERE messageId = ? AND contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(2, c.getInt());
			for (MessageId m : requested) {
				ps.setBytes(1, m.getBytes());
				ps.addBatch();
			}
			int[] batchAffected = ps.executeBatch();
			if (batchAffected.length != requested.size())
				throw new DbStateException();
			for (int rows : batchAffected) {
				if (rows < 0) throw new DbStateException();
				if (rows > 1) throw new DbStateException();
			}
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void mergeGroupMetadata(Connection txn, GroupId g, Metadata meta)
			throws DbException {
		PreparedStatement ps = null;
		try {
			Map<String, byte[]> added = removeOrUpdateMetadata(txn,
					g.getBytes(), meta, "groupMetadata", "groupId");
			if (added.isEmpty()) return;
			String sql = "INSERT INTO groupMetadata (groupId, metaKey, value)"
					+ " VALUES (?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			for (Entry<String, byte[]> e : added.entrySet()) {
				ps.setString(2, e.getKey());
				ps.setBytes(3, e.getValue());
				ps.addBatch();
			}
			int[] batchAffected = ps.executeBatch();
			if (batchAffected.length != added.size())
				throw new DbStateException();
			for (int rows : batchAffected)
				if (rows != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void mergeMessageMetadata(Connection txn, MessageId m,
			Metadata meta) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			Map<String, byte[]> added = removeOrUpdateMetadata(txn,
					m.getBytes(), meta, "messageMetadata", "messageId");
			if (added.isEmpty()) return;
			String sql = "SELECT groupId, state FROM messages"
					+ " WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			GroupId g = new GroupId(rs.getBytes(1));
			MessageState state = MessageState.fromValue(rs.getInt(2));
			rs.close();
			ps.close();
			sql = "INSERT INTO messageMetadata"
					+ " (messageId, groupId, state, metaKey, value)"
					+ " VALUES (?, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setBytes(2, g.getBytes());
			ps.setInt(3, state.getValue());
			for (Entry<String, byte[]> e : added.entrySet()) {
				ps.setString(4, e.getKey());
				ps.setBytes(5, e.getValue());
				ps.addBatch();
			}
			int[] batchAffected = ps.executeBatch();
			if (batchAffected.length != added.size())
				throw new DbStateException();
			for (int rows : batchAffected)
				if (rows != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}
	private Map<String, byte[]> removeOrUpdateMetadata(Connection txn,
			byte[] id, Metadata meta, String tableName, String columnName)
			throws DbException {
		PreparedStatement ps = null;
		try {
			List<String> removed = new ArrayList<>();
			Map<String, byte[]> notRemoved = new HashMap<>();
			for (Entry<String, byte[]> e : meta.entrySet()) {
				if (e.getValue() == REMOVE) removed.add(e.getKey());
				else notRemoved.put(e.getKey(), e.getValue());
			}
			if (!removed.isEmpty()) {
				String sql = "DELETE FROM " + tableName
						+ " WHERE " + columnName + " = ? AND metaKey = ?";
				ps = txn.prepareStatement(sql);
				ps.setBytes(1, id);
				for (String key : removed) {
					ps.setString(2, key);
					ps.addBatch();
				}
				int[] batchAffected = ps.executeBatch();
				if (batchAffected.length != removed.size())
					throw new DbStateException();
				for (int rows : batchAffected) {
					if (rows < 0) throw new DbStateException();
					if (rows > 1) throw new DbStateException();
				}
				ps.close();
			}
			if (notRemoved.isEmpty()) return Collections.emptyMap();
			String sql = "UPDATE " + tableName + " SET value = ?"
					+ " WHERE " + columnName + " = ? AND metaKey = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(2, id);
			for (Entry<String, byte[]> e : notRemoved.entrySet()) {
				ps.setBytes(1, e.getValue());
				ps.setString(3, e.getKey());
				ps.addBatch();
			}
			int[] batchAffected = ps.executeBatch();
			if (batchAffected.length != notRemoved.size())
				throw new DbStateException();
			for (int rows : batchAffected) {
				if (rows < 0) throw new DbStateException();
				if (rows > 1) throw new DbStateException();
			}
			ps.close();
			Map<String, byte[]> added = new HashMap<>();
			int updateIndex = 0;
			for (Entry<String, byte[]> e : notRemoved.entrySet()) {
				if (batchAffected[updateIndex++] == 0)
					added.put(e.getKey(), e.getValue());
			}
			return added;
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void mergeSettings(Connection txn, Settings s, String namespace)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE settings SET value = ?"
					+ " WHERE namespace = ? AND settingKey = ?";
			ps = txn.prepareStatement(sql);
			for (Entry<String, String> e : s.entrySet()) {
				ps.setString(1, e.getValue());
				ps.setString(2, namespace);
				ps.setString(3, e.getKey());
				ps.addBatch();
			}
			int[] batchAffected = ps.executeBatch();
			if (batchAffected.length != s.size()) throw new DbStateException();
			for (int rows : batchAffected) {
				if (rows < 0) throw new DbStateException();
				if (rows > 1) throw new DbStateException();
			}
			sql = "INSERT INTO settings (namespace, settingKey, value)"
					+ " VALUES (?, ?, ?)";
			ps = txn.prepareStatement(sql);
			int updateIndex = 0, inserted = 0;
			for (Entry<String, String> e : s.entrySet()) {
				if (batchAffected[updateIndex] == 0) {
					ps.setString(1, namespace);
					ps.setString(2, e.getKey());
					ps.setString(3, e.getValue());
					ps.addBatch();
					inserted++;
				}
				updateIndex++;
			}
			batchAffected = ps.executeBatch();
			if (batchAffected.length != inserted) throw new DbStateException();
			for (int rows : batchAffected)
				if (rows != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void raiseAckFlag(Connection txn, ContactId c, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE statuses SET ack = TRUE"
					+ " WHERE messageId = ? AND contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setInt(2, c.getInt());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void raiseRequestedFlag(Connection txn, ContactId c, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE statuses SET requested = TRUE"
					+ " WHERE messageId = ? AND contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setInt(2, c.getInt());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean raiseSeenFlag(Connection txn, ContactId c, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE statuses SET seen = TRUE"
					+ " WHERE messageId = ? AND contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setInt(2, c.getInt());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
			return affected == 1;
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removeContact(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM contacts WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removeGroup(Connection txn, GroupId g) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM groups WHERE groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removeGroupVisibility(Connection txn, ContactId c, GroupId g)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM groupVisibilities"
					+ " WHERE contactId = ? AND groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setBytes(2, g.getBytes());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
			sql = "DELETE FROM statuses"
					+ " WHERE contactId = ? AND groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setBytes(2, g.getBytes());
			affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removeIdentity(Connection txn, AuthorId a) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM localAuthors WHERE authorId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, a.getBytes());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removeMessage(Connection txn, MessageId m) throws DbException {
		PreparedStatement ps = null;
		try {

			String sql = "DELETE FROM offers WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.executeUpdate();
			ps.close();

			sql = "DELETE FROM statuses WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.executeUpdate();
			ps.close();

			sql = "DELETE FROM messageMetadata WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.executeUpdate();
			ps.close();

			sql = "DELETE FROM messageDependencies"
					+ " WHERE messageId = ? OR dependencyId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setBytes(2, m.getBytes());
			ps.executeUpdate();
			ps.close();

			sql = "DELETE FROM messages WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removeAllGroupMessages(Connection txn, GroupId g)
			throws DbException {
		PreparedStatement ps = null;
		try {

			String sql = "DELETE FROM offers WHERE messageId IN"
					+ " (SELECT messageId FROM messages WHERE groupId = ?)";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			ps.executeUpdate();
			ps.close();

			sql = "DELETE FROM statuses WHERE groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			ps.executeUpdate();
			ps.close();

			sql = "DELETE FROM messageMetadata WHERE groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			ps.executeUpdate();
			ps.close();

			sql = "DELETE FROM messageDependencies WHERE groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			ps.executeUpdate();
			ps.close();

			sql = "DELETE FROM messages WHERE groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, g.getBytes());
			ps.executeUpdate();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	private boolean removeOfferedMessage(Connection txn, ContactId c,
			MessageId m) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM offers"
					+ " WHERE contactId = ? AND messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setBytes(2, m.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
			return affected == 1;
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removeOfferedMessages(Connection txn, ContactId c,
			Collection<MessageId> requested) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM offers"
					+ " WHERE contactId = ? AND messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			for (MessageId m : requested) {
				ps.setBytes(2, m.getBytes());
				ps.addBatch();
			}
			int[] batchAffected = ps.executeBatch();
			if (batchAffected.length != requested.size())
				throw new DbStateException();
			for (int rows : batchAffected)
				if (rows != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removePendingContact(Connection txn, PendingContactId p)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM pendingContacts"
					+ " WHERE pendingContactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, p.getBytes());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removeTemporaryMessages(Connection txn) throws DbException {
		Statement s = null;
		try {

			String sql = "DELETE FROM offers WHERE messageId IN"
					+ " (SELECT messageId FROM messages WHERE temporary = TRUE)";
			s = txn.createStatement();
			s.executeUpdate(sql);
			s.close();

			sql = "DELETE FROM statuses WHERE messageId IN"
					+ " (SELECT messageId FROM messages WHERE temporary = TRUE)";
			s = txn.createStatement();
			s.executeUpdate(sql);
			s.close();

			sql = "DELETE FROM messageMetadata WHERE messageId IN"
					+ " (SELECT messageId FROM messages WHERE temporary = TRUE)";
			s = txn.createStatement();
			s.executeUpdate(sql);
			s.close();

			sql = "DELETE FROM messageDependencies WHERE messageId IN"
					+ " (SELECT messageId FROM messages WHERE temporary = TRUE)"
					+ " OR dependencyId IN"
					+ " (SELECT messageId FROM messages WHERE temporary = TRUE)";
			s = txn.createStatement();
			s.executeUpdate(sql);
			s.close();

			sql = "DELETE FROM messages WHERE temporary = TRUE";
			s = txn.createStatement();
			int affected = s.executeUpdate(sql);
			if (affected < 0) throw new DbStateException();
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}

	@Override
	public void removeTransport(Connection txn, TransportId t)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM transports WHERE transportId = ?";
			ps = txn.prepareStatement(sql);
			ps.setString(1, t.getString());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removeTransportKeys(Connection txn, TransportId t, KeySetId k)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM outgoingKeys"
					+ " WHERE transportId = ? AND keySetId = ?";
			ps = txn.prepareStatement(sql);
			ps.setString(1, t.getString());
			ps.setInt(2, k.getInt());
			int affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void resetExpiryTime(Connection txn, ContactId c, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE statuses SET expiry = 0, txCount = 0"
					+ " WHERE messageId = ? AND contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setInt(2, c.getInt());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void resetUnackedMessagesToSend(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE statuses SET expiry = 0, txCount = 0,"
					+ " maxLatency = NULL"
					+ " WHERE contactId = ? AND state = ?"
					+ " AND groupShared = TRUE AND messageShared = TRUE"
					+ " AND deleted = FALSE AND seen = FALSE";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, DELIVERED.getValue());
			int affected = ps.executeUpdate();
			if (affected < 0) {
				throw new DbStateException();
			}
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setCleanupTimerDuration(Connection txn, MessageId m,
			long duration) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE messages SET cleanupTimerDuration = ?"
					+ " WHERE messageId = ? AND cleanupTimerDuration IS NULL";
			ps = txn.prepareStatement(sql);
			ps.setLong(1, duration);
			ps.setBytes(2, m.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setContactVerified(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE contacts SET verified = ? WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBoolean(1, true);
			ps.setInt(2, c.getInt());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setContactAlias(Connection txn, ContactId c,
			@Nullable String alias) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE contacts SET alias = ? WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			if (alias == null) ps.setNull(1, VARCHAR);
			else ps.setString(1, alias);
			ps.setInt(2, c.getInt());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setContactPcsEnabled(Connection txn, ContactId c,
			boolean pcsEnabled) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE contacts SET pcsEnabled = ?"
					+ " WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBoolean(1, pcsEnabled);
			ps.setInt(2, c.getInt());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setGroupVisibility(Connection txn, ContactId c, GroupId g,
			boolean shared) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE groupVisibilities SET shared = ?"
					+ " WHERE contactId = ? AND groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBoolean(1, shared);
			ps.setInt(2, c.getInt());
			ps.setBytes(3, g.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
			sql = "UPDATE statuses SET groupShared = ?"
					+ " WHERE contactId = ? AND groupId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBoolean(1, shared);
			ps.setInt(2, c.getInt());
			ps.setBytes(3, g.getBytes());
			affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setHandshakeKeyPair(Connection txn, AuthorId local,
			PublicKey publicKey, PrivateKey privateKey) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE localAuthors"
					+ " SET handshakePublicKey = ?, handshakePrivateKey = ?"
					+ " WHERE authorId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, publicKey.getEncoded());
			ps.setBytes(2, privateKey.getEncoded());
			ps.setBytes(3, local.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setHybridHandshakeKeyPair(Connection txn, AuthorId local,
			PublicKey publicKey, PrivateKey privateKey) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE localAuthors"
					+ " SET hybridHandshakePublicKey = ?,"
					+ " hybridHandshakePrivateKey = ?"
					+ " WHERE authorId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, publicKey.getEncoded());
			ps.setBytes(2, privateKey.getEncoded());
			ps.setBytes(3, local.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setMlDsaSigKeyPair(Connection txn, AuthorId local,
			byte[] publicKey, byte[] privateKey) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE localAuthors"
					+ " SET mlDsaSigPublicKey = ?,"
					+ " mlDsaSigPrivateKey = ?"
					+ " WHERE authorId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, publicKey);
			ps.setBytes(2, privateKey);
			ps.setBytes(3, local.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setContactMlDsaSigPublicKey(Connection txn, ContactId c,
			byte[] publicKey) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE contacts"
					+ " SET mlDsaSigPublicKey = ?"
					+ " WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, publicKey);
			ps.setInt(2, c.getInt());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setMessagePermanent(Connection txn, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE messages SET temporary = FALSE"
					+ " WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setMessageShared(Connection txn, MessageId m, boolean shared)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE messages SET shared = ?"
					+ " WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBoolean(1, shared);
			ps.setBytes(2, m.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
			sql = "UPDATE statuses SET messageShared = ?"
					+ " WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBoolean(1, shared);
			ps.setBytes(2, m.getBytes());
			affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setMessageState(Connection txn, MessageId m, MessageState state)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE messages SET state = ? WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, state.getValue());
			ps.setBytes(2, m.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
			sql = "UPDATE messageMetadata SET state = ? WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, state.getValue());
			ps.setBytes(2, m.getBytes());
			affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			ps.close();
			sql = "UPDATE statuses SET state = ? WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, state.getValue());
			ps.setBytes(2, m.getBytes());
			affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			ps.close();
			sql = "UPDATE messageDependencies SET messageState = ?"
					+ " WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, state.getValue());
			ps.setBytes(2, m.getBytes());
			affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			ps.close();
			sql = "UPDATE messageDependencies SET dependencyState = ?"
					+ " WHERE dependencyId = ? AND dependencyState IS NOT NULL";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, state.getValue());
			ps.setBytes(2, m.getBytes());
			affected = ps.executeUpdate();
			if (affected < 0) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setReorderingWindow(Connection txn, KeySetId k,
			TransportId t, long timePeriod, long base, byte[] bitmap)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE incomingKeys SET base = ?, bitmap = ?"
					+ " WHERE transportId = ? AND keySetId = ?"
					+ " AND timePeriod = ?";
			ps = txn.prepareStatement(sql);
			ps.setLong(1, base);
			ps.setBytes(2, bitmap);
			ps.setString(3, t.getString());
			ps.setInt(4, k.getInt());
			ps.setLong(5, timePeriod);
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setSyncVersions(Connection txn, ContactId c,
			List<Byte> supported) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE contacts SET syncVersions = ?"
					+ " WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			byte[] bytes = new byte[supported.size()];
			for (int i = 0; i < bytes.length; i++) {
				bytes[i] = supported.get(i);
			}
			ps.setBytes(1, bytes);
			ps.setInt(2, c.getInt());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setTransportKeysActive(Connection txn, TransportId t,
			KeySetId k) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE outgoingKeys SET active = true"
					+ " WHERE transportId = ? AND keySetId = ?";
			ps = txn.prepareStatement(sql);
			ps.setString(1, t.getString());
			ps.setInt(2, k.getInt());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public long startCleanupTimer(Connection txn, MessageId m)
			throws DbException {
		long now = clock.currentTimeMillis();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "UPDATE messages"
					+ " SET cleanupDeadline = ? + cleanupTimerDuration"
					+ " WHERE messageId = ?"
					+ " AND cleanupTimerDuration IS NOT NULL"
					+ " AND cleanupDeadline IS NULL";
			ps = txn.prepareStatement(sql);
			ps.setLong(1, now);
			ps.setBytes(2, m.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
			if (affected == 0) return TIMER_NOT_STARTED;
			sql = "SELECT cleanupDeadline FROM messages WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			long deadline = rs.getLong(1);
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			return deadline;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void stopCleanupTimer(Connection txn, MessageId m)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE messages SET cleanupDeadline = NULL"
					+ " WHERE messageId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void updateRetransmissionData(Connection txn, ContactId c,
			MessageId m, long maxLatency) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT txCount FROM statuses"
					+ " WHERE messageId = ? AND contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, m.getBytes());
			ps.setInt(2, c.getInt());
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			int txCount = rs.getInt(1);
			if (rs.next()) throw new DbStateException();
			rs.close();
			ps.close();
			sql = "UPDATE statuses"
					+ " SET expiry = ?, txCount = txCount + 1, maxLatency = ?"
					+ " WHERE messageId = ? AND contactId = ?";
			ps = txn.prepareStatement(sql);
			long now = clock.currentTimeMillis();
			ps.setLong(1, calculateExpiry(now, maxLatency, txCount));
			ps.setLong(2, maxLatency);
			ps.setBytes(3, m.getBytes());
			ps.setInt(4, c.getInt());
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void updateTransportKeys(Connection txn, TransportKeySet ks)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "UPDATE outgoingKeys SET timePeriod = ?,"
					+ " tagKey = ?, headerKey = ?, stream = ?"
					+ " WHERE transportId = ? AND keySetId = ?";
			ps = txn.prepareStatement(sql);
			TransportKeys k = ks.getKeys();
			OutgoingKeys outCurr = k.getCurrentOutgoingKeys();
			ps.setLong(1, outCurr.getTimePeriod());
			ps.setBytes(2, outCurr.getTagKey().getBytes());
			ps.setBytes(3, outCurr.getHeaderKey().getBytes());
			ps.setLong(4, outCurr.getStreamCounter());
			ps.setString(5, k.getTransportId().getString());
			ps.setInt(6, ks.getKeySetId().getInt());
			int affected = ps.executeUpdate();
			if (affected < 0 || affected > 1) throw new DbStateException();
			ps.close();
			sql = "UPDATE incomingKeys SET timePeriod = ?,"
					+ " tagKey = ?, headerKey = ?, base = ?, bitmap = ?"
					+ " WHERE transportId = ? AND keySetId = ?"
					+ " AND periodOffset = ?";
			ps = txn.prepareStatement(sql);
			ps.setString(6, k.getTransportId().getString());
			ps.setInt(7, ks.getKeySetId().getInt());
			IncomingKeys inPrev = k.getPreviousIncomingKeys();
			ps.setLong(1, inPrev.getTimePeriod());
			ps.setBytes(2, inPrev.getTagKey().getBytes());
			ps.setBytes(3, inPrev.getHeaderKey().getBytes());
			ps.setLong(4, inPrev.getWindowBase());
			ps.setBytes(5, inPrev.getWindowBitmap());
			ps.setInt(8, OFFSET_PREV);
			ps.addBatch();
			IncomingKeys inCurr = k.getCurrentIncomingKeys();
			ps.setLong(1, inCurr.getTimePeriod());
			ps.setBytes(2, inCurr.getTagKey().getBytes());
			ps.setBytes(3, inCurr.getHeaderKey().getBytes());
			ps.setLong(4, inCurr.getWindowBase());
			ps.setBytes(5, inCurr.getWindowBitmap());
			ps.setInt(8, OFFSET_CURR);
			ps.addBatch();
			IncomingKeys inNext = k.getNextIncomingKeys();
			ps.setLong(1, inNext.getTimePeriod());
			ps.setBytes(2, inNext.getTagKey().getBytes());
			ps.setBytes(3, inNext.getHeaderKey().getBytes());
			ps.setLong(4, inNext.getWindowBase());
			ps.setBytes(5, inNext.getWindowBitmap());
			ps.setInt(8, OFFSET_NEXT);
			ps.addBatch();
			int[] batchAffected = ps.executeBatch();
			if (batchAffected.length != 3) throw new DbStateException();
			for (int rows : batchAffected)
				if (rows < 0 || rows > 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setPcsSessionState(Connection txn, ContactId c, int direction,
			SecretKey chainKey, int messageNumber, int previousChainLength)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM pcsSessionState"
					+ " WHERE contactId = ? AND direction = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, direction);
			ps.executeUpdate();
			ps.close();
			sql = "INSERT INTO pcsSessionState"
					+ " (contactId, direction, chainKey, messageNumber,"
					+ " previousChainLength)"
					+ " VALUES (?, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, direction);
			ps.setBytes(3, chainKey.getBytes());
			ps.setInt(4, messageNumber);
			ps.setInt(5, previousChainLength);
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	@Nullable
	public Object[] getPcsSessionState(Connection txn, ContactId c,
			int direction) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT chainKey, messageNumber, previousChainLength"
					+ " FROM pcsSessionState"
					+ " WHERE contactId = ? AND direction = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, direction);
			rs = ps.executeQuery();
			if (!rs.next()) {
				rs.close();
				ps.close();
				return null;
			}
			byte[] chainKeyBytes = rs.getBytes(1);
			int messageNumber = rs.getInt(2);
			int previousChainLength = rs.getInt(3);
			rs.close();
			ps.close();
			return new Object[]{chainKeyBytes, messageNumber, previousChainLength};
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsPcsSessionState(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT 1 FROM pcsSessionState WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			rs = ps.executeQuery();
			boolean found = rs.next();
			rs.close();
			ps.close();
			return found;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void addPcsSkippedKey(Connection txn, ContactId c, int direction,
			int messageNumber, SecretKey messageKey, long timestamp)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "INSERT INTO pcsSkippedKeys"
					+ " (contactId, direction, messageNumber, messageKey, timestamp)"
					+ " VALUES (?, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, direction);
			ps.setInt(3, messageNumber);
			ps.setBytes(4, messageKey.getBytes());
			ps.setLong(5, timestamp);
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	@Nullable
	public SecretKey getPcsSkippedKey(Connection txn, ContactId c,
			int direction, int messageNumber) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String selectSql = "SELECT messageKey FROM pcsSkippedKeys"
					+ " WHERE contactId = ? AND direction = ? AND messageNumber = ?";
			ps = txn.prepareStatement(selectSql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, direction);
			ps.setInt(3, messageNumber);
			rs = ps.executeQuery();
			if (!rs.next()) {
				rs.close();
				ps.close();
				return null;
			}
			byte[] keyBytes = rs.getBytes(1);
			rs.close();
			ps.close();
			String deleteSql = "DELETE FROM pcsSkippedKeys"
					+ " WHERE contactId = ? AND direction = ? AND messageNumber = ?";
			ps = txn.prepareStatement(deleteSql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, direction);
			ps.setInt(3, messageNumber);
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();

			return new SecretKey(keyBytes);
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public int getPcsSkippedKeyCount(Connection txn, ContactId c, int direction)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT COUNT(*) FROM pcsSkippedKeys"
					+ " WHERE contactId = ? AND direction = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, direction);
			rs = ps.executeQuery();
			if (!rs.next()) throw new DbStateException();
			int count = rs.getInt(1);
			rs.close();
			ps.close();
			return count;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public int prunePcsSkippedKeys(Connection txn, long maxAge)
			throws DbException {
		PreparedStatement ps = null;
		try {
			long threshold = clock.currentTimeMillis() - maxAge;
			String sql = "DELETE FROM pcsSkippedKeys WHERE timestamp < ?";
			ps = txn.prepareStatement(sql);
			ps.setLong(1, threshold);
			int affected = ps.executeUpdate();
			ps.close();
			return affected;
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removePcsState(Connection txn, ContactId c) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM pcsSkippedKeys WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.executeUpdate();
			ps.close();
			sql = "DELETE FROM pcsSessionState WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.executeUpdate();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setPcsMode2SessionState(Connection txn, ContactId c, int direction,
			SecretKey chainKey, int messageNumber, int previousChainLength,
			@Nullable SecretKey rootKey, @Nullable PrivateKey dhPrivateKey,
			@Nullable PublicKey dhPublicKey, @Nullable PublicKey dhRemotePublicKey,
			boolean mode2Enabled,
			@Nullable byte[] mode3FullStateBlob) throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM pcsSessionState"
					+ " WHERE contactId = ? AND direction = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, direction);
			ps.executeUpdate();
			ps.close();
			sql = "INSERT INTO pcsSessionState"
					+ " (contactId, direction, chainKey, messageNumber,"
					+ " previousChainLength, mode2Enabled, rootKey,"
					+ " dhPrivateKey, dhPublicKey, dhRemotePublicKey,"
					+ " mode3FullStateBlob)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, direction);
			ps.setBytes(3, chainKey.getBytes());
			ps.setInt(4, messageNumber);
			ps.setInt(5, previousChainLength);
			ps.setBoolean(6, mode2Enabled);
			ps.setBytes(7, rootKey != null ? rootKey.getBytes() : null);
			ps.setBytes(8, dhPrivateKey != null ? dhPrivateKey.getEncoded() : null);
			ps.setBytes(9, dhPublicKey != null ? dhPublicKey.getEncoded() : null);
			ps.setBytes(10, dhRemotePublicKey != null ? dhRemotePublicKey.getEncoded() : null);
			ps.setBytes(11, mode3FullStateBlob);
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	@Nullable
	public Object[] getPcsMode2SessionState(Connection txn, ContactId c,
			int direction) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT chainKey, messageNumber, previousChainLength,"
					+ " rootKey, dhPrivateKey, dhPublicKey, dhRemotePublicKey,"
					+ " mode2Enabled, mode3FullStateBlob"
					+ " FROM pcsSessionState"
					+ " WHERE contactId = ? AND direction = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setInt(2, direction);
			rs = ps.executeQuery();
			if (!rs.next()) {
				rs.close();
				ps.close();
				return null;
			}
			byte[] chainKeyBytes = rs.getBytes(1);
			int messageNumber = rs.getInt(2);
			int previousChainLength = rs.getInt(3);
			byte[] rootKeyBytes = rs.getBytes(4);
			byte[] dhPrivateKeyBytes = rs.getBytes(5);
			byte[] dhPublicKeyBytes = rs.getBytes(6);
			byte[] dhRemotePublicKeyBytes = rs.getBytes(7);
			boolean mode2Enabled = rs.getBoolean(8);
			byte[] mode3FullStateBlob = rs.getBytes(9);
			rs.close();
			ps.close();
			return new Object[]{
					chainKeyBytes, messageNumber, previousChainLength,
					rootKeyBytes, dhPrivateKeyBytes, dhPublicKeyBytes,
					dhRemotePublicKeyBytes, mode2Enabled, mode3FullStateBlob
			};
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void addPcsMode2SkippedKey(Connection txn, byte[] chainId,
			int messageNumber, SecretKey messageKey, long timestamp)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "INSERT INTO pcsSkippedKeys"
					+ " (contactId, direction, messageNumber, messageKey, timestamp, chainId)"
					+ " VALUES (0, 0, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, messageNumber);
			ps.setBytes(2, messageKey.getBytes());
			ps.setLong(3, timestamp);
			ps.setBytes(4, chainId);
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	@Nullable
	public SecretKey getPcsMode2SkippedKey(Connection txn, byte[] chainId,
			int messageNumber) throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT messageKey FROM pcsSkippedKeys"
					+ " WHERE chainId = ? AND messageNumber = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, chainId);
			ps.setInt(2, messageNumber);
			rs = ps.executeQuery();
			if (!rs.next()) {
				rs.close();
				ps.close();
				return null;
			}
			byte[] keyBytes = rs.getBytes(1);
			rs.close();
			ps.close();
			sql = "DELETE FROM pcsSkippedKeys"
					+ " WHERE chainId = ? AND messageNumber = ?";
			ps = txn.prepareStatement(sql);
			ps.setBytes(1, chainId);
			ps.setInt(2, messageNumber);
			ps.executeUpdate();
			ps.close();

			return new SecretKey(keyBytes);
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void setPqRatchetState(Connection txn, ContactId c, long currentEpoch,
			long epochStartTime, int messagesSinceEpoch, int state,
			boolean isInitiator, int chunksSent, int chunksReceived,
			@Nullable byte[] ourEkSeed, @Nullable byte[] ourEkVector,
			@Nullable byte[] ourDecapsKey, @Nullable byte[] theirEkSeed,
			@Nullable byte[] theirEkHash, @Nullable byte[] theirEkVector,
			@Nullable byte[] ciphertext, @Nullable byte[] pendingChunks)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM pqRatchetState"
					+ " WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.executeUpdate();
			ps.close();
			sql = "INSERT INTO pqRatchetState"
					+ " (contactId, currentEpoch, epochStartTime,"
					+ " messagesSinceEpoch, state, isInitiator,"
					+ " chunksSent, chunksReceived, ourEkSeed,"
					+ " ourEkVector, ourDecapsKey, theirEkSeed,"
					+ " theirEkHash, theirEkVector, ciphertext,"
					+ " pendingChunks)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.setLong(2, currentEpoch);
			ps.setLong(3, epochStartTime);
			ps.setInt(4, messagesSinceEpoch);
			ps.setInt(5, state);
			ps.setBoolean(6, isInitiator);
			ps.setInt(7, chunksSent);
			ps.setInt(8, chunksReceived);
			ps.setBytes(9, ourEkSeed);
			ps.setBytes(10, ourEkVector);
			ps.setBytes(11, ourDecapsKey);
			ps.setBytes(12, theirEkSeed);
			ps.setBytes(13, theirEkHash);
			ps.setBytes(14, theirEkVector);
			ps.setBytes(15, ciphertext);
			ps.setBytes(16, pendingChunks);
			int affected = ps.executeUpdate();
			if (affected != 1) throw new DbStateException();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	@Nullable
	public Object[] getPqRatchetState(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT currentEpoch, epochStartTime, messagesSinceEpoch,"
					+ " state, isInitiator, chunksSent, chunksReceived,"
					+ " ourEkSeed, ourEkVector, ourDecapsKey,"
					+ " theirEkSeed, theirEkHash, theirEkVector,"
					+ " ciphertext, pendingChunks"
					+ " FROM pqRatchetState"
					+ " WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			rs = ps.executeQuery();
			if (!rs.next()) {
				rs.close();
				ps.close();
				return null;
			}
			Object[] result = new Object[]{
					rs.getLong(1),
					rs.getLong(2),
					rs.getInt(3),
					rs.getInt(4),
					rs.getBoolean(5),
					rs.getInt(6),
					rs.getInt(7),
					rs.getBytes(8),
					rs.getBytes(9),
					rs.getBytes(10),
					rs.getBytes(11),
					rs.getBytes(12),
					rs.getBytes(13),
					rs.getBytes(14),
					rs.getBytes(15)
			};
			rs.close();
			ps.close();
			return result;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public boolean containsPqRatchetState(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			String sql = "SELECT 1 FROM pqRatchetState WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			rs = ps.executeQuery();
			boolean exists = rs.next();
			rs.close();
			ps.close();
			return exists;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	@Override
	public void removePqRatchetState(Connection txn, ContactId c)
			throws DbException {
		PreparedStatement ps = null;
		try {
			String sql = "DELETE FROM pqRatchetState WHERE contactId = ?";
			ps = txn.prepareStatement(sql);
			ps.setInt(1, c.getInt());
			ps.executeUpdate();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}
}
