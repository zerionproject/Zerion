package com.professor.zerion.android.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TransportStateEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class UpdateCheckerTest {

	private static final String RELEASES =
			"https://github.com/zerionproject/Zerion/releases";
	private static final String CERT =
			"d7fdb11125890d133ae89d8ba4f4331d9045e21ef01d9899a7cdee6888f704c8";
	private static final long HOUR = 60L * 60 * 1000;

	private static KeyPair ours;

	private final List<EventListener> listeners = new CopyOnWriteArrayList<>();
	private final EventBus bus = new EventBus() {
		@Override
		public void addListener(EventListener l) {
			listeners.add(l);
		}

		@Override
		public void removeListener(EventListener l) {
			listeners.remove(l);
		}

		@Override
		public void broadcast(Event e) {
			for (EventListener l : listeners) l.eventOccurred(e);
		}
	};
	private final PluginManager pluginManager = mock(PluginManager.class);
	private final Plugin tor = mock(Plugin.class);
	private SharedPreferences prefs;
	private long now = 1_800_000_000_000L;
	private int fetches = 0;
	private AnnouncementSource.Fetched next;
	private boolean failNext = false;
	private String store = null;
	private final List<UpdateChecker.Result> results = new ArrayList<>();

	@BeforeClass
	public static void key() throws Exception {
		KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
		rsa.initialize(2048);
		ours = rsa.generateKeyPair();
	}

	@Before
	public void setUp() {
		prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
				"update-checker-test", Context.MODE_PRIVATE);
		prefs.edit().clear().commit();
		when(pluginManager.getPlugin(TorConstants.ID)).thenReturn(tor);
		when(tor.getState()).thenReturn(Plugin.State.INACTIVE);
	}

	private static byte[] manifest(String v, long code) {
		return ("{\"android\":{\"version\":\"" + v + "\",\"versionCode\":" + code
				+ ",\"tag\":\"v" + v + "\",\"signingCertSha256\":\"" + CERT
				+ "\",\"apk\":{\"url\":\"" + RELEASES + "/download/v" + v
				+ "/zerion-" + v + ".apk\",\"sha256\":\""
				+ "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
				+ "\"}}}").getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] sign(byte[] m) throws Exception {
		Signature s = Signature.getInstance("SHA256withRSA");
		s.initSign(ours.getPrivate());
		s.update(ReleaseAnnouncements.CONTEXT);
		s.update(m);
		return Base64.getEncoder().encode(s.sign());
	}

	private void announce(String v, long code) throws Exception {
		byte[] m = manifest(v, code);
		next = new AnnouncementSource.Fetched(m, sign(m));
	}

	private UpdateChecker checker(long installed) {
		AnnouncementSource source = () -> {
			fetches++;
			if (failNext) throw new IOException("no route");
			return next;
		};
		return new UpdateChecker(bus, pluginManager, Runnable::run,
				() -> prefs, source,
				() -> Collections.singletonList(new ReleaseAnnouncements.Signer(
						ours.getPublic(), CERT)),
				() -> store, () -> now, RELEASES, installed, "3.0.16");
	}

	private void torConnects() {
		when(tor.getState()).thenReturn(Plugin.State.ACTIVE);
		bus.broadcast(new TransportStateEvent(TorConstants.ID,
				Plugin.State.ACTIVE));
		idle();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private UpdateChecker.Result checkNow(UpdateChecker c) {
		results.clear();
		c.checkNow(results::add);
		idle();
		assertEquals(1, results.size());
		return results.get(0);
	}

	@Test
	public void aSignedNewerVersionPopsUpOnceADayWhenTorConnects()
			throws Exception {
		announce("3.0.17", 31700);
		UpdateChecker c = checker(31600);
		torConnects();
		ReleaseAnnouncement a = c.getPopup().getValue();
		assertNotNull(a);
		assertEquals("3.0.17", a.versionName);
		assertEquals(RELEASES + "/tag/v3.0.17", a.releasePageUrl);

		c.popupShown(a);
		assertNull(c.getPopup().getValue());
		torConnects();
		c.onAppForeground();
		idle();
		assertEquals("one check a day", 1, fetches);
		assertNull(c.getPopup().getValue());

		now += 25 * HOUR;
		c.onAppForeground();
		idle();
		assertEquals(2, fetches);
		assertNotNull("shown again the next day", c.getPopup().getValue());
	}

	@Test
	public void anUnverifiableAnnouncementNeverPopsUp() throws Exception {
		byte[] m = manifest("3.0.17", 31700);
		byte[] wrong = sign(manifest("3.0.18", 31800));
		next = new AnnouncementSource.Fetched(m, wrong);
		UpdateChecker c = checker(31600);
		torConnects();
		assertNull(c.getPopup().getValue());
		assertEquals(UpdateChecker.Outcome.NOT_VERIFIED, checkNow(c).outcome);

		next = new AnnouncementSource.Fetched(m, null);
		assertEquals("no signature file", UpdateChecker.Outcome.NOT_VERIFIED,
				checkNow(c).outcome);
		next = new AnnouncementSource.Fetched(null, null);
		assertEquals(UpdateChecker.Outcome.NOT_VERIFIED, checkNow(c).outcome);
	}

	@Test
	public void theSameVersionDoesNotPopUp() throws Exception {
		announce("3.0.16", 31600);
		UpdateChecker c = checker(31600);
		torConnects();
		assertNull(c.getPopup().getValue());
		UpdateChecker.Result r = checkNow(c);
		assertEquals(UpdateChecker.Outcome.UP_TO_DATE, r.outcome);
	}

	@Test
	public void storeInstallsNeverFetch() throws Exception {
		announce("3.0.17", 31700);
		store = "F-Droid";
		UpdateChecker c = checker(31600);
		torConnects();
		assertEquals(UpdateChecker.Outcome.STORE_INSTALL, checkNow(c).outcome);
		assertEquals(0, fetches);
		assertNull(c.getPopup().getValue());
	}

	@Test
	public void nothingIsFetchedBeforeTorIsConnected() throws Exception {
		announce("3.0.17", 31700);
		UpdateChecker c = checker(31600);
		c.onAppForeground();
		idle();
		assertEquals(UpdateChecker.Outcome.TOR_NOT_READY, checkNow(c).outcome);
		assertEquals(0, fetches);
	}

	@Test
	public void switchedOffStopsAutomaticChecksButTheButtonStillWorks()
			throws Exception {
		announce("3.0.17", 31700);
		UpdateChecker c = checker(31600);
		c.setAutomaticEnabled(false);
		torConnects();
		assertEquals(0, fetches);
		assertNull(c.getPopup().getValue());
		UpdateChecker.Result r = checkNow(c);
		assertEquals(UpdateChecker.Outcome.AVAILABLE, r.outcome);
		assertEquals(1, fetches);
	}

	@Test
	public void aFailedDownloadIsRetriedAfterAnHour() throws Exception {
		announce("3.0.17", 31700);
		failNext = true;
		UpdateChecker c = checker(31600);
		torConnects();
		assertEquals(1, fetches);
		assertNull(c.getPopup().getValue());

		failNext = false;
		now += 30 * 60 * 1000;
		c.onAppForeground();
		idle();
		assertEquals("not before the hour", 1, fetches);

		now += 31 * 60 * 1000;
		c.onAppForeground();
		idle();
		assertEquals(2, fetches);
		assertNotNull(c.getPopup().getValue());
	}
}
