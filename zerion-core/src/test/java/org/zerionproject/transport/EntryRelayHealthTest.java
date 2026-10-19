package org.zerionproject.transport;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.transport.EntryRelayHealth.UNANSWERED_MS;

public class EntryRelayHealthTest {

	private final List<Boolean> reported = new ArrayList<>();
	private final List<Boolean> underLock = new ArrayList<>();
	private EntryRelayHealth health;

	public EntryRelayHealthTest() {
		health = new EntryRelayHealth(failing -> {
			reported.add(failing);
			underLock.add(Thread.holdsLock(health));
		});
	}

	@Test
	public void anAnswerRestartsTheWait() {
		health.onWarning(0);
		assertTrue(health.onRequest(0));
		health.onAnswer(60_000);
		assertTrue("a request after an answer starts a new wait",
				health.onRequest(90_000));
		assertFalse("a request during a wait joins it",
				health.onRequest(90_000 + UNANSWERED_MS - 1));
		assertFalse(health.isFailing());
		health.onRecheck(90_000 + UNANSWERED_MS);
		assertTrue(health.isFailing());
		assertEquals(singletonList(true), reported);
	}

	@Test
	public void aRecheckBeforeTheWaitIsOverChangesNothing() {
		health.onWarning(0);
		health.onRequest(0);
		health.onRecheck(UNANSWERED_MS - 1);
		assertFalse(health.isFailing());
		health.onAnswer(UNANSWERED_MS - 1);
		health.onRecheck(UNANSWERED_MS);
		assertFalse("an answered wait is not failing", health.isFailing());
		assertEquals(emptyList(), reported);
	}

	@Test
	public void onlyABootstrapThatStartsOverClearsTheWarning() {
		health.onBootstrap(95);
		health.onWarning(0);
		health.onBootstrap(98);
		health.onBootstrap(100);
		health.onRequest(0);
		health.onRecheck(UNANSWERED_MS);
		assertTrue("later progress in the same run keeps the warning",
				health.isFailing());
		health.onBootstrap(100);
		assertTrue(health.isFailing());
		health.onBootstrap(5);
		assertFalse("a bootstrap that starts over is a new run",
				health.isFailing());
		health.onBootstrap(100);
		health.onRequest(2 * UNANSWERED_MS);
		health.onRecheck(3 * UNANSWERED_MS);
		assertFalse("the new run has not warned", health.isFailing());
		assertEquals(asList(true, false), reported);
	}

	@Test
	public void clearingForgetsTheWarningAndTheWait() {
		health.onWarning(0);
		health.onRequest(0);
		health.onRecheck(UNANSWERED_MS);
		health.clear();
		health.clear();
		assertEquals(asList(true, false), reported);
		health.onRequest(2 * UNANSWERED_MS);
		health.onWarning(2 * UNANSWERED_MS);
		health.onRecheck(2 * UNANSWERED_MS);
		assertFalse("the wait starts again after a clear",
				health.isFailing());
	}

	@Test
	public void changesAreReportedUnderTheLockSoTheyStayInOrder() {
		health.onWarning(0);
		health.onRequest(0);
		health.onRecheck(UNANSWERED_MS);
		health.clear();
		assertEquals(asList(true, false), reported);
		assertEquals(asList(true, true), underLock);
	}
}
