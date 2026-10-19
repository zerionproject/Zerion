package com.professor.zerion.android.settings;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LifecycleTestsReleaseTest {

	private static final String SETTINGS =
			"java/com/professor/zerion/android/settings/";
	private static final File RELEASE_CARD =
			new File("src/release/" + SETTINGS + "LifecycleTests.java");
	private static final File RELEASE_MODULE =
			new File("src/release/" + SETTINGS + "LifecycleTestsModule.java");
	private static final File DEBUG_STRINGS =
			new File("src/debug/res/values/strings.xml");

	private static final String[] HOOK_NAMES = {
			"forceCompleteRotation", "B4DebugAccess", "OnionAuthDebugAccess",
			"LifecycleClock", "LifecycleSwitches", "debug_lifecycle_tests",
			"tickForTest", "evaluateAll(", "rotateAuthorizedService(",
			"pref_force_complete_rotation", "force_complete_rotation_card",
			"skipNewNegotiations", "holdConfirmations",
			"debug-lifecycle-tests", "lifecycle_tests_container"
	};

	private static String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()),
				StandardCharsets.UTF_8);
	}

	private static void sources(File dir, List<File> out) {
		File[] files = dir.listFiles();
		if (files == null) return;
		for (File f : files) {
			if (f.isDirectory()) sources(f, out);
			else if (f.getName().endsWith(".java")
					|| f.getName().endsWith(".xml")
					|| f.getName().endsWith(".kt")) {
				out.add(f);
			}
		}
	}

	private static List<File> releaseSources() {
		List<File> shipped = new ArrayList<>();
		sources(new File("src/main"), shipped);
		sources(new File("src/release"), shipped);
		return shipped;
	}

	private static Map<String, String> debugStrings() throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		Element root = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder().parse(DEBUG_STRINGS)
				.getDocumentElement();
		NodeList nodes = root.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node n = nodes.item(i);
			if (!(n instanceof Element)) continue;
			Element e = (Element) n;
			String name = e.getAttribute("name");
			if (!name.startsWith("lifecycle_")) continue;
			assertEquals(name + " is translatable", "false",
					e.getAttribute("translatable"));
			if (e.getTagName().equals("string")) {
				out.put(name, e.getTextContent());
			} else {
				out.put(name, null);
			}
		}
		return out;
	}

	@Test
	public void releaseSourcesHoldNoLifecycleHook() throws IOException {
		List<File> shipped = releaseSources();
		assertTrue(shipped.size() > 100);
		List<String> offenders = new ArrayList<>();
		for (File f : shipped) {
			String text = read(f);
			for (String name : HOOK_NAMES) {
				if (text.contains(name)) {
					offenders.add(f.getPath() + " names " + name);
				}
			}
		}
		assertTrue("lifecycle test hooks in release sources: " + offenders,
				offenders.isEmpty());
	}

	@Test
	public void theReleaseStubsDoNothing() throws IOException {
		assertTrue(RELEASE_CARD.getAbsolutePath(), RELEASE_CARD.isFile());
		assertTrue(RELEASE_MODULE.getAbsolutePath(), RELEASE_MODULE.isFile());
		String card = read(RELEASE_CARD);
		assertFalse(card.contains("@Inject"));
		assertFalse(card.contains("R."));
		assertTrue(card.contains(
				"public static void attach(Fragment fragment, View root) {\n"
						+ "\t}"));
		String module = read(RELEASE_MODULE);
		assertTrue(module.contains("return OnionAuthDebugHooks.NONE;"));
		assertFalse(module.contains("SettingsManager"));
		assertFalse(module.contains("new "));
	}

	@Test
	public void noDebugStringReachesReleaseResources() throws Exception {
		Map<String, String> strings = debugStrings();
		assertTrue(strings.size() > 20);
		File[] debugValues = new File("src/debug/res").listFiles(
				(d, n) -> n.startsWith("values-"));
		assertTrue("debug strings are English only",
				debugValues == null || debugValues.length == 0);
		List<String> offenders = new ArrayList<>();
		for (File f : releaseSources()) {
			String text = read(f);
			for (Map.Entry<String, String> e : strings.entrySet()) {
				if (text.contains(e.getKey())) {
					offenders.add(f.getPath() + " names " + e.getKey());
				}
				String english = e.getValue();
				if (english != null && english.length() > 12
						&& f.getPath().contains("res")
						&& text.contains(">" + english + "<")) {
					offenders.add(f.getPath() + " says " + english);
				}
			}
		}
		assertTrue("debug strings in release resources: " + offenders,
				offenders.isEmpty());
	}
}
