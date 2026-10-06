package org.zerionproject.core.db;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfig;
import org.zerionproject.core.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.util.HashSet;
import java.util.Set;

import static org.zerionproject.core.api.sync.validation.MessageState.DELIVERED;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.test.TestUtils.isCryptoStrengthUnlimited;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class MetadataEqualityStrictnessTest extends BrambleTestCase {

	private static final byte[] EXCLUDED = {0x21, 0x20};

	private final File testDir = getTestDirectory();
	private final SecretKey key = getSecretKey();
	private final ClientId clientId = getClientId();
	private final Group group = getGroup(clientId, 123);
	private final GroupId groupId = group.getId();

	private JdbcDatabase db;

	@Before
	public void setUp() throws Exception {
		assumeTrue(isCryptoStrengthUnlimited());
		deleteTestDirectory(testDir);
		db = new HyperSqlDatabase(new TestDatabaseConfig(testDir),
				new TestMessageFactory(), new SystemClock());
		db.open(key, null);
		Connection txn = db.startTransaction();
		db.addGroup(txn, group);
		db.commitTransaction(txn);
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	@Test
	public void aValueThatDiffersInANonPaddingByteIsToldApartEverywhere()
			throws Exception {
		Connection txn = db.startTransaction();
		MessageId excluded = add(txn, "type", EXCLUDED);
		MessageId other = add(txn, "type", new byte[] {0x21, 0x21});
		MessageId shorter = add(txn, "type", new byte[] {0x21});
		Set<MessageId> visited = visit(txn, "type", EXCLUDED);
		db.commitTransaction(txn);
		assertFalse(visited.contains(excluded));
		assertTrue(visited.contains(other));
		assertTrue("a shorter value is another value on both databases",
				visited.contains(shorter));
	}

	@Test
	public void hyperSqlPadsValuesWithZeroBytesAndKeysWithSpaces()
			throws Exception {
		Connection txn = db.startTransaction();
		MessageId zeroPadded = add(txn, "type",
				new byte[] {0x21, 0x20, 0x00});
		MessageId spacePaddedKey = add(txn, "type ", EXCLUDED);
		MessageId exact = add(txn, "type", EXCLUDED);
		Set<MessageId> visited = visit(txn, "type", EXCLUDED);
		db.commitTransaction(txn);
		assertFalse(visited.contains(exact));
		assertEquals("HyperSQL treats a zero-padded value as equal "
						+ "(SQLite would not); if this changes, update the"
						+ " documentation of the JVM test database",
				false, visited.contains(zeroPadded));
		assertEquals("HyperSQL treats a space-padded key as equal "
						+ "(SQLite would not); if this changes, update the"
						+ " documentation of the JVM test database",
				false, visited.contains(spacePaddedKey));
	}

	private Set<MessageId> visit(Connection txn, String key, byte[] value)
			throws Exception {
		Set<MessageId> visited = new HashSet<>();
		db.visitMessageMetadataExcluding(txn, groupId, key, value,
				(m, meta) -> visited.add(m));
		return visited;
	}

	private MessageId add(Connection txn, String key, byte[] value)
			throws Exception {
		Message m = getMessage(groupId);
		db.addMessage(txn, m, DELIVERED, true, false, null);
		Metadata meta = new Metadata();
		meta.put(key, value);
		db.mergeMessageMetadata(txn, m.getId(), meta);
		return m.getId();
	}
}
