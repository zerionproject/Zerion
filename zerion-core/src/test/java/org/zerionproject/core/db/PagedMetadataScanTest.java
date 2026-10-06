package org.zerionproject.core.db;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbException;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.Math.max;
import static org.zerionproject.core.api.sync.validation.MessageState.DELIVERED;
import static org.zerionproject.core.db.JdbcDatabase.METADATA_PAGE_ROWS;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class PagedMetadataScanTest extends BrambleTestCase {

	private static final byte[] EXCLUDED = {0x21, 0x20};
	private static final byte[] KEPT = {0x21, 0x00};

	private final File testDir = getTestDirectory();
	private final SecretKey key = getSecretKey();
	private final ClientId clientId = getClientId();
	private final Group group = getGroup(clientId, 123);
	private final GroupId groupId = group.getId();
	private final List<AtomicLong> rowsPerQuery = new ArrayList<>();

	private JdbcDatabase db;

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
		db.commitTransaction(txn);
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	@Test
	public void noQueryHandsBackMoreRowsThanAPage() throws Exception {
		int messages = 3 * METADATA_PAGE_ROWS;
		Connection txn = db.startTransaction();
		for (int i = 0; i < messages; i++) {
			add(txn, metadata("type", i % 50 == 0 ? EXCLUDED : KEPT,
					"text", getRandomBytes(8)));
		}
		db.commitTransaction(txn);

		txn = db.startTransaction();
		Map<MessageId, Metadata> whole = db.getMessageMetadata(txn, groupId);
		rowsPerQuery.clear();
		Map<MessageId, Metadata> visited = visit(txn);
		db.commitTransaction(txn);

		long largest = 0;
		for (AtomicLong rows : rowsPerQuery) largest = max(largest, rows.get());
		assertTrue("a query handed back " + largest + " rows of a chat of "
						+ 2 * messages + " rows",
				largest <= METADATA_PAGE_ROWS);
		assertSameMetadata(withoutExcluded(whole), visited);
	}

	@Test
	public void messagesCutOffByAPageAreVisitedOnceAndWhole()
			throws Exception {
		Random random = new Random(20261001L);
		Connection txn = db.startTransaction();
		for (int i = 0; i < 4000; i++) {
			Metadata m = metadata("type", random.nextInt(9) == 0
					? EXCLUDED : KEPT);
			int keys = 1 + random.nextInt(4);
			for (int k = 0; k < keys; k++) {
				m.put("k" + k, getRandomBytes(1 + random.nextInt(16)));
			}
			add(txn, m);
		}
		Metadata wide = metadata("type", KEPT);
		for (int k = 0; k < METADATA_PAGE_ROWS + 10; k++) {
			wide.put("w" + k, getRandomBytes(2));
		}
		MessageId wideId = add(txn, wide);
		db.commitTransaction(txn);

		txn = db.startTransaction();
		Map<MessageId, Metadata> whole = db.getMessageMetadata(txn, groupId);
		Map<MessageId, Metadata> visited = visit(txn);
		db.commitTransaction(txn);

		assertEquals(METADATA_PAGE_ROWS + 11,
				visited.get(wideId).keySet().size());
		assertSameMetadata(withoutExcluded(whole), visited);
	}

	@Test
	public void theMigrationAddsTheIndexToAnExistingDatabase()
			throws Exception {
		Connection txn = db.startTransaction();
		for (int i = 0; i < 10; i++) add(txn, metadata("type", KEPT));
		Statement s = txn.createStatement();
		s.execute("DROP INDEX messageMetadataByGroupIdStateMessageId");
		s.close();
		db.commitTransaction(txn);
		txn = db.startTransaction();
		assertEquals(0, indexCount(txn));

		Migration68_69 migration = new Migration68_69();
		assertEquals(68, migration.getStartVersion());
		assertEquals(69, migration.getEndVersion());
		migration.migrate(txn);
		migration.migrate(txn);
		db.commitTransaction(txn);

		txn = db.startTransaction();
		assertTrue(indexCount(txn) > 0);
		assertEquals(10, visit(txn).size());
		db.commitTransaction(txn);
	}

	private int indexCount(Connection txn) throws SQLException {
		PreparedStatement ps = txn.prepareStatement("SELECT COUNT(*)"
				+ " FROM INFORMATION_SCHEMA.SYSTEM_INDEXINFO"
				+ " WHERE UPPER(INDEX_NAME)"
				+ " = 'MESSAGEMETADATABYGROUPIDSTATEMESSAGEID'");
		ResultSet rs = ps.executeQuery();
		rs.next();
		int n = rs.getInt(1);
		rs.close();
		ps.close();
		return n;
	}

	private Map<MessageId, Metadata> visit(Connection txn) throws Exception {
		Map<MessageId, Metadata> visited = new HashMap<>();
		db.visitMessageMetadataExcluding(txn, groupId, "type", EXCLUDED,
				(m, meta) -> assertNull(visited.put(m, meta)));
		return visited;
	}

	private static Map<MessageId, Metadata> withoutExcluded(
			Map<MessageId, Metadata> whole) {
		Map<MessageId, Metadata> kept = new HashMap<>();
		for (Map.Entry<MessageId, Metadata> e : whole.entrySet()) {
			byte[] type = e.getValue().get("type");
			if (type == null || !java.util.Arrays.equals(type, EXCLUDED)) {
				kept.put(e.getKey(), e.getValue());
			}
		}
		return kept;
	}

	private static void assertSameMetadata(Map<MessageId, Metadata> expected,
			Map<MessageId, Metadata> visited) {
		assertEquals(expected.keySet(), visited.keySet());
		for (Map.Entry<MessageId, Metadata> e : expected.entrySet()) {
			Metadata got = visited.get(e.getKey());
			assertEquals(e.getValue().keySet(), got.keySet());
			for (String k : got.keySet()) {
				assertArrayEquals(e.getValue().get(k), got.get(k));
			}
		}
	}

	private MessageId add(Connection txn, Metadata meta) throws Exception {
		Message m = getMessage(groupId);
		db.addMessage(txn, m, DELIVERED, true, false, null);
		db.mergeMessageMetadata(txn, m.getId(), meta);
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
							.contains("FROM messageMetadata")
							&& ((String) args[0]).contains("OCTET_LENGTH")) {
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
						AtomicLong rows = new AtomicLong();
						synchronized (rowsPerQuery) {
							rowsPerQuery.add(rows);
						}
						return recording((ResultSet) result, rows);
					}
					return result;
				});
	}

	private ResultSet recording(ResultSet rs, AtomicLong rows) {
		return (ResultSet) Proxy.newProxyInstance(
				ResultSet.class.getClassLoader(),
				new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
					Object result = invoke(method, rs, args);
					if (method.getName().equals("next")
							&& Boolean.TRUE.equals(result)) {
						rows.incrementAndGet();
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
