package com.professor.zerion.android.navdrawer;

import android.os.Bundle;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.professor.zerion.android.navdrawer.NavDrawerActivity.STATE_TAB;
import static com.professor.zerion.android.navdrawer.NavDrawerActivity.TAB_CHANNELS;
import static com.professor.zerion.android.navdrawer.NavDrawerActivity.TAB_CONTACTS;
import static com.professor.zerion.android.navdrawer.NavDrawerActivity.TAB_GROUPS;
import static com.professor.zerion.android.navdrawer.NavDrawerActivity.TAB_VAULT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class HomeScreenThemeTest {

	private static final File MAIN = new File("src/main");
	private static final Pattern STATIC_COLOR = Pattern.compile(
			"android:(textColor|background|drawableTint)=\"@color/(?!tab_icon_tint\")"
					+ "|app:(tint|backgroundTint|cardBackgroundColor)=\"@color/");

	private static String read(String path) throws Exception {
		return new String(Files.readAllBytes(new File(MAIN, path).toPath()),
				StandardCharsets.UTF_8);
	}

	private static List<File> javaSources() {
		List<File> out = new ArrayList<>();
		List<File> stack = new ArrayList<>();
		stack.add(new File(MAIN, "java"));
		while (!stack.isEmpty()) {
			File f = stack.remove(stack.size() - 1);
			File[] list = f.listFiles();
			if (list != null) {
				stack.addAll(Arrays.asList(list));
			} else if (f.getName().endsWith(".java")) {
				out.add(f);
			}
		}
		return out;
	}

	@Test
	public void theAppNeverMixesWallpaperColorsIntoItsOwnTheme()
			throws Exception {
		List<File> files = javaSources();
		assertTrue("scanned " + files.size(), files.size() > 500);
		List<String> problems = new ArrayList<>();
		for (File f : files) {
			String s = new String(Files.readAllBytes(f.toPath()),
					StandardCharsets.UTF_8);
			if (s.contains("DynamicColors")) problems.add(f.getName());
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theHomeScreenTakesEveryColorFromTheTheme() throws Exception {
		String layout = read("res/layout/activity_nav_drawer.xml");
		List<String> problems = new ArrayList<>();
		Matcher m = STATIC_COLOR.matcher(layout);
		while (m.find()) problems.add(m.group());
		assertEquals(String.join("\n", problems), 0, problems.size());

		String tint = read("res/color/tab_icon_tint.xml");
		assertFalse(tint, tint.contains("colorPrimary"));
		assertFalse(tint, tint.contains("@color/"));

		String activity = read(
				"java/com/professor/zerion/android/navdrawer/NavDrawerActivity.java");
		assertFalse(activity.contains("R.color.zerion_text_"));
	}

	@Test
	public void aRecreatedHomeScreenShowsTheTabItWasOn() {
		for (int tab : new int[] {TAB_CONTACTS, TAB_GROUPS, TAB_CHANNELS,
				TAB_VAULT}) {
			Bundle saved = new Bundle();
			saved.putInt(STATE_TAB, tab);
			assertEquals(tab, NavDrawerActivity.restoredTab(saved));
		}
		assertEquals(TAB_CONTACTS, NavDrawerActivity.restoredTab(null));
		Bundle odd = new Bundle();
		odd.putInt(STATE_TAB, 42);
		assertEquals(TAB_CONTACTS, NavDrawerActivity.restoredTab(odd));
	}

	@Test
	public void theTitleNamesTheTabThatIsShown() {
		assertEquals(R.string.chats_button,
				NavDrawerActivity.titleFor(TAB_CONTACTS));
		assertEquals(R.string.groups_button,
				NavDrawerActivity.titleFor(TAB_GROUPS));
		assertEquals(R.string.channels_button,
				NavDrawerActivity.titleFor(TAB_CHANNELS));
		assertEquals(R.string.vault_button,
				NavDrawerActivity.titleFor(TAB_VAULT));
	}

	@Test
	public void aRecreatedHomeScreenShowsTheButtonsAndTabsOfWhatItShows()
			throws Exception {
		String activity = read(
				"java/com/professor/zerion/android/navdrawer/NavDrawerActivity.java");
		int restore = activity.indexOf("currentTab = restoredTab(state);");
		assertTrue(restore > 0);
		String branch = activity.substring(restore,
				activity.indexOf("maybeShowPostUpdateNotice();", restore));
		assertTrue(branch, branch.contains("if (!signingOut) {"));
		assertTrue(branch, branch.contains("findFragmentById(R.id.fragmentContainer)"));
		assertTrue(branch, branch.contains("updateFabVisibilityForFragment("));
		assertTrue(branch, branch.contains("applyBottomNavForFragment("));
	}

	@Test
	public void theVaultIsOnlyReopenedWhenNoSignOutIsShowing() {
		assertTrue(NavDrawerActivity.mustReopenVault(TAB_VAULT, false, false));
		assertFalse(NavDrawerActivity.mustReopenVault(TAB_VAULT, false, true));
		assertFalse(NavDrawerActivity.mustReopenVault(TAB_VAULT, true, false));
		assertFalse(NavDrawerActivity.mustReopenVault(TAB_GROUPS, false,
				false));
	}

	@Test
	public void theTitleInTheLayoutMatchesTheFirstTab() throws Exception {
		String layout = read("res/layout/activity_nav_drawer.xml");
		assertFalse(layout.contains("@string/contact_list_button"));
	}
}
