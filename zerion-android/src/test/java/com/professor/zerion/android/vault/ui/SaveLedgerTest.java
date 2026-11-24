package com.professor.zerion.android.vault.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SaveLedgerTest {

	private final SaveLedger<String> ledger = new SaveLedger<>();

	@Test
	public void requestNumbersStartAboveZeroAndNeverRepeat() {
		long first = ledger.newRequest();
		long second = ledger.newRequest();

		assertTrue(first > 0);
		assertNotEquals(first, second);
		assertFalse(ledger.isPending(first));
		assertFalse(ledger.isRunning(first));
	}

	@Test
	public void aSaveIsPendingFromItsStartUntilItsResultIsTaken() {
		long request = ledger.newRequest();

		ledger.started(request);
		assertTrue(ledger.isRunning(request));
		assertTrue(ledger.isAnyRunning());
		assertTrue(ledger.isPending(request));
		assertNull(ledger.take(request));

		ledger.finished(request, "stored");
		assertFalse(ledger.isRunning(request));
		assertFalse(ledger.isAnyRunning());
		assertTrue("the result is gone before anyone took it",
				ledger.isPending(request));

		assertEquals("stored", ledger.take(request));
		assertFalse(ledger.isPending(request));
	}

	@Test
	public void aResultIsHandedOutOnceAndOnlyForItsOwnRequest() {
		long one = ledger.newRequest();
		long other = ledger.newRequest();
		ledger.started(one);
		ledger.started(other);

		ledger.finished(other, "other");
		assertNull(ledger.take(one));
		assertTrue(ledger.isRunning(one));

		ledger.finished(one, "one");
		assertEquals("one", ledger.take(one));
		assertNull(ledger.take(one));
		assertEquals("other", ledger.take(other));
	}

	@Test
	public void onlyTheLatestResultsAreKept() {
		long first = ledger.newRequest();
		ledger.started(first);
		ledger.finished(first, "first");
		long last = first;
		for (int later = 0; later < SaveLedger.KEPT_RESULTS; later++) {
			last = ledger.newRequest();
			ledger.started(last);
			ledger.finished(last, "later");
		}

		assertFalse(ledger.isPending(first));
		assertNull(ledger.take(first));
		assertEquals("later", ledger.take(last));
	}

	@Test
	public void aRunningSaveIsNotForgottenWhenOthersFinish() {
		long slow = ledger.newRequest();
		ledger.started(slow);
		for (int other = 0; other <= SaveLedger.KEPT_RESULTS; other++) {
			long request = ledger.newRequest();
			ledger.started(request);
			ledger.finished(request, "other");
		}

		assertTrue(ledger.isRunning(slow));
		assertTrue(ledger.isPending(slow));
		ledger.finished(slow, "slow");
		assertEquals("slow", ledger.take(slow));
	}
}
