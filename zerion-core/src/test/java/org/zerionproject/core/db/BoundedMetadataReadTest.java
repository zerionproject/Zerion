package org.zerionproject.core.db;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfig;
import org.zerionproject.core.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.Math.max;
import static org.zerionproject.core.api.sync.validation.MessageState.DELIVERED;
import static org.zerionproject.core.api.sync.validation.MessageState.PENDING;
import static org.zerionproject.core.db.JdbcDatabase.MAX_METADATA_BATCH_BYTES;
import static org.zerionproject.core.db.JdbcDatabase.MAX_METADATA_BATCH_MESSAGES;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.test.TestUtils.isCryptoStrengthUnlimited;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

public class BoundedMetadataReadTest extends BrambleTestCase {

	private static final byte[] EXCLUDED = {0x21, 0x20};
	private static final byte[] KEPT = {0x21, 0x00};
	private static final int SMALL_MESSAGES = 3 * MAX_METADATA_BATCH_MESSAGES;
	private static final int MEDIUM_MESSAGES = 150;
	private static final int MEDIUM_VALUE = 20 * 1024;
	private static final int LARGE_KEYS = 24;
	private static final int LARGE_VALUE = 50 * 1024;
	private static final int EXCLUDED_MESSAGES = 10;
	private static final int EXCLUDED_VALUE = 60 * 1024;
	private static final long ROW_OVERHEAD = 64 * 1024;

	private final File testDir = getTestDirectory();
	private final SecretKey key = getSecretKey();
	private final ClientId clientId = getClientId();
	private final Group group = getGroup(clientId, 123);
	private final Group otherGroup = getGroup(clientId, 123);
	private final GroupId groupId = group.getId();
	private final List<AtomicLong> metadataQueries = new ArrayList<>();
	private final List<MessageId> excluded = new ArrayList<>();

	private JdbcDatabase db;
	private long largestMessage = 0;
	private long totalKept = 0;

