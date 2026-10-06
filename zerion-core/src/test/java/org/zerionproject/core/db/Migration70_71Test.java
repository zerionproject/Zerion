package org.zerionproject.core.db;

import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfig;
import org.zerionproject.core.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.test.TestUtils.isCryptoStrengthUnlimited;

public class Migration70_71Test extends BrambleTestCase {

	private static final String DROPPED = "MESSAGEMETADATABYGROUPIDSTATE";
	private static final String KEPT =
			"MESSAGEMETADATABYGROUPIDSTATEMESSAGEID";

	private final File testDir = getTestDirectory();
	private HyperSqlDatabase db;

	@Before
	public void setUp() {
		assumeTrue(isCryptoStrengthUnlimited());
		assertTrue(testDir.mkdirs());
		db = new HyperSqlDatabase(new TestDatabaseConfig(testDir),
				new TestMessageFactory(), new SystemClock());
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	@Test
	public void theRedundantIndexIsDroppedAndTheReplacementKept()
			throws Exception {
		db.open(getSecretKey(), null);
		Connection txn = db.startTransaction();
		assertFalse("a new database has the dropped index",
				indexNames(txn).contains(DROPPED));
		Statement s = txn.createStatement();
		s.execute("CREATE INDEX messageMetadataByGroupIdState"
				+ " ON messageMetadata (groupId, state)");
		s.close();
		assertTrue(indexNames(txn).contains(DROPPED));

		for (int run = 0; run < 2; run++) {
			new Migration70_71().migrate(txn);
			Set<String> names = indexNames(txn);
			assertFalse("the redundant index is still there after run "
					+ run, names.contains(DROPPED));
			assertTrue("the replacement index is gone after run " + run,
					names.contains(KEPT));
		}
		db.commitTransaction(txn);
	}

	private static Set<String> indexNames(Connection txn) throws Exception {
		Set<String> names = new HashSet<>();
		PreparedStatement ps = txn.prepareStatement("SELECT INDEX_NAME FROM"
				+ " INFORMATION_SCHEMA.SYSTEM_INDEXINFO"
				+ " WHERE TABLE_NAME = 'MESSAGEMETADATA'");
		ResultSet rs = ps.executeQuery();
		while (rs.next()) names.add(rs.getString(1).toUpperCase());
		rs.close();
		ps.close();
		return names;
	}
}
