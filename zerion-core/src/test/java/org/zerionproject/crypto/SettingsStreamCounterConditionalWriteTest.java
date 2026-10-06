package org.zerionproject.crypto;

import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.wire.ZwfConstants.DIRECTION_RECV;

public class SettingsStreamCounterConditionalWriteTest
		extends BrambleMockTestCase {

	private static final String NAMESPACE =
			"org.zerionproject.zwf.streamCounter";

	private final DatabaseComponent db =
			context.mock(DatabaseComponent.class);
	private final SettingsManager settingsManager =
			context.mock(SettingsManager.class);
	private final Transaction txn = new Transaction(null, false);

	@Test
	public void theConditionIsEvaluatedInsideTheWriteTransaction()
			throws Exception {
		AtomicBoolean evaluated = new AtomicBoolean(false);
		Settings expected = new Settings();
		expected.putLong("5." + DIRECTION_RECV, 42);
		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(false),
					withDbCallable(txn));
			oneOf(settingsManager).mergeSettings(txn, expected, NAMESPACE);
		}});
		SettingsStreamCounterStore store =
				new SettingsStreamCounterStore(db, settingsManager);
		assertTrue(store.storeHighWaterIf(5, DIRECTION_RECV, 42, () -> {
			evaluated.set(true);
			return true;
		}));
		assertTrue(evaluated.get());
	}

	@Test
	public void aRefusedWriteWritesNothing() throws Exception {
		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(false),
					withDbCallable(txn));
			never(settingsManager).mergeSettings(with(any(Transaction.class)),
					with(any(Settings.class)), with(any(String.class)));
		}});
		SettingsStreamCounterStore store =
				new SettingsStreamCounterStore(db, settingsManager);
		assertFalse(store.storeHighWaterIf(5, DIRECTION_RECV, 42,
				() -> false));
	}
}
