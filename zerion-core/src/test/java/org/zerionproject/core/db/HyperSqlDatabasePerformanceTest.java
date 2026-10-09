package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.system.Clock;
import org.junit.BeforeClass;

import static org.zerionproject.core.test.TestUtils.isOptionalTestEnabled;
import static org.junit.Assume.assumeTrue;

public class HyperSqlDatabasePerformanceTest
		extends SingleDatabasePerformanceTest {

	@BeforeClass
	public static void runOnlyWhenRequested() {
		assumeTrue(isOptionalTestEnabled(
				HyperSqlDatabasePerformanceTest.class));
	}

	@Override
	protected String getTestName() {
		return getClass().getSimpleName();
	}

	@Override
	protected JdbcDatabase createDatabase(DatabaseConfig config,
			MessageFactory messageFactory, Clock clock) {
		return new HyperSqlDatabase(config, messageFactory, clock);
	}
}
