package com.professor.zerion.android;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TaskAndExportManifestTest {

	private static final String ANDROID =
			"http://schemas.android.com/apk/res/android";
	private static final String TOOLS =
			"http://schemas.android.com/tools";
	private static final String PKG = "com.professor.zerion";

	private static Document manifest() throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setNamespaceAware(true);
		return f.newDocumentBuilder().parse(
				new File("src/main/AndroidManifest.xml"));
	}

	private static String name(Element e) {
		String n = e.getAttributeNS(ANDROID, "name");
		return n.startsWith(".") ? PKG + n : n;
	}

	private static List<Element> elements(Document d, String tag) {
		List<Element> out = new ArrayList<>();
		NodeList list = d.getElementsByTagName(tag);
		for (int i = 0; i < list.getLength(); i++) {
			out.add((Element) list.item(i));
		}
		return out;
	}

	@Test
	public void noScreenHasATaskAffinityAnotherAppCouldClaim()
			throws Exception {
		Document d = manifest();
		Element app = elements(d, "application").get(0);
		assertTrue("the application must declare an empty task affinity",
				app.hasAttributeNS(ANDROID, "taskAffinity"));
		assertEquals("", app.getAttributeNS(ANDROID, "taskAffinity"));
		for (Element a : elements(d, "activity")) {
			if (a.hasAttributeNS(ANDROID, "taskAffinity")) {
				assertEquals(name(a) + " has a task affinity", "",
						a.getAttributeNS(ANDROID, "taskAffinity"));
			}
			assertTrue(name(a) + " may move into another task",
					!"true".equals(a.getAttributeNS(ANDROID,
							"allowTaskReparenting")));
		}
	}

	@Test
	public void otherAppsReachOnlyTheIntendedEntries() throws Exception {
		Document d = manifest();
		Set<String> exported = new HashSet<>();
		for (String tag : Arrays.asList("activity", "service", "receiver",
				"provider")) {
			for (Element e : elements(d, tag)) {
				if ("true".equals(e.getAttributeNS(ANDROID, "exported"))) {
					exported.add(name(e));
				}
			}
		}
		Set<String> expected = new HashSet<>(Arrays.asList(
				PKG + ".android.splash.SplashScreenActivity",
				PKG + ".android.navdrawer.ExternalLinkActivity",
				PKG + ".android.panic.PanicResponderActivity"));
		assertEquals(expected, exported);
	}

	@Test
	public void theLinkEntryHasNoWindowAndNoHistory() throws Exception {
		Document d = manifest();
		Element link = null;
		for (Element a : elements(d, "activity")) {
			if (name(a).endsWith(".navdrawer.ExternalLinkActivity")) link = a;
		}
		assertTrue("there is one entry for links", link != null);
		assertEquals("true", link.getAttributeNS(ANDROID, "noHistory"));
		assertEquals("true", link.getAttributeNS(ANDROID,
				"excludeFromRecents"));
		assertTrue(link.getAttributeNS(ANDROID, "theme")
				.contains("NoDisplay"));
		assertTrue("it must not be single-task",
				!link.hasAttributeNS(ANDROID, "launchMode"));
	}

	@Test
	public void theLauncherEntryLeavesNoEmptyTaskInTheRecentApps()
			throws Exception {
		for (Element a : elements(manifest(), "activity")) {
			if (name(a).endsWith(".splash.SplashScreenActivity")) {
				assertEquals("true", a.getAttributeNS(ANDROID,
						"excludeFromRecents"));
				assertEquals("true", a.getAttributeNS(ANDROID, "noHistory"));
				return;
			}
		}
		throw new AssertionError("no launcher entry");
	}

	@Test
	public void theCallScreenStaysOutOfTheRecentApps() throws Exception {
		for (Element a : elements(manifest(), "activity")) {
			if (name(a).endsWith(".voice.VoiceCallActivity")) {
				assertEquals("true", a.getAttributeNS(ANDROID,
						"excludeFromRecents"));
				return;
			}
		}
		throw new AssertionError("no call screen");
	}

	@Test
	public void theEmojiFontLoaderIsNotMergedIn() throws Exception {
		for (Element m : elements(manifest(), "meta-data")) {
			if ("androidx.emoji2.text.EmojiCompatInitializer".equals(
					m.getAttributeNS(ANDROID, "name"))) {
				assertEquals("remove", m.getAttributeNS(TOOLS, "node"));
				return;
			}
		}
		throw new AssertionError("the emoji font loader is merged in");
	}

	@Test
	public void theWebViewSendsNoMetrics() throws Exception {
		for (Element m : elements(manifest(), "meta-data")) {
			if ("android.webkit.WebView.MetricsOptOut".equals(
					m.getAttributeNS(ANDROID, "name"))) {
				assertEquals("true", m.getAttributeNS(ANDROID, "value"));
				return;
			}
		}
		throw new AssertionError("WebView metrics are not opted out of");
	}
}
