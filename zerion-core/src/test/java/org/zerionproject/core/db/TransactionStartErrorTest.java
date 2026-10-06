package org.zerionproject.core.db;

import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.ShutdownManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.concurrent.Executor;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class TransactionStartErrorTest extends BrambleMockTestCase {

	@SuppressWarnings("unchecked")
	private final Database<Object> database = context.mock(Database.class);
	private final ShutdownManager shutdownManager =
			context.mock(ShutdownManager.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final Executor eventExecutor = context.mock(Executor.class);

	private static final class StartFailure extends Error {
	}

	@Test
	public void anErrorWhileStartingAWriteReleasesTheLock() throws Exception {
		Object txn = new Object();
		context.checking(new Expectations() {{
			oneOf(database).startTransaction();
			will(throwException(new StartFailure()));
			oneOf(database).startTransaction();
			will(returnValue(txn));
		}});
		DatabaseComponentImpl<Object> db = new DatabaseComponentImpl<>(
				database, Object.class, eventBus, eventExecutor,
				shutdownManager);
		try {
			db.startTransaction(false);
			fail();
		} catch (StartFailure expected) {
		}
		Transaction next = db.startTransaction(true);
		assertNotNull("the next transaction starts", next);
	}
}
