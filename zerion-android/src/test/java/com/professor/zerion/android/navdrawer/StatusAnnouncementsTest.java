package com.professor.zerion.android.navdrawer;

import android.content.Context;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityManager;
import org.zerionproject.core.api.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import androidx.fragment.app.Fragment;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, qualifiers = "w360dp-h740dp")
public class StatusAnnouncementsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static ShadowAccessibilityManager listen(Context ctx) {
		ShadowAccessibilityManager talkBack = shadowOf((AccessibilityManager)
				ctx.getSystemService(Context.ACCESSIBILITY_SERVICE));
		talkBack.setEnabled(true);
		return talkBack;
	}

	private static void wait(int ms) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
	}

	private static List<String> said(ShadowAccessibilityManager talkBack) {
		wait(3_000);
		List<String> out = new ArrayList<>();
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
				out.add(e.getText().toString());
			}
		}
		return out;
	}

	private static void set(Object o, Class<?> c, String name, Object value)
			throws Exception {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		f.set(o, value);
	}

	private static void chip(NavDrawerActivity home, ConnectionChip.State s)
			throws Exception {
		set(home, NavDrawerActivity.class, "chipState", s);
		Method fit = NavDrawerActivity.class.getDeclaredMethod(
				"fitConnectionChip");
		fit.setAccessible(true);
		fit.invoke(home);
		idle();
	}

	@Test
	public void theChipSaysEachNewStateOnceAndNeverOnARefit()
			throws Exception {
		NavDrawerActivity home = Robolectric.buildActivity(
				NavDrawerActivity.class).setup().get();
		idle();
		ShadowAccessibilityManager talkBack = listen(home);
		chip(home, ConnectionChip.State.CONNECTING);
		chip(home, ConnectionChip.State.CONNECTING);
		int before = said(talkBack).size();

		chip(home, ConnectionChip.State.CONNECTED);
		List<String> said = said(talkBack);
		assertEquals(said.toString(), before + 1, said.size());
		assertEquals("[" + home.getString(R.string.connection_chip_description,
				home.getString(R.string.connected)) + "]",
				said.get(said.size() - 1));

		set(home, NavDrawerActivity.class, "boundCompact", true);
		chip(home, ConnectionChip.State.CONNECTED);
		chip(home, ConnectionChip.State.CONNECTED);
		assertEquals("folding the chip is not news", before + 1,
				said(talkBack).size());
	}

	@Test
	public void theChipSaysNothingWhenItFlapsAndOnceWhenAStateHolds()
			throws Exception {
		NavDrawerActivity home = Robolectric.buildActivity(
				NavDrawerActivity.class).setup().get();
		idle();
		ShadowAccessibilityManager talkBack = listen(home);
		chip(home, ConnectionChip.State.CONNECTED);
		int before = said(talkBack).size();

		chip(home, ConnectionChip.State.CONNECTING);
		wait(400);
		chip(home, ConnectionChip.State.CONNECTED);
		wait(400);
		chip(home, ConnectionChip.State.CONNECTING);
		wait(100);
		chip(home, ConnectionChip.State.CONNECTED);
		assertEquals("a flap within a second is not news", before,
				said(talkBack).size());

		chip(home, ConnectionChip.State.CONNECTING);
		wait(1_000);
		assertEquals("not yet", before, countNow(talkBack));
		List<String> said = said(talkBack);
		assertEquals(said.toString(), before + 1, said.size());
		assertEquals("[" + home.getString(R.string.connection_chip_description,
				home.getString(R.string.connecting)) + "]",
				said.get(said.size() - 1));
	}

	@Test
	public void nothingIsSaidAfterTheHomeScreenGoesAway() throws Exception {
		ActivityController<NavDrawerActivity> c = Robolectric.buildActivity(
				NavDrawerActivity.class).setup();
		NavDrawerActivity home = c.get();
		idle();
		ShadowAccessibilityManager talkBack = listen(home);
		chip(home, ConnectionChip.State.CONNECTED);
		int before = said(talkBack).size();
		chip(home, ConnectionChip.State.CONNECTING);
		c.pause();
		assertEquals(before, said(talkBack).size());
	}

	@Test
	public void aStateThatArrivedJustBeforeLeavingIsSaidOnReturn()
			throws Exception {
		ActivityController<NavDrawerActivity> c = Robolectric.buildActivity(
				NavDrawerActivity.class).setup();
		NavDrawerActivity home = c.get();
		idle();
		ShadowAccessibilityManager talkBack = listen(home);
		chip(home, ConnectionChip.State.CONNECTED);
		int before = said(talkBack).size();
		chip(home, ConnectionChip.State.CONNECTING);
		c.pause();
		assertEquals("nothing while away", before, said(talkBack).size());
		c.resume();
		List<String> said = said(talkBack);
		assertEquals(said.toString(), before + 1, said.size());
		assertEquals("[" + home.getString(R.string.connection_chip_description,
				home.getString(R.string.connecting)) + "]",
				said.get(said.size() - 1));
		c.pause();
		c.resume();
		assertEquals("said once", before + 1, said(talkBack).size());
	}

	@Test
	public void theTorLineSaysNothingWhenItFlaps() throws Exception {
		ActivityController<NetworkStatusActivity> c = Robolectric
				.buildActivity(NetworkStatusActivity.class).setup();
		NetworkStatusActivity screen = c.get();
		idle();
		Fragment f = screen.getSupportFragmentManager()
				.findFragmentById(R.id.fragmentContainer);
		assertNotNull(f);
		ShadowAccessibilityManager talkBack = listen(screen);
		Method update = TorStatusFragment.class.getDeclaredMethod(
				"updateTorStatus", Plugin.State.class);
		update.setAccessible(true);
		update.invoke(f, Plugin.State.STARTING_STOPPING);
		int before = said(talkBack).size();

		update.invoke(f, Plugin.State.DISABLED);
		wait(500);
		update.invoke(f, Plugin.State.STARTING_STOPPING);
		assertEquals("a flap is not news", before, said(talkBack).size());

		update.invoke(f, Plugin.State.DISABLED);
		c.pause();
		assertEquals("nothing after the screen goes away", before,
				said(talkBack).size());
	}

	private static int countNow(ShadowAccessibilityManager talkBack) {
		idle();
		int n = 0;
		for (AccessibilityEvent e : talkBack.getSentAccessibilityEvents()) {
			if (e.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) n++;
		}
		return n;
	}

	@Test
	public void theTorLineSaysANewStatusOnceAndNotEachPercent()
			throws Exception {
		NetworkStatusActivity screen = Robolectric.buildActivity(
				NetworkStatusActivity.class).setup().get();
		idle();
		Fragment f = screen.getSupportFragmentManager()
				.findFragmentById(R.id.fragmentContainer);
		assertNotNull(f);
		ShadowAccessibilityManager talkBack = listen(screen);
		Method update = TorStatusFragment.class.getDeclaredMethod(
				"updateTorStatus", Plugin.State.class);
		update.setAccessible(true);

		set(f, TorStatusFragment.class, "torBootstrap", 40);
		update.invoke(f, Plugin.State.STARTING_STOPPING);
		idle();
		int before = said(talkBack).size();
		set(f, TorStatusFragment.class, "torBootstrap", 60);
		update.invoke(f, Plugin.State.STARTING_STOPPING);
		set(f, TorStatusFragment.class, "torBootstrap", 80);
		update.invoke(f, Plugin.State.STARTING_STOPPING);
		TextView line = screen.findViewById(R.id.torStatusText);
		assertTrue(line.getText().toString(),
				line.getText().toString().contains("80"));
		assertEquals("progress is not news", before, said(talkBack).size());

		update.invoke(f, Plugin.State.DISABLED);
		update.invoke(f, Plugin.State.DISABLED);
		List<String> said = said(talkBack);
		assertEquals(said.toString(), before + 1, said.size());
		assertEquals("[" + screen.getString(R.string.disabled) + "]",
				said.get(said.size() - 1));
	}
}