	@Before
	public void setUp() throws Exception {
		assumeTrue(isCryptoStrengthUnlimited());
		deleteTestDirectory(testDir);
		db = new HyperSqlDatabase(new TestDatabaseConfig(testDir),
				new TestMessageFactory(), new SystemClock()) {
			@Override
			protected Connection createConnection()
					throws DbException, SQLException {
				return recording(super.createConnection());
			}
		};
		db.open(key, null);
		Connection txn = db.startTransaction();
		db.addGroup(txn, group);
		db.addGroup(txn, otherGroup);
		for (int i = 0; i < SMALL_MESSAGES; i++) {
			add(txn, groupId, DELIVERED, metadata("type", KEPT,
					"text", getRandomBytes(40 + i % 50)));
		}
		for (int i = 0; i < MEDIUM_MESSAGES; i++) {
			add(txn, groupId, DELIVERED, metadata("type", KEPT,
					"body", getRandomBytes(MEDIUM_VALUE)));
		}
		Metadata large = metadata("type", KEPT);
		for (int i = 0; i < LARGE_KEYS; i++) {
			large.put("part" + i, getRandomBytes(LARGE_VALUE));
		}
		add(txn, groupId, DELIVERED, large);
		for (int i = 0; i < EXCLUDED_MESSAGES; i++) {
			excluded.add(add(txn, groupId, DELIVERED, metadata("type",
					EXCLUDED, "body", getRandomBytes(EXCLUDED_VALUE))));
		}
		add(txn, groupId, PENDING, metadata("type", KEPT));
		add(txn, otherGroup.getId(), DELIVERED, metadata("type", KEPT));
		db.commitTransaction(txn);
		metadataQueries.clear();
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	@Test
	public void eachQueryHandsBackAtMostABatchWhileEveryMessageIsVisited()
			throws Exception {
		Connection txn = db.startTransaction();
		Map<MessageId, Metadata> whole = db.getMessageMetadata(txn, groupId);
		for (MessageId m : excluded) assertTrue(whole.remove(m) != null);
		metadataQueries.clear();

		Map<MessageId, Metadata> visited = new HashMap<>();
		db.visitMessageMetadataExcluding(txn, groupId, "type", EXCLUDED,
				(m, meta) -> assertNull(visited.put(m, meta)));
		db.commitTransaction(txn);

		assertEquals(SMALL_MESSAGES + MEDIUM_MESSAGES + 1, whole.size());
		assertEquals(whole.keySet(), visited.keySet());
		for (Map.Entry<MessageId, Metadata> e : whole.entrySet()) {
			Metadata got = visited.get(e.getKey());
			assertEquals(e.getValue().keySet(), got.keySet());
			for (String k : got.keySet()) {
				assertArrayEquals(e.getValue().get(k), got.get(k));
			}
		}
		long largestQuery = 0;
		for (AtomicLong q : metadataQueries) {
			largestQuery = max(largestQuery, q.get());
		}
		long bound = max(MAX_METADATA_BATCH_BYTES, largestMessage)
				+ ROW_OVERHEAD;
		String reported = "queries " + metadataQueries.size()
				+ ", largest " + largestQuery + " bytes, bound " + bound
				+ ", whole group " + totalKept + " bytes";
		assertTrue(reported, totalKept > 3 * bound);
		assertTrue(reported, largestQuery <= bound);
		long batchesByBytes = totalKept / MAX_METADATA_BATCH_BYTES + 2;
		long batchesByCount = whole.size() / MAX_METADATA_BATCH_MESSAGES + 2;
		assertTrue(reported,
				metadataQueries.size() <= 1 + batchesByBytes + batchesByCount);
	}

	@Test
	public void theVisitorCanReadTheStoreAndItsExceptionsPassThrough()
			throws Exception {
		Connection txn = db.startTransaction();
		DbException thrown = new DbException();
		int[] seen = new int[1];
		try {
			db.visitMessageMetadataExcluding(txn, groupId, "type", EXCLUDED,
					(m, meta) -> {
						Metadata again = db.getMessageMetadata(txn, m);
						assertEquals(meta.keySet(), again.keySet());
						if (++seen[0] == MAX_METADATA_BATCH_MESSAGES + 5) {
							throw thrown;
						}
					});
			fail();
		} catch (DbException e) {
			assertSame(thrown, e);
		}
		assertEquals(MAX_METADATA_BATCH_MESSAGES + 5, seen[0]);
		Map<MessageId, Metadata> visited = new HashMap<>();
		db.visitMessageMetadataExcluding(txn, groupId, "type", EXCLUDED,
				(m, meta) -> visited.put(m, meta));
		assertEquals(SMALL_MESSAGES + MEDIUM_MESSAGES + 1, visited.size());
		db.commitTransaction(txn);
	}

	private MessageId add(Connection txn, GroupId g, MessageState state,
			Metadata meta) throws Exception {
		Message m = getMessage(g);
		db.addMessage(txn, m, state, true, false, null);
		db.mergeMessageMetadata(txn, m.getId(), meta);
		if (g.equals(groupId) && state == DELIVERED
				&& Arrays.equals(meta.get("type"), KEPT)) {
			long size = 0;
			for (byte[] v : meta.values()) size += v.length;
			largestMessage = max(largestMessage, size);
			totalKept += size;
		}
		return m.getId();
	}

	private static Metadata metadata(Object... entries) {
		Metadata m = new Metadata();
		for (int i = 0; i < entries.length; i += 2) {
			m.put((String) entries[i], (byte[]) entries[i + 1]);
		}
		return m;
	}

	private Connection recording(Connection c) {
		return (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(),
				new Class<?>[] {Connection.class}, (proxy, method, args) -> {
					Object result = invoke(method, c, args);
					if (method.getName().equals("prepareStatement")
							&& args != null && args[0] instanceof String
							&& ((String) args[0])
							.contains("FROM messageMetadata")) {
						return recording((PreparedStatement) result);
					}
					return result;
				});
	}

	private PreparedStatement recording(PreparedStatement ps) {
		return (PreparedStatement) Proxy.newProxyInstance(
				PreparedStatement.class.getClassLoader(),
				new Class<?>[] {PreparedStatement.class},
				(proxy, method, args) -> {
					Object result = invoke(method, ps, args);
					if (method.getName().equals("executeQuery")) {
						AtomicLong bytes = new AtomicLong();
						synchronized (metadataQueries) {
							metadataQueries.add(bytes);
						}
						return recording((ResultSet) result, bytes);
					}
					return result;
				});
	}

	private ResultSet recording(ResultSet rs, AtomicLong bytes) {
		return (ResultSet) Proxy.newProxyInstance(
				ResultSet.class.getClassLoader(),
				new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
					Object result = invoke(method, rs, args);
					if (result instanceof byte[]) {
						bytes.addAndGet(((byte[]) result).length);
					} else if (result instanceof String) {
						bytes.addAndGet(((String) result).length());
					}
					return result;
				});
	}

	private static Object invoke(Method method,
			Object target, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}
}
