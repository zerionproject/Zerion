package com.professor.zerion.android.navdrawer;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;

import com.google.android.material.button.MaterialButton;
import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.UiUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.plugin.Plugin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.navdrawer.ConnectionChip.State.CLOCK;
import static com.professor.zerion.android.navdrawer.ConnectionChip.State.CONNECTED;
import static com.professor.zerion.android.navdrawer.ConnectionChip.State.CONNECTING;
import static com.professor.zerion.android.navdrawer.ConnectionChip.State.OFFLINE;
import static com.professor.zerion.android.navdrawer.ConnectionChip.State.PAUSED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.api.plugin.Plugin.State.ACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.DISABLED;
import static org.zerionproject.core.api.plugin.Plugin.State.STARTING_STOPPING;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ConnectionChipTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final Pattern TECHNICAL = Pattern.compile(
			"\\b(tor|onion|bridges?|relay|hidden service)\\b");

	private final Context ctx = new ContextThemeWrapper(
			RuntimeEnvironment.getApplication(), R.style.ZerionTheme);

	private static ConnectionChip.State decide(boolean offlineMode,
			boolean paused, Plugin.State tor, boolean internet,
			boolean published, boolean skewed, boolean relayFailing,
			int bootstrap) {
		return ConnectionChip.decide(offlineMode, paused, tor, internet,
				published, skewed, relayFailing, bootstrap);
	}

	@Test
	public void theChipFollowsTheStatusScreen() {
		assertEquals(CONNECTED, decide(false, false, ACTIVE, true, true, false,
				false, 100));
		assertEquals(CONNECTING, decide(false, false, ACTIVE, true, false,
				false, false, 100));
		assertEquals(CONNECTING, decide(false, false, STARTING_STOPPING, true,
				false, false, false, 40));
		assertEquals("Tor is connected while its entry relay struggles",
				CONNECTED, decide(false, false, ACTIVE, true, true,
						false, true, 100));
		assertEquals(OFFLINE, decide(true, false, ACTIVE, true, true, false,
				false, 100));
		assertEquals(OFFLINE, decide(false, false, ACTIVE, false, true, false,
				false, 100));
	}

	@Test
	public void pausedConnectionsArePausedNotConnecting() {
		assertEquals("paused connections remove the plugin", PAUSED,
				decide(false, true, null, true, false, false, false, 0));
		assertEquals("a stopped plugin still reads as starting", PAUSED,
				decide(false, true, STARTING_STOPPING, true, false, false,
						false, 0));
		assertEquals("offline mode wins over a pause", OFFLINE,
				decide(true, true, null, true, false, false, false, 0));
	}

	@Test
	public void aWrongClockIsNamed() {
		assertEquals(CLOCK, decide(false, false, ACTIVE, true, false, true,
				false, 100));
		assertEquals(CLOCK, decide(false, false, STARTING_STOPPING, true,
				false, true, false, 40));
		assertEquals("without internet the clock does not matter", OFFLINE,
				decide(false, false, ACTIVE, false, false, true, false, 100));
	}

	@Test
	public void aPluginBeingReplacedStillReadsConnecting() {
		assertEquals(CONNECTING, decide(false, false, null, true, false,
				false, false, 0));
		assertEquals(OFFLINE, decide(false, false, null, false, false,
				false, false, 0));
	}

	@Test
	public void theUserTurningTheConnectionOffReadsOffline() {
		assertEquals(OFFLINE, decide(false, false, DISABLED, true, false,
				false, false, 0));
	}

	private MaterialButton bound(ConnectionChip.State state) {
		MaterialButton chip = new MaterialButton(ctx);
		ConnectionChip.bind(chip, state, false);
		return chip;
	}

	@Test
	public void eachStateIsSaidInWordsNotOnlyInColour() {
		Set<String> texts = new HashSet<>();
		for (ConnectionChip.State s : ConnectionChip.State.values()) {
			MaterialButton chip = bound(s);
			String text = chip.getText().toString();
			texts.add(text);
			assertEquals(chip.getCurrentTextColor(),
					chip.getIconTint().getDefaultColor());
			assertEquals("Connection: " + text,
					String.valueOf(chip.getContentDescription()));
			assertEquals(UiUtils.resolveColorAttribute(ctx,
					ConnectionChip.color(s)), chip.getCurrentTextColor());
		}
		assertEquals(ConnectionChip.State.values().length, texts.size());
		assertEquals("Connected", bound(CONNECTED).getText().toString());
		assertEquals("Connecting\u2026",
				bound(CONNECTING).getText().toString());
		assertEquals("Offline", bound(OFFLINE).getText().toString());
		assertEquals("Paused", bound(PAUSED).getText().toString());
		assertEquals("Check the clock", bound(CLOCK).getText().toString());
		assertEquals(UiUtils.resolveColorAttribute(ctx,
						R.attr.zerionColorSuccess),
				bound(CONNECTED).getCurrentTextColor());
	}

	@Test
	public void onlyConnectedFoldsSoTheOtherStatesKeepTheirWords() {
		for (ConnectionChip.State s : ConnectionChip.State.values()) {
			assertFalse(ConnectionChip.folds(s, true));
			assertEquals(s.name(), s == CONNECTED,
					ConnectionChip.folds(s, false));
		}
	}

	@Test
	public void aFoldedChipStillStandsApartByItsColour() {
		int connected = UiUtils.resolveColorAttribute(ctx,
				ConnectionChip.color(CONNECTED));
		for (ConnectionChip.State s : ConnectionChip.State.values()) {
			if (s == CONNECTED) continue;
			assertTrue(s.name(), UiUtils.resolveColorAttribute(ctx,
					ConnectionChip.color(s)) != connected);
		}
	}

	@Test
	public void theTitleKeepsItsMinimumBesideTheChip() {
		float density = 2.625f;
		int bar = Math.round(360 * density);
		int others = Math.round(198 * density);
		int max = ConnectionChip.maxWidth(bar, others, density);
		assertTrue(bar - others - max >= Math.round(
				ConnectionChip.TITLE_MIN_DP * density));
		assertEquals(Math.round(ConnectionChip.CHIP_MIN_DP * density),
				ConnectionChip.maxWidth(bar, bar, density));
	}

	@Test
	public void aCompactChipKeepsItsSpokenLabel() {
		MaterialButton chip = new MaterialButton(ctx);
		ConnectionChip.bind(chip, CLOCK, true);
		assertEquals("", chip.getText().toString());
		assertEquals("Connection: Check the clock",
				String.valueOf(chip.getContentDescription()));
		assertEquals(0, chip.getIconPadding());
	}

	@Test
	public void theChipFoldsToADotWhenTheTitleNeedsTheRoom() {
		float density = 2.625f;
		float largestAppScale = 1.3f;
		int bar = Math.round(360 * density);
		int others = Math.round((6 + 6 + 12 + 48 + 48 + 48 + 4 + 14 + 8 + 4)
				* density);
		float glyph = 0.55f * 22 * largestAppScale * density;
		float title = "Private Groups".length() * glyph;
		float chipGlyph = 0.55f * 12 * largestAppScale * density;
		int chipWithText = Math.round((12 + 12 + 8 + 6) * density
				+ "Connecting\u2026".length() * chipGlyph);
		assertFalse("at 360dp and the largest text the title gets the room",
				ConnectionChip.showsText(bar, others, chipWithText, title));
		float shortTitle = "Chats".length() * 0.55f * 22 * density;
		int normalChip = Math.round((12 + 12 + 8 + 6) * density
				+ "Connected".length() * 0.55f * 12 * density);
		assertTrue("a short title leaves room for the words",
				ConnectionChip.showsText(bar, others, normalChip, shortTitle));
	}

	@Test
	public void theChipNeverTalksAboutTheNetworkUnderneath() {
		List<String> said = Arrays.asList(ctx.getString(R.string.connected),
				ctx.getString(R.string.connecting),
				ctx.getString(R.string.offline),
				ctx.getString(R.string.connection_chip_paused),
				ctx.getString(R.string.connection_chip_clock),
				ctx.getString(R.string.connection_chip_description, ""));
		for (String s : said) {
			assertFalse(s, TECHNICAL.matcher(s.toLowerCase(Locale.ROOT))
					.find());
		}
	}

	@Test
	public void theMainHeaderShowsTheChipAndItOpensTheStatusScreen() {
		NavDrawerActivity a = Robolectric.buildActivity(
				NavDrawerActivity.class).setup().get();
		shadowOf(Looper.getMainLooper()).idle();
		View view = a.findViewById(R.id.connectionChip);
		assertTrue(view instanceof MaterialButton);
		MaterialButton chip = (MaterialButton) view;
		assertEquals(View.VISIBLE, chip.getVisibility());
		String text = chip.getText().toString();
		assertTrue(text, Arrays.asList("Connected", "Connecting…",
				"Offline", "Paused", "Check the clock").contains(text));
		assertEquals("Connection: " + text,
				String.valueOf(chip.getContentDescription()));
		assertTrue(chip.getMinimumHeight() >= Math.round(48 * a
				.getResources().getDisplayMetrics().density));
		while (shadowOf(a).getNextStartedActivity() != null) {
			shadowOf(Looper.getMainLooper()).idle();
		}
		chip.performClick();
		Intent next = shadowOf(a).getNextStartedActivity();
		assertNotNull(next);
		assertEquals(NetworkStatusActivity.class.getName(),
				next.getComponent().getClassName());
	}
}
