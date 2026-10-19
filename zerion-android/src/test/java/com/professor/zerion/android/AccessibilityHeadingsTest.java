package com.professor.zerion.android;

import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.selection.MessageInfo;
import com.professor.zerion.android.selection.SelectionBarTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AccessibilityHeadingsTest {

	private static final File RES = new File("src/main/res");
	private static final List<String> HEADING_STYLES = Arrays.asList(
			"Zerion.Settings.SectionHeader", "GroupTrAdminSectionLabel");
	private static final String[][] HEADINGS = {
			{"activity_nav_drawer.xml", "@+id/toolbarTitle"},
			{"activity_channel_settings.xml", "@+id/channelSettingsHeader"},
			{"activity_grouptr_admin.xml", "@+id/headerName"},
			{"activity_grouptr_admin.xml", "@+id/membersHeader"},
			{"activity_grouptr_admin.xml",
					"@string/grouptr_admin_section_group_settings"},
			{"activity_grouptr_admin.xml",
					"@string/grouptr_admin_section_danger"},
			{"sheet_channel_member.xml", "@+id/channelMemberName"},
			{"sheet_channel_delete_auth.xml", "@+id/channelDeleteAuthTitle"},
	};

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

	private static Element style(String name) throws Exception {
		NodeList styles = parse(new File(RES, "values/styles.xml"))
				.getElementsByTagName("style");
		for (int i = 0; i < styles.getLength(); i++) {
			Element s = (Element) styles.item(i);
			if (s.getAttribute("name").equals(name)) return s;
		}
		throw new AssertionError(name);
	}

	private static boolean styleIsHeading(String name) throws Exception {
		NodeList items = style(name).getElementsByTagName("item");
		for (int i = 0; i < items.getLength(); i++) {
			Element item = (Element) items.item(i);
			if (item.getAttribute("name").equals("android:accessibilityHeading")
					&& item.getTextContent().trim().equals("true")) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void sectionTitleStylesAreHeadings() throws Exception {
		for (String name : HEADING_STYLES) {
			assertTrue(name, styleIsHeading(name));
		}
		int titles = 0;
		File[] layouts = new File(RES, "layout").listFiles(
				(d, n) -> n.endsWith(".xml"));
		assertNotNull(layouts);
		for (File f : layouts) {
			for (Element e : all(parse(f))) {
				String s = e.getAttribute("style");
				if (s.startsWith("@style/") && HEADING_STYLES.contains(
						s.substring("@style/".length()))) {
					titles++;
				}
			}
		}
		assertTrue("section titles found: " + titles, titles >= 30);
	}

	@Test
	public void screenAndSheetTitlesAreHeadings() throws Exception {
		List<String> problems = new ArrayList<>();
		for (String[] h : HEADINGS) {
			Element found = null;
			for (Element e : all(parse(new File(RES, "layout/" + h[0])))) {
				if (h[1].equals(e.getAttribute("android:id"))
						|| h[1].equals(e.getAttribute("android:text"))) {
					found = e;
				}
			}
			if (found == null) {
				problems.add(h[0] + " has no " + h[1]);
			} else if (!"true".equals(found.getAttribute(
					"android:accessibilityHeading"))) {
				problems.add(h[0] + " " + h[1] + " is not a heading");
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void aSettingsSectionTitleIsReadAsAHeading() {
		SelectionBarTest.Probe activity = Robolectric.buildActivity(
				SelectionBarTest.Probe.class).setup().get();
		ViewGroup root = (ViewGroup) LayoutInflater.from(activity).inflate(
				R.layout.fragment_settings_notifications, null, false);
		List<View> titles = new ArrayList<>();
		collectHeadings(root, titles);
		assertEquals("both section titles are headings and nothing else", 2,
				titles.size());
		for (View v : titles) {
			assertTrue(v.getClass().getName(), v instanceof TextView);
		}
	}

	private static void collectHeadings(View v, List<View> out) {
		if (ViewCompat.isAccessibilityHeading(v)) out.add(v);
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collectHeadings(g.getChildAt(i), out);
			}
		}
	}

	@Test
	public void theMessageInfoTitleIsAHeading() {
		SelectionBarTest.Probe activity = Robolectric.buildActivity(
				SelectionBarTest.Probe.class).setup().get();
		AlertDialog dialog = new MessageInfo(activity)
				.line(R.string.message_info_sent, "12:00").show();
		shadowOf(Looper.getMainLooper()).idle();
		View title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
		assertNotNull(title);
		assertEquals(activity.getString(R.string.selection_info),
				((TextView) title).getText().toString());
		assertTrue(ViewCompat.isAccessibilityHeading(title));
	}
}
