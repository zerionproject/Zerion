package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.system.Clock;
import org.junit.Ignore;

@Ignore
public class HyperSqlDatabasePerformanceTest
		extends SingleDatabasePerformanceTest {

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
