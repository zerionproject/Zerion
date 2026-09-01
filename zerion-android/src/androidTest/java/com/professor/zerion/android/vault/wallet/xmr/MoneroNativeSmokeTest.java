package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * XMR-P0 on-device proof that libzmonero.so (built reproducibly from official
 * Monero wallet2_api) loads and performs the local wallet lifecycle without a
 * daemon: create, derive primary address and a subaddress, read the seed, and
 * validate addresses. No network is used. Runs on a real arm device where the
 * native library is present.
 */
@RunWith(AndroidJUnit4.class)
public class MoneroNativeSmokeTest {

	@Test
	public void nativeLibraryLoadsOnDevice() {
		assertTrue("libzmonero.so must load on a shipped ABI",
				NativeMonero.isAvailable());
	}

	@Test
	public void backgroundRefreshControlsAreBound() {
		Context ctx = ApplicationProvider.getApplicationContext();
		File dir = new File(ctx.getCacheDir(), "xmr-bg-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		MoneroEngine engine = new NativeMoneroEngine();
		MoneroEngine.Session s = null;
		try {
			s = engine.create(new File(dir, "w").getAbsolutePath(),
					"pw".toCharArray(), "English");
			assertNotEquals("wallet session must be non-null", null, s);

			s.setAutoRefreshInterval(10000);
			s.startRefresh();
			s.pauseRefresh();
			s.stopRefresh();
		} finally {
			if (s != null) s.close();
			File[] files = dir.listFiles();
			if (files != null) {
				for (File f : files) {
					f.delete();
				}
			}
			dir.delete();
		}
	}

	@Test
	public void createDeriveSeedValidateClose() {
		Context ctx = ApplicationProvider.getApplicationContext();
		File dir = new File(ctx.getCacheDir(), "xmr-smoke-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		MoneroEngine engine = new NativeMoneroEngine();
		MoneroEngine.Session s = null;
		try {
			s = engine.create(new File(dir, "w").getAbsolutePath(),
					"pw".toCharArray(), "English");
			assertNotEquals("wallet session must be non-null", null, s);
			assertEquals("wallet status must be OK", 0, s.status());

			String primary = s.address(0, 0);
			assertTrue("primary address is mainnet (starts with 4)",
					primary.startsWith("4"));

			s.addSubaddress(0, "smoke");
			String sub = s.address(0, 1);
			assertTrue("subaddress starts with 8", sub.startsWith("8"));
			assertNotEquals("subaddress differs from primary", primary, sub);

			char[] seed = s.seed(new char[0]);
			assertEquals("Monero seed is 25 words", 25,
					new String(seed).trim().split("\\s+").length);
			java.util.Arrays.fill(seed, '\0');

			assertTrue("primary validates", engine.validateAddress(primary));
			assertTrue("subaddress validates", engine.validateAddress(sub));
			assertFalse("garbage does not validate",
					engine.validateAddress("not-an-address"));
		} finally {
			if (s != null) s.close();
			File[] files = dir.listFiles();
			if (files != null) {
				for (File f : files) {
					f.delete();
				}
			}
			dir.delete();
		}
	}
}
