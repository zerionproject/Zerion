package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * XMR-P0 hardening pass for the Zerion JNI boundary (non-funded, on-device).
 * Verifies the native library survives repeated lifecycle, guards double-close,
 * rejects invalid handles and malformed input, and never lets a native error
 * escape as an uncontrolled crash. Uses only throwaway wallets; no funds.
 */
@RunWith(AndroidJUnit4.class)
public class MoneroNativeHardeningTest {

	private final MoneroEngine engine = new NativeMoneroEngine();

	private File tempDir() {
		Context ctx = ApplicationProvider.getApplicationContext();
		File d = new File(ctx.getCacheDir(), "xmr-hard-" + System.nanoTime());
		assertTrue(d.mkdirs());
		return d;
	}

	private void wipe(File dir) {
		File[] files = dir.listFiles();
		if (files != null) {
			for (File f : files) {
				f.delete();
			}
		}
		dir.delete();
	}

	@Test
	public void repeatedCreateDeriveCloseDoesNotLeakOrCrash() {
		for (int i = 0; i < 6; i++) {
			File dir = tempDir();
			try {
				MoneroEngine.Session s = engine.create(
						new File(dir, "w").getAbsolutePath(), "pw".toCharArray(), "English");
				assertNotNull(s);
				assertEquals(0, s.status());
				String primary = s.address(0, 0);
				assertTrue(primary.startsWith("4"));
				s.close();
			} finally {
				wipe(dir);
			}
		}
	}

	@Test
	public void doubleCloseIsIdempotentAndSafe() {
		File dir = tempDir();
		try {
			MoneroEngine.Session s = engine.create(
					new File(dir, "w").getAbsolutePath(), "pw".toCharArray(), "English");
			assertNotNull(s);
			s.close();
			s.close();
			s.close();
		} finally {
			wipe(dir);
		}
	}

	@Test
	public void nullHandleFailsSafely() {
		assertEquals(-1, NativeMonero.nStatus(0));
		assertEquals("", NativeMonero.nAddress(0, 0, 0));
		assertEquals(Long.MIN_VALUE, NativeMonero.nBalance(0, 0));
		assertEquals(Long.MIN_VALUE, NativeMonero.nUnlockedBalance(0, 0));
		assertEquals(Long.MIN_VALUE, NativeMonero.nNumSubaddresses(0, 0));
		assertFalse(NativeMonero.nClose(0, false));
		assertFalse(NativeMonero.nRefresh(0));
		assertEquals(-1, NativeMonero.nTxStatus(0));
		assertEquals(Long.MIN_VALUE, NativeMonero.nTxFee(0));
		assertEquals(Long.MIN_VALUE, NativeMonero.nTxChange(0));
		assertEquals(Long.MIN_VALUE, NativeMonero.nTxCount(0));
		assertEquals(Long.MIN_VALUE, NativeMonero.nTxDust(0));

		assertNull(NativeMonero.nHistory(0));
	}

	/**
	 * F-5 handle registry: forged, stale, wrong-kind and double-disposed handles
	 * must resolve to the typed error sentinel, never dereference memory. Real
	 * handles are small opaque ids, not raw pointers.
	 */
	@Test
	public void forgedStaleAndWrongTypeHandlesFailSafely() {
		long[] forged = {1L, -1L, 7L, 0xDEADBEEFL, 0x7FFFFFFFFFFFFFFFL,
				Long.MIN_VALUE, 0x1000L};
		for (long h : forged) {
			assertEquals(Long.MIN_VALUE, NativeMonero.nBalance(h, 0));
			assertEquals(Long.MIN_VALUE, NativeMonero.nUnlockedBalance(h, 0));
			assertEquals(-1, NativeMonero.nStatus(h));
			assertNull(NativeMonero.nHistory(h));
			assertFalse(NativeMonero.nClose(h, false));
			assertEquals(-1, NativeMonero.nTxStatus(h));
			assertEquals(Long.MIN_VALUE, NativeMonero.nTxFee(h));
			assertEquals(Long.MIN_VALUE, NativeMonero.nTxChange(h));
			assertEquals(Long.MIN_VALUE, NativeMonero.nTxCount(h));
			assertEquals(Long.MIN_VALUE, NativeMonero.nTxDust(h));
			NativeMonero.nDisposeTx(h, h);
		}
		File dir = tempDir();
		try {
			long w = NativeMonero.nCreate(new File(dir, "w").getAbsolutePath(),
					"pw".getBytes(StandardCharsets.UTF_8), "English");
			assertTrue("real handle is a positive opaque id, not a pointer",
					w > 0 && w < 0x1000000L);
			assertEquals(0, NativeMonero.nStatus(w));

			assertEquals(-1, NativeMonero.nTxStatus(w));
			assertEquals(Long.MIN_VALUE, NativeMonero.nTxFee(w));
			assertEquals(Long.MIN_VALUE, NativeMonero.nTxChange(w));
			assertEquals(Long.MIN_VALUE, NativeMonero.nTxCount(w));
			assertEquals(Long.MIN_VALUE, NativeMonero.nTxDust(w));
			assertTrue(NativeMonero.nClose(w, false));

			assertEquals(-1, NativeMonero.nStatus(w));
			assertEquals(Long.MIN_VALUE, NativeMonero.nBalance(w, 0));
			assertNull(NativeMonero.nHistory(w));
			assertFalse(NativeMonero.nClose(w, false));
		} finally {
			wipe(dir);
		}
	}

	@Test
	public void malformedSeedIsRejected() {
		File dir = tempDir();
		try {
			MoneroEngine.Session s = engine.restore(
					new File(dir, "w").getAbsolutePath(), "pw".toCharArray(),
					"these are not valid monero mnemonic words at all".toCharArray(),
					0, new char[0]);
			assertTrue("bad seed must not yield a usable wallet",
					s == null || s.status() != 0);
			if (s != null) {
				s.close();
			}
		} finally {
			wipe(dir);
		}
	}

	@Test
	public void openNonexistentWalletFailsSafely() {
		MoneroEngine.Session s = engine.open("/does/not/exist/w", "pw".toCharArray());
		assertTrue(s == null || s.status() != 0);
		if (s != null) {
			s.close();
		}
	}

	@Test
	public void invalidAddressesRejected() {
		assertFalse(engine.validateAddress(""));
		assertFalse(engine.validateAddress("not-an-address"));
		assertFalse(engine.validateAddress(
				"4thisisnotarealmoneroaddressjustgarbagexxxxxxxxxxxxxxxxxxxx"));
	}

	@Test
	public void concurrentReadOnlyCallsDoNotCrash() throws Exception {
		File dir = tempDir();
		try {
			MoneroEngine.Session s = engine.create(
					new File(dir, "w").getAbsolutePath(), "pw".toCharArray(), "English");
			assertNotNull(s);
			final String primary = s.address(0, 0);
			final AtomicBoolean failed = new AtomicBoolean(false);
			Thread[] threads = new Thread[8];
			for (int t = 0; t < threads.length; t++) {
				threads[t] = new Thread(() -> {
					try {
						for (int i = 0; i < 50; i++) {
							if (!engine.validateAddress(primary)) {
								failed.set(true);
							}
							s.address(0, 0);
							s.status();
						}
					} catch (Throwable e) {
						failed.set(true);
					}
				});
			}
			for (Thread th : threads) th.start();
			for (Thread th : threads) th.join();
			assertFalse("concurrent read-only calls must not fail",
					failed.get());
			s.close();
		} finally {
			wipe(dir);
		}
	}
}
