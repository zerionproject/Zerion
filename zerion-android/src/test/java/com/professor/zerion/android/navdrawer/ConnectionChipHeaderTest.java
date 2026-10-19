package com.professor.zerion.android.navdrawer;

import android.content.Context;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.professor.zerion.R;
import com.professor.zerion.android.EarlyPrefs;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.settings.ChatPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, qualifiers = "w360dp-h740dp")
public class ConnectionChipHeaderTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static NavDrawerActivity open() {
		NavDrawerActivity a = Robolectric.buildActivity(
				NavDrawerActivity.class).setup().get();
		idle();
		return a;
	}

	@Test
	public void pausedConnectionsSayPausedInsteadOfConnecting() {
		ZerionApplication app = ApplicationProvider.getApplicationContext();
		app.getApplicationComponent().pluginManager()
				.setConnectionsPaused(true);
		MaterialButton chip = open().findViewById(R.id.connectionChip);
		assertEquals("Paused", chip.getText().toString());
		assertEquals("Connection: Paused",
				String.valueOf(chip.getContentDescription()));
	}

	private static void longTitle(NavDrawerActivity a) {
		TextView title = a.findViewById(R.id.toolbarTitle);
		StringBuilder longTitle = new StringBuilder();
		for (int i = 0; i < 600; i++) longTitle.append('W');
		title.setText(longTitle);
		title.requestLayout();
		idle();
		idle();
	}

	@Test
	public void aStateThatNeedsWordsKeepsThemWhenTheTitleIsLong() {
		Context ctx = ApplicationProvider.getApplicationContext();
		EarlyPrefs.setGuiTextSize(ctx, 3);
		ChatPreferences.invalidateGuiFontScale();
		NavDrawerActivity a = open();
		MaterialButton chip = a.findViewById(R.id.connectionChip);
		String label = chip.getText().toString();
		assertFalse(label.isEmpty());
		longTitle(a);
		assertEquals("the words stay", label, chip.getText().toString());
		assertEquals("Connection: " + label,
				String.valueOf(chip.getContentDescription()));
		float density = a.getResources().getDisplayMetrics().density;
		TextView title = a.findViewById(R.id.toolbarTitle);
		assertTrue("the title keeps its minimum: " + title.getWidth(),
				title.getWidth() >= Math.round(
						ConnectionChip.TITLE_MIN_DP * density));
		ChatPreferences.invalidateGuiFontScale();
	}

	@Test
	public void theWordsStayOnOneLineInsideThePillAtTheLargestText() {
		Context ctx = ApplicationProvider.getApplicationContext();
		EarlyPrefs.setGuiTextSize(ctx, 3);
		ChatPreferences.invalidateGuiFontScale();
		NavDrawerActivity a = open();
		longTitle(a);
		MaterialButton chip = a.findViewById(R.id.connectionChip);
		assertEquals(1, chip.getMaxLines());
		assertEquals(TextUtils.TruncateAt.END, chip.getEllipsize());
		assertEquals("the pill grows with the text instead of clipping it",
				ViewGroup.LayoutParams.WRAP_CONTENT,
				chip.getLayoutParams().height);
		float density = a.getResources().getDisplayMetrics().density;
		assertTrue(chip.getMinimumHeight() >= Math.round(48 * density));
		assertTrue("lines " + chip.getLineCount(), chip.getLineCount() <= 1);
		TextView title = a.findViewById(R.id.toolbarTitle);
		assertTrue("the title keeps its minimum: " + title.getWidth(),
				title.getWidth() >= Math.round(
						ConnectionChip.TITLE_MIN_DP * density));
		ChatPreferences.invalidateGuiFontScale();
	}

	@Test
	public void pausedKeepsItsWordWhenTheTitleIsLong() {
		ZerionApplication app = ApplicationProvider.getApplicationContext();
		app.getApplicationComponent().pluginManager()
				.setConnectionsPaused(true);
		NavDrawerActivity a = open();
		longTitle(a);
		MaterialButton chip = a.findViewById(R.id.connectionChip);
		assertEquals("Paused", chip.getText().toString());
	}

	@Test
	public void theHeaderDoesNotNeedTheNetworkScreensModel() throws Exception {
		String src = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/navdrawer/"
						+ "NavDrawerActivity.java")), StandardCharsets.UTF_8);
		assertFalse("the header builds the network screen's model",
				src.contains("PluginViewModel"));
		assertTrue(src.contains("ConnectionStatusViewModel"));
	}
}
