package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class XmrRestoreHeightDeviceTest {

	private static final char[] MAIN_PW = "restore-main-pass".toCharArray();
	private static final char[] BG_PW = "restore-bg-pass".toCharArray();
	private static final long HIST_HEIGHT = 3_700_000L;
	private static final String NODE = "xmr-node.cakewallet.com:18081";

	private static byte[] b(char[] c) {
		return new String(c).getBytes(StandardCharsets.UTF_8);
	}

	private File freshDir(String tag) {
		Context ctx = ApplicationProvider.getApplicationContext();
		File dir = new File(ctx.getCacheDir(), "xmr-rh-" + tag + "-"
				+ System.nanoTime());
		assertTrue(dir.mkdirs());
		return dir;
	}

	private String throwawaySeed(File dir) {
		long w = NativeMonero.nCreate(new File(dir, "seedgen").getAbsolutePath(),
				b(MAIN_PW), "English");
		assertTrue(w > 0);
		try {
			byte[] seed = NativeMonero.nSeed(w, new byte[0]);
			String s = new String(seed, StandardCharsets.UTF_8).trim();
			assertEquals(25, s.split("\\s+").length);
			return s;
		} finally {
			NativeMonero.nClose(w, false);
		}
	}

	private void buildAtHistoricalHeight(File dir) {
		String seed = throwawaySeed(dir);
		String base = new File(dir, "w").getAbsolutePath();
		long spend = NativeMonero.nRestore(base, b(MAIN_PW),
				seed.getBytes(StandardCharsets.UTF_8), HIST_HEIGHT, new byte[0]);
		assertTrue(spend > 0);
		assertEquals(0, NativeMonero.nStatus(spend));
		assertEquals("restore sets the requested refresh height", HIST_HEIGHT,
				NativeMonero.nGetRefreshFromHeight(spend));
		assertTrue(NativeMonero.nSetupBackgroundSync(spend, b(MAIN_PW), b(BG_PW)));
		assertTrue(NativeMonero.nStore(spend, base));
		NativeMonero.nClose(spend, false);
	}

	private long openBackground(File dir) {
		long bg = NativeMonero.nOpen(new File(dir, "w.background").getAbsolutePath(),
				b(BG_PW));
		assertTrue("background wallet opens", bg > 0);
		assertEquals(0, NativeMonero.nStatus(bg));
		assertTrue("opened file is a background (view-only) wallet",
				NativeMonero.nIsBackgroundWallet(bg));
		return bg;
	}

	@Test
	public void restoreHeightPersistsThroughBackgroundCache() {
		File dir = freshDir("persist");
		buildAtHistoricalHeight(dir);
		long bg = openBackground(dir);
		try {
			assertEquals("the background cache carries the same refresh height",
					HIST_HEIGHT, NativeMonero.nGetRefreshFromHeight(bg));
		} finally {
			NativeMonero.nClose(bg, false);
		}
	}

	@Test
	public void publicSetRefreshHeightIsNoOpOnBackgroundWallet() {
		File dir = freshDir("noop");
		buildAtHistoricalHeight(dir);
		long bg = openBackground(dir);
		try {
			NativeMonero.nSetRefreshFromHeight(bg, HIST_HEIGHT + 40_000L);
			assertEquals("setRefreshFromBlockHeight does not move a background "
					+ "wallet's height", HIST_HEIGHT,
					NativeMonero.nGetRefreshFromHeight(bg));
		} finally {
			NativeMonero.nClose(bg, false);
		}
	}

	@Test
	public void connectWithoutRecoveringMarkerFastForwardsToTip() {
		File dir = freshDir("bug");
		buildAtHistoricalHeight(dir);
		long bg = openBackground(dir);
		try {
			boolean connected;
			try {
				connected = NativeMonero.nInit(bg, NODE, "", false);
			} catch (Throwable t) {
				connected = false;
			}
			long tip = NativeMonero.nDaemonHeight(bg);
			assumeTrue("requires a reachable daemon", connected && tip > HIST_HEIGHT);
			assertTrue("without the marker, connect fast-forwards the refresh "
					+ "height toward the tip (history before now is skipped)",
					NativeMonero.nGetRefreshFromHeight(bg) > HIST_HEIGHT);
		} finally {
			NativeMonero.nClose(bg, false);
		}
	}

	@Test
	public void recoveringMarkerPreservesEarlyHeightThroughConnect() {
		File dir = freshDir("fix");
		buildAtHistoricalHeight(dir);
		long bg = openBackground(dir);
		try {
			NativeMonero.nSetRecoveringFromSeed(bg, true);
			boolean connected;
			try {
				connected = NativeMonero.nInit(bg, NODE, "", false);
			} catch (Throwable t) {
				connected = false;
			}
			long tip = NativeMonero.nDaemonHeight(bg);
			assumeTrue("requires a reachable daemon", connected && tip > HIST_HEIGHT);
			assertEquals("with the marker, connect leaves the early restore "
					+ "height untouched so history before now is scanned",
					HIST_HEIGHT, NativeMonero.nGetRefreshFromHeight(bg));
		} finally {
			NativeMonero.nClose(bg, false);
		}
	}
}
