package org.zerionproject.core.db;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.TestDatabaseConfig;
import org.zerionproject.core.test.TestMessageFactory;

import java.io.IOException;
import java.sql.Connection;
import java.util.List;

import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getMean;
import static org.zerionproject.core.test.TestUtils.getMedian;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getStandardDeviation;

public abstract class SingleDatabasePerformanceTest
		extends DatabasePerformanceTest {

	abstract Database<Connection> createDatabase(DatabaseConfig databaseConfig,
			MessageFactory messageFactory, Clock clock);

	private SecretKey databaseKey = getSecretKey();

	@Override
	protected void benchmark(String name,
			BenchmarkTask<Database<Connection>> task) throws Exception {
		deleteTestDirectory(testDir);
		Database<Connection> db = openDatabase();
		populateDatabase(db);
		db.close();
		db = openDatabase();

		long firstDuration = measureOne(db, task);

		SteadyStateResult result = measureSteadyState(db, task);
		db.close();
		writeResult(name, result.blocks, firstDuration, result.durations);
	}

	private Database<Connection> openDatabase() throws DbException {
		Database<Connection> db = createDatabase(
				new TestDatabaseConfig(testDir), new TestMessageFactory(),
				new SystemClock());
		db.open(databaseKey, null);
		return db;
	}

	private void writeResult(String name, int blocks, long firstDuration,
			List<Double> durations) throws IOException {
		String result = String.format("%s\t%d\t%,d\t%,d\t%,d\t%,d", name,
				blocks, firstDuration, (long) getMean(durations),
				(long) getMedian(durations),
				(long) getStandardDeviation(durations));
		writeResult(result);
	}
}
