package org.zerionproject.core.db;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.TestDatabaseConfig;
import org.zerionproject.core.test.TestMessageFactory;
import org.zerionproject.core.test.UTest;

import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getMean;
import static org.zerionproject.core.test.TestUtils.getMedian;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getStandardDeviation;
import static org.zerionproject.core.test.UTest.Z_CRITICAL_0_01;

public abstract class DatabasePerformanceComparisonTest
		extends DatabasePerformanceTest {

	private static final int COMPARISON_BLOCKS = 10;
	private SecretKey databaseKey = getSecretKey();

	abstract Database<Connection> createDatabase(boolean conditionA,
			DatabaseConfig databaseConfig, MessageFactory messageFactory,
			Clock clock);

	@Override
	protected void benchmark(String name,
			BenchmarkTask<Database<Connection>> task) throws Exception {
		List<Double> aDurations = new ArrayList<>();
		List<Double> bDurations = new ArrayList<>();
		boolean aFirst = true;
		for (int i = 0; i < COMPARISON_BLOCKS; i++) {

			if (aFirst) {
				aDurations.addAll(benchmark(true, task).durations);
				bDurations.addAll(benchmark(false, task).durations);
			} else {
				bDurations.addAll(benchmark(false, task).durations);
				aDurations.addAll(benchmark(true, task).durations);
			}
			aFirst = !aFirst;
		}

		UTest.Result comparison = UTest.test(aDurations, bDurations,
				Z_CRITICAL_0_01);
		writeResult(name, aDurations, bDurations, comparison);
	}

	private SteadyStateResult benchmark(boolean conditionA,
			BenchmarkTask<Database<Connection>> task) throws Exception {
		deleteTestDirectory(testDir);
		Database<Connection> db = openDatabase(conditionA);
		populateDatabase(db);
		db.close();
		db = openDatabase(conditionA);

		SteadyStateResult result = measureSteadyState(db, task);
		db.close();
		return result;
	}

	private Database<Connection> openDatabase(boolean conditionA)
			throws DbException {
		Database<Connection> db = createDatabase(conditionA,
				new TestDatabaseConfig(testDir), new TestMessageFactory(),
				new SystemClock());
		db.open(databaseKey, null);
		return db;
	}

	private void writeResult(String name, List<Double> aDurations,
			List<Double> bDurations, UTest.Result comparison)
			throws IOException {
		String result = String.format("%s\t%,d\t%,d\t%,d\t%,d\t%,d\t%,d\t%s",
				name, (long) getMean(aDurations), (long) getMedian(aDurations),
				(long) getStandardDeviation(aDurations),
				(long) getMean(bDurations), (long) getMedian(bDurations),
				(long) getStandardDeviation(bDurations),
				comparison.name());
		writeResult(result);
	}
}
