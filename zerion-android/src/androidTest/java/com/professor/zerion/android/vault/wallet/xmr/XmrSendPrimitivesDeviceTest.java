package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;

/**
 * On-device contract tests for the send inspection and reconciliation
 * primitives against the real native library, with no vault, no app data, no
 * relay and no transaction construction. These cover the offline-verifiable
 * guarantees: classification comes from Monero's parser; invalid handles fail
 * closed; the refresh-idle probe is honest when the wallet is genuinely idle;
 * and a lookup that cannot reach a daemon, or is given a malformed txid, is a
 * LOOKUP_ERROR and never negative evidence. Positive lookup results
 * (IN_POOL / MINED / MISSED from a real daemon) are covered by the decode unit
 * test {@link XmrTxLookup} and by the P4-C Pixel acceptance against a live
 * node; a loopback socket cannot faithfully emulate the daemon's HTTP server.
 */
@RunWith(AndroidJUnit4.class)
public class XmrSendPrimitivesDeviceTest {

	private static final String A =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String B =
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	/**
	 * Canonical mainnet address vectors from Monero's own upstream functional
	 * test suite (tests/functional_tests/validate_address.py). The classifier
	 * under test is exactly Monero's get_account_address_from_str, so these are
	 * authoritative and independently verifiable.
	 */
	private static final String MAINNET_STANDARD =
			"42ey1afDFnn4886T7196doS9GPMzexD9gXpsZJDwVjeRVdFCSoHnv7KPbBeGpzJBzHRCAs9UxqeoyFQMYbqSWYTfJJQAWDm";
	private static final String MAINNET_SUBADDRESS =
			"8AsN91rznfkBGTY8psSNkJBg9SZgxxGGRUhGwRptBhgr5XSQ1XzmA9m8QAnoxydecSh5aLJXdrgXwTDMMZ1AuXsN1EX5Mtm";
	private static final String MAINNET_INTEGRATED =
			"4BxSHvcgTwu25WooY4BVmgdcKwZu5EksVZSZkDd6ooxSVVqQ4ubxXkhLF6hEqtw96i9cf3cVfLw8UWe95bdDKfRQeYtPwLm1Jiw7AKt2LY";

	private File dir;
	private MoneroEngine engine;
	private MoneroEngine.Session session;

	@Before
	public void setUp() {
		Context ctx = ApplicationProvider.getApplicationContext();
		assertTrue(NativeMonero.isAvailable());
		dir = new File(ctx.getCacheDir(), "xmr-send-prim-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		engine = new NativeMoneroEngine();
		session = engine.create(new File(dir, "w").getAbsolutePath(),
				"pw".toCharArray(), "English");
		assertNotNull(session);
		assertEquals(0, session.status());
	}

	@After
	public void tearDown() {
		if (session != null) session.close();
		File[] files = dir.listFiles();
		if (files != null) for (File f : files) f.delete();
		dir.delete();
	}

	@Test
	public void addressKindComesFromTheMoneroParser() {
		String standard = session.address(0, 0);
		session.addSubaddress(0, "");
		String sub = session.address(0, 1);
		assertTrue(standard.startsWith("4"));
		assertTrue(sub.startsWith("8"));
		assertEquals("a real wallet's own primary address is standard",
				MoneroEngine.AddressKind.STANDARD, engine.addressKind(standard));
		assertEquals("a real wallet's own subaddress classifies as subaddress",
				MoneroEngine.AddressKind.SUBADDRESS, engine.addressKind(sub));
		assertEquals(MoneroEngine.AddressKind.STANDARD,
				engine.addressKind(MAINNET_STANDARD));
		assertEquals(MoneroEngine.AddressKind.SUBADDRESS,
				engine.addressKind(MAINNET_SUBADDRESS));
		assertEquals("integrated is classified from its payment id, not generated",
				MoneroEngine.AddressKind.INTEGRATED,
				engine.addressKind(MAINNET_INTEGRATED));
		assertEquals(MoneroEngine.AddressKind.INVALID, engine.addressKind(""));
		assertEquals(MoneroEngine.AddressKind.INVALID,
				engine.addressKind("not-an-address"));
		assertEquals("a corrupted address is invalid, not reclassified",
				MoneroEngine.AddressKind.INVALID,
				engine.addressKind(standard.substring(0, 94) + "1"));
		assertEquals("a mainnet address with a mangled tail is invalid",
				MoneroEngine.AddressKind.INVALID, engine.addressKind(
						MAINNET_INTEGRATED.substring(0, 104) + "11"));
	}

	@Test
	public void inspectionOnInvalidHandlesFailsClosed() {
		assertNull(NativeMonero.nTxIds(0));
		assertEquals(Long.MIN_VALUE, NativeMonero.nTxCount(0));
		assertEquals(Long.MIN_VALUE, NativeMonero.nTxDust(0));
		assertFalse(NativeMonero.nWaitRefreshIdle(0, 100));
		assertNull(NativeMonero.nLookupTxs(0, new String[]{A}, 1000));
	}

	@Test
	public void refreshIdleIsTrueWhenTheWalletIsIdle() {
		assertTrue("a fresh wallet with no refresh running is idle",
				session.waitRefreshIdle(1000));
		session.pauseRefresh();
		assertTrue("still idle after pausing", session.waitRefreshIdle(1000));
	}

	@Test
	public void lookupTransportFailureIsErrorNeverMissed() throws Exception {
		int closedPort;
		try (ServerSocket s = new ServerSocket(0, 1,
				java.net.InetAddress.getLoopbackAddress())) {
			closedPort = s.getLocalPort();
		}
		assertTrue(session.init("127.0.0.1:" + closedPort, "", false));
		List<XmrTxLookup> r = session.lookupTxs(Arrays.asList(A, B), 3000);
		assertEquals(2, r.size());
		for (XmrTxLookup x : r) {
			assertEquals("no daemon answer is an error, never negative evidence",
					XmrTxLookup.Result.LOOKUP_ERROR, x.result);
		}
	}

	@Test
	public void lookupRejectsMalformedTxidWithoutQuerying() {
		List<XmrTxLookup> r = session.lookupTxs(
				Arrays.asList("NOT-HEX", A.toUpperCase(), A.substring(1)), 2000);
		for (XmrTxLookup x : r) {
			assertEquals(XmrTxLookup.Result.LOOKUP_ERROR, x.result);
		}
	}

	@Test
	public void disposedWalletFailsClosed() {
		session.close();
		assertFalse("closed session is never idle", session.waitRefreshIdle(200));
		for (XmrTxLookup x : session.lookupTxs(Arrays.asList(A), 1000)) {
			assertEquals(XmrTxLookup.Result.LOOKUP_ERROR, x.result);
		}
		session = null;
	}
}
