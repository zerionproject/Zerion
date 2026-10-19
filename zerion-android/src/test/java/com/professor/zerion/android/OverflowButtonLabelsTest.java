package com.professor.zerion.android;

import android.os.Looper;
import android.view.View;

import com.professor.zerion.R;
import com.professor.zerion.android.navdrawer.NavDrawerActivity;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class OverflowButtonLabelsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final File RES = new File("src/main/res");
	private static final File LAYOUTS = new File(RES, "layout");
	private static final String MORE =
			"@string/selection_more_description";
	private static final Set<String> DOTS = new HashSet<>(Arrays.asList(
			"@drawable/ic_more_vert", "@drawable/ic_more_vert_accent"));
	private static final Set<String> OPEN_SETTINGS = new HashSet<>(
			Arrays.asList("fragment_vault_wallet.xml#@+id/wallet_settings",
					"fragment_xmr_detail.xml#@+id/xmr_settings"));

	private static Element parse(File f) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(f).getDocumentElement();
	}

	private static List<Element> all(Element root) {
		List<Element> out = new ArrayList<>();
		out.add(root);
		NodeList nodes = root.getElementsByTagName("*");
		for (int i = 0; i < nodes.getLength(); i++) {
			out.add((Element) nodes.item(i));
		}
		return out;
	}

	private static Map<String, String> englishStrings() throws Exception {
		Map<String, String> out = new HashMap<>();
		NodeList strings = parse(new File(RES, "values/strings.xml"))
				.getElementsByTagName("string");
		for (int i = 0; i < strings.getLength(); i++) {
			Element s = (Element) strings.item(i);
			out.put("@string/" + s.getAttribute("name"),
					s.getTextContent().trim());
		}
		return out;
	}

	private static File[] files(File dir) {
		File[] files = dir.listFiles((d, n) -> n.endsWith(".xml"));
		assertTrue(dir.toString(), files != null && files.length > 0);
		Arrays.sort(files);
		return files;
	}

	private static String icon(Element e) {
		for (String attr : new String[] {"android:src", "app:srcCompat",
				"app:icon", "android:icon"}) {
			if (e.hasAttribute(attr)) return e.getAttribute(attr);
		}
		return "";
	}

	@Test
	public void everyDotsButtonSaysMoreInTheAppsOwnWords() throws Exception {
		List<String> problems = new ArrayList<>();
		int checked = 0;
		for (File f : files(LAYOUTS)) {
			for (Element e : all(parse(f))) {
				if (!DOTS.contains(icon(e))) continue;
				checked++;
				String label = e.getAttribute("android:contentDescription");
				if (!MORE.equals(label)) {
					problems.add(f.getName() + " "
							+ e.getAttribute("android:id") + " says " + label);
				}
			}
		}
		assertTrue("checked " + checked, checked >= 2);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theDotsWordsAreTranslatedForEveryLanguage() throws Exception {
		String name = MORE.substring("@string/".length());
		List<String> missing = new ArrayList<>();
		int locales = 0;
		File[] dirs = RES.listFiles((d, n) -> n.startsWith("values-"));
		assertTrue(dirs != null);
		for (File dir : dirs) {
			File strings = new File(dir, "strings.xml");
			if (!strings.exists()) continue;
			locales++;
			boolean found = false;
			NodeList all = parse(strings).getElementsByTagName("string");
			for (int i = 0; i < all.getLength(); i++) {
				if (((Element) all.item(i)).getAttribute("name")
						.equals(name)) {
					found = true;
				}
			}
			if (!found) missing.add(dir.getName());
		}
		assertTrue("locales " + locales, locales >= 45);
		assertEquals(String.join(", ", missing), 0, missing.size());
	}

	@Test
	public void theSelectionBarsDotsSayMoreActionsNotSettings()
			throws Exception {
		Map<String, String> english = englishStrings();
		List<String> problems = new ArrayList<>();
		for (File f : files(new File(RES, "menu"))) {
			for (Element e : all(parse(f))) {
				if (!DOTS.contains(icon(e))) continue;
				String title = english.get(e.getAttribute("android:title"));
				if (title == null || title.toLowerCase(Locale.ROOT)
						.contains("settings")) {
					problems.add(f.getName() + " "
							+ e.getAttribute("android:id") + " is " + title);
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
		String bar = new String(Files.readAllBytes(new File(
				"src/main/java/com/professor/zerion/android/selection/"
						+ "SelectionBar.java").toPath()), StandardCharsets.UTF_8);
		assertTrue(bar.contains("R.string.selection_more_description"));
		assertEquals("More actions",
				english.get("@string/selection_more_description"));
	}

	@Test
	public void noButtonSaysSettingsUnlessItOpensSettings() throws Exception {
		Map<String, String> english = englishStrings();
		List<String> problems = new ArrayList<>();
		int checked = 0;
		for (File f : files(LAYOUTS)) {
			for (Element e : all(parse(f))) {
				String label = english.get(
						e.getAttribute("android:contentDescription"));
				if (label == null || !label.toLowerCase(Locale.ROOT)
						.contains("settings")) {
					continue;
				}
				checked++;
				String where = f.getName() + "#" + e.getAttribute("android:id");
				if (!OPEN_SETTINGS.contains(where)) {
					problems.add(where + " is labelled " + label);
				}
				if (DOTS.contains(icon(e))) {
					problems.add(where + " shows dots but says " + label);
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
		assertEquals("the wallet gears that do open settings are still found",
				OPEN_SETTINGS.size(), checked);
	}

	@Test
	public void theHomeOverflowButtonIsReadAsMoreActions() {
		NavDrawerActivity home = Robolectric.buildActivity(
				NavDrawerActivity.class).setup().get();
		shadowOf(Looper.getMainLooper()).idle();
		View dots = home.findViewById(R.id.menuButton);
		assertEquals(home.getString(R.string.selection_more_description),
				String.valueOf(dots.getContentDescription()));
		assertEquals("More actions",
				String.valueOf(dots.getContentDescription()));
		View profile = home.findViewById(R.id.profileIcon);
		assertFalse(String.valueOf(profile.getContentDescription())
				.equals(home.getString(R.string.settings_button)));
	}
}
