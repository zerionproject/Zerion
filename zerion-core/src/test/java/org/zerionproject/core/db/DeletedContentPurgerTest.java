package org.zerionproject.core.db;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.event.PurgeRequestedEvent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.briarproject.nullsafety.NotNullByDefault;
import org.jmock.Expectations;
import org.jmock.api.Invocation;
import org.jmock.lib.action.CustomAction;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.db.DeletedContentPurger.MIN_INTERVAL_MS;
import static org.zerionproject.core.db.DeletedContentPurger.PURGE_DELAY_MS;
import static org.zerionproject.core.db.DeletedContentPurger.RETRY_DELAY_MS;

public class DeletedContentPurgerTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final Executor dbExecutor = Runnable::run;
	private final RecordingScheduler scheduler = new RecordingScheduler();
	private final java.util.concurrent.atomic.AtomicLong now =
			new java.util.concurrent.atomic.AtomicLong(1_800_000_000_000L);
	private final Clock clock = new Clock() {
		@Override
		public long currentTimeMillis() {
			return now.get();
		}

		@Override
		public void sleep(long milliseconds) {
			now.addAndGet(milliseconds);
		}
	};
	private final DeletedContentPurger purger =
			new DeletedContentPurger(db, dbExecutor, scheduler, clock);

	@Test
	public void aBurstOfDeletionsRunsOnePurge() throws Exception {
		context.checking(new Expectations() {{
			oneOf(db).purgeDeletedContent();
			will(returnValue(true));
		}});

		for (int i = 0; i < 50; i++) {
			purger.eventOccurred(new PurgeRequestedEvent());
		}
		assertEquals(1, scheduler.tasks.size());
		assertEquals(PURGE_DELAY_MS, scheduler.delays.get(0).longValue());
		scheduler.runNext();
		assertEquals(0, scheduler.tasks.size());
	}

	@Test
	public void aDeletionDuringAPurgeIsPurgedAgain() throws Exception {
		context.checking(new Expectations() {{
			oneOf(db).purgeDeletedContent();
			will(new CustomAction("delete while purging") {
				@Override
				public Object invoke(Invocation invocation) {
					purger.eventOccurred(new PurgeRequestedEvent());
					return true;
				}
			});
			oneOf(db).purgeDeletedContent();
			will(returnValue(true));
		}});

		purger.eventOccurred(new PurgeRequestedEvent());
		purger.eventOccurred(new PurgeRequestedEvent());
		assertEquals(1, scheduler.tasks.size());
		scheduler.runNext();
		assertEquals(1, scheduler.tasks.size());
		assertEquals(MIN_INTERVAL_MS, scheduler.delays.get(0).longValue());
		scheduler.runNext();
		assertEquals(0, scheduler.tasks.size());
	}

	@Test
	public void purgesAreAtLeastTheMinimumIntervalApart() throws Exception {
		context.checking(new Expectations() {{
			exactly(3).of(db).purgeDeletedContent();
			will(returnValue(true));
		}});

		purger.eventOccurred(new PurgeRequestedEvent());
		assertEquals(PURGE_DELAY_MS, scheduler.delays.get(0).longValue());
		scheduler.runNext();
		now.addAndGet(3_000);
		purger.eventOccurred(new PurgeRequestedEvent());
		assertEquals(MIN_INTERVAL_MS - 3_000,
				scheduler.delays.get(0).longValue());
		scheduler.runNext();
		now.addAndGet(MIN_INTERVAL_MS * 3);
		purger.eventOccurred(new PurgeRequestedEvent());
		assertEquals(PURGE_DELAY_MS, scheduler.delays.get(0).longValue());
		scheduler.runNext();
		assertEquals(0, scheduler.tasks.size());
	}

	@Test
	public void aClockSetBackDoesNotDelayTheNextPurge() throws Exception {
		context.checking(new Expectations() {{
			exactly(3).of(db).purgeDeletedContent();
			will(returnValue(true));
		}});

		purger.eventOccurred(new PurgeRequestedEvent());
		scheduler.runNext();
		now.addAndGet(-60L * 60 * 1000);
		purger.eventOccurred(new PurgeRequestedEvent());
		assertEquals(MIN_INTERVAL_MS, scheduler.delays.get(0).longValue());
		scheduler.runNext();
		now.addAndGet(2_000);
		purger.eventOccurred(new PurgeRequestedEvent());
		assertEquals(MIN_INTERVAL_MS - 2_000,
				scheduler.delays.get(0).longValue());
		scheduler.runNext();
		assertEquals(0, scheduler.tasks.size());
	}

	@Test
	public void aPurgeThatCannotCompleteIsRetried() throws Exception {
		context.checking(new Expectations() {{
			oneOf(db).purgeDeletedContent();
			will(returnValue(false));
			oneOf(db).purgeDeletedContent();
			will(throwException(new DbException()));
			oneOf(db).purgeDeletedContent();
			will(returnValue(true));
		}});

		purger.eventOccurred(new PurgeRequestedEvent());
		scheduler.runNext();
		assertEquals(1, scheduler.tasks.size());
		assertEquals(RETRY_DELAY_MS, scheduler.delays.get(0).longValue());
		scheduler.runNext();
		assertEquals(1, scheduler.tasks.size());
		assertEquals(RETRY_DELAY_MS, scheduler.delays.get(0).longValue());
		scheduler.runNext();
		assertEquals(0, scheduler.tasks.size());
	}

	@Test
	public void otherEventsAreIgnored() {
		purger.eventOccurred(new Event() {
		});
		assertEquals(0, scheduler.tasks.size());
	}

	@Test
	public void aRejectedScheduleIsRequestedAgainByTheNextDeletion()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(db).purgeDeletedContent();
			will(returnValue(true));
		}});

		scheduler.reject = true;
		purger.eventOccurred(new PurgeRequestedEvent());
		assertEquals(0, scheduler.tasks.size());
		scheduler.reject = false;
		purger.eventOccurred(new PurgeRequestedEvent());
		assertEquals(1, scheduler.tasks.size());
		scheduler.runNext();
	}

	@NotNullByDefault
	private static class RecordingScheduler implements TaskScheduler {

		private final List<Runnable> tasks = new ArrayList<>();
		private final List<Long> delays = new ArrayList<>();
		private boolean reject = false;

		@Override
		public Cancellable schedule(Runnable task, Executor executor,
				long delay, TimeUnit unit) {
			if (reject) throw new RejectedExecutionException();
			tasks.add(() -> executor.execute(task));
			delays.add(unit.toMillis(delay));
			return () -> {
			};
		}

		@Override
		public Cancellable scheduleWithFixedDelay(Runnable task,
				Executor executor, long delay, long interval, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		private Runnable take() {
			delays.remove(0);
			return tasks.remove(0);
		}

		private void runNext() {
			take().run();
		}
	}
}
