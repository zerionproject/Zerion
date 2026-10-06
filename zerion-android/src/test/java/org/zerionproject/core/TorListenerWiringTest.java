package org.zerionproject.core;

import android.app.Application;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.plugin.OnionTargetFactory;
import org.zerionproject.core.socks.UnixOnionTargetFactory;
import org.zerionproject.tor.TorWrapper;
import org.zerionproject.transport.TorProcessWatch;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class TorListenerWiringTest {

	private static final String SOCKS =
			"/data/user/0/com.professor.zerion/files/zs/0123456789ab";

	private final Application app = ApplicationProvider.getApplicationContext();

	private static List<String> lines(String torrc) {
		List<String> out = new ArrayList<>();
		for (String l : torrc.split("\n")) if (!l.isEmpty()) out.add(l);
		return out;
	}

	private static Object wrapperField(TorWrapper tor, String name)
			throws Exception {
		Field f = Class.forName("org.zerionproject.tor.AbstractTorWrapper")
				.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(tor);
	}

	@Test
	public void theAppGivesTorAUnixControlSocketBesideTheSocksOne()
			throws Exception {
		File socks = new File(app.getFilesDir(), "zs/0123456789ab");
		TorWrapper tor = new ZerionTorWrapperModule().provideZerionTorWrapper(
				app, mock(AndroidWakeLockManager.class), Runnable::run,
				Runnable::run, new File(app.getFilesDir(), "tor"), 59060,
				59061, socks, new TorProcessWatch());
		assertEquals(socks.getAbsolutePath() + "c",
				wrapperField(tor, "controlSocketPath"));
	}

	@Test
	public void torWithAUnixControlSocketOpensNoTcpListener()
			throws Exception {
		TorWrapper tor = new ZerionTorWrapper(app,
				mock(AndroidWakeLockManager.class), Runnable::run,
				Runnable::run, "arm64_pie", new File(app.getFilesDir(), "tor"),
				59060, 59061, ZerionTorWrapperModule.controlSocketPath(SOCKS),
				(t, l) -> {
				}, new TorProcessWatch());
		Method torrc = Class.forName("org.zerionproject.tor.AbstractTorWrapper")
				.getDeclaredMethod("torrc");
		torrc.setAccessible(true);
		List<String> conf = lines((String) torrc.invoke(tor));
		List<String> control = new ArrayList<>();
		List<String> socksLines = new ArrayList<>();
		for (String l : conf) {
			if (l.startsWith("ControlPort ")) control.add(l);
			if (l.startsWith("SocksPort ")) socksLines.add(l);
			assertFalse(l, l.contains("59060"));
			assertFalse(l, l.contains("59061"));
		}
		assertEquals(1, control.size());
		assertEquals("ControlPort unix:" + SOCKS + "c", control.get(0));
		assertEquals(1, socksLines.size());
		assertEquals("SocksPort 0", socksLines.get(0));
	}

	@Test
	public void everyOnionServiceForwardsToAUnixSocket() {
		OnionTargetFactory targets =
				new ZerionTorWrapperModule().provideOnionTargetFactory(app);
		assertTrue(targets instanceof UnixOnionTargetFactory);
	}
}
