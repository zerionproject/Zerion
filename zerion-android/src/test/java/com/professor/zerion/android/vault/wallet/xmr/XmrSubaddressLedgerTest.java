package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.Nullable;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class XmrSubaddressLedgerTest {

	private static final class MemStore implements XmrSubaddressLedger.Store {
		int issued = 0;
		boolean failSetIssued = false;
		final Map<Integer, String> addrs = new HashMap<>();
		final Map<Integer, String> labels = new HashMap<>();
		final Map<Integer, Long> dates = new HashMap<>();

		@Override
		public int getIssued() {
			return issued;
		}

		@Override
		public void setIssued(int index) throws Exception {
			if (failSetIssued) throw new Exception("persist failed");
			issued = index;
		}

		@Nullable
		@Override
		public String getAddress(int index) {
			return addrs.get(index);
		}

		@Override
		public void putAddress(int index, String address) {
			addrs.put(index, address);
		}

		@Override
		public void putAddresses(Map<Integer, String> addresses) {
			addrs.putAll(addresses);
		}

		@Nullable
		@Override
		public String getLabel(int index) {
			return labels.get(index);
		}

		@Override
		public void putLabel(int index, @Nullable String label) {
			if (label == null) labels.remove(index);
			else labels.put(index, label);
		}

		@Override
		public long getDate(int index) {
			Long d = dates.get(index);
			return d == null ? 0 : d;
		}

		@Override
		public void putDate(int index, long millis) {
			dates.put(index, millis);
		}
	}

	@Test
	public void firstReserveIsIndexOneNotPrimary() throws Exception {
		MemStore s = new MemStore();
		XmrSubaddressLedger l = new XmrSubaddressLedger(s);
		assertEquals(0, l.issuedCount());
		int first = l.reserveNext(1000L);
		assertEquals("first fresh index is 1, never the primary 0", 1, first);
	}

	@Test
	public void reserveIncrementsMonotonically() throws Exception {
		MemStore s = new MemStore();
		XmrSubaddressLedger l = new XmrSubaddressLedger(s);
		assertEquals(1, l.reserveNext(1L));
		assertEquals(2, l.reserveNext(2L));
		assertEquals(3, l.reserveNext(3L));
		assertEquals(3, l.issuedCount());
	}

	@Test
	public void issuedIsPersistedBeforeReturnSoRestartPreservesIt() throws Exception {
		MemStore s = new MemStore();
		int idx = new XmrSubaddressLedger(s).reserveNext(1L);
		XmrSubaddressLedger afterRestart = new XmrSubaddressLedger(s);
		assertTrue(afterRestart.isIssued(idx));
		assertEquals(idx, afterRestart.issuedCount());
		assertEquals("next reserve does not collide with the issued index",
				idx + 1, afterRestart.reserveNext(2L));
	}

	@Test
	public void persistenceFailureThrowsAndNeverSilentlyReuses() throws Exception {
		MemStore s = new MemStore();
		XmrSubaddressLedger l = new XmrSubaddressLedger(s);
		assertEquals(1, l.reserveNext(1L));
		s.failSetIssued = true;
		try {
			l.reserveNext(2L);
			fail("reserve must throw on persistence failure");
		} catch (Exception expected) {
		}
		assertEquals(1, l.issuedCount());
		s.failSetIssued = false;
		assertEquals("recovery issues a NEW index, never silently reuses 1",
				2, l.reserveNext(3L));
	}

	@Test
	public void issuedIndicesExcludePrimary() throws Exception {
		MemStore s = new MemStore();
		XmrSubaddressLedger l = new XmrSubaddressLedger(s);
		l.reserveNext(1L);
		l.reserveNext(2L);
		assertEquals("[1, 2]", l.issuedIndices().toString());
		assertFalse(l.issuedIndices().contains(0));
	}

	@Test
	public void deliberateReuseReturnsSelectedCachedAddress() throws Exception {
		MemStore s = new MemStore();
		XmrSubaddressLedger l = new XmrSubaddressLedger(s);
		int i1 = l.reserveNext(1L);
		l.cacheAddress(i1, "8AAA");
		int i2 = l.reserveNext(2L);
		l.cacheAddress(i2, "8BBB");
		assertEquals("8AAA", l.cachedAddress(1));
		assertEquals("8BBB", l.cachedAddress(2));
		assertNull(l.cachedAddress(99));
	}

	@Test
	public void processDeathAfterExposureNeverReusesTheIndex() throws Exception {
		MemStore s = new MemStore();
		int first = new XmrSubaddressLedger(s).reserveNext(1L);
		s.putAddress(first, "8FIRST");
		int second = new XmrSubaddressLedger(s).reserveNext(2L);
		assertTrue("the second index must differ from the exposed one",
				second != first);
		assertEquals(first + 1, second);
	}

	@Test
	public void deathAtThePersistExposeBoundaryDoesNotExposeThenReuse()
			throws Exception {
		MemStore s = new MemStore();
		XmrSubaddressLedger l = new XmrSubaddressLedger(s);
		s.failSetIssued = true;
		try {
			l.reserveNext(1L);
			fail("must fail closed at the persistence boundary");
		} catch (Exception expected) {
		}
		assertEquals("nothing was issued or exposed", 0, l.issuedCount());
		s.failSetIssued = false;
		assertEquals("the first successfully issued index is 1", 1,
				l.reserveNext(2L));
	}

	@Test
	public void separateWalletsHaveIsolatedIssuance() throws Exception {
		MemStore a = new MemStore();
		MemStore b = new MemStore();
		XmrSubaddressLedger la = new XmrSubaddressLedger(a);
		XmrSubaddressLedger lb = new XmrSubaddressLedger(b);
		la.reserveNext(1L);
		la.reserveNext(2L);
		assertEquals("wallet A advanced", 2, la.issuedCount());
		assertEquals("wallet B is untouched by A", 0, lb.issuedCount());
		assertEquals("wallet B issues its own index 1", 1, lb.reserveNext(3L));
		assertEquals(2, la.issuedCount());
	}

	@Test
	public void primaryIndexZeroIsNeverAFreshIssue() throws Exception {
		MemStore s = new MemStore();
		XmrSubaddressLedger l = new XmrSubaddressLedger(s);
		for (int i = 0; i < 5; i++) {
			assertTrue("fresh index is always >= 1, never the primary 0",
					l.reserveNext(i) >= 1);
		}
		assertFalse(l.issuedIndices().contains(0));
	}

	@Test
	public void labelsAndDatesRoundTrip() throws Exception {
		MemStore s = new MemStore();
		XmrSubaddressLedger l = new XmrSubaddressLedger(s);
		int i = l.reserveNext(1735689600000L);
		l.setLabel(i, "rent");
		assertEquals("rent", l.label(i));
		assertEquals(1735689600000L, l.issuedDate(i));
		l.setLabel(i, null);
		assertNull(l.label(i));
	}
}
