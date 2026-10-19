package com.professor.zerion.android;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThemeLeftoversTest {

	private static final File LAYOUTS = new File("src/main/res/layout");
	private static final String TOGGLE_TEXT = "@color/zerion_toggle_text";
	private static final String TOGGLE_GROUP =
			"com.google.android.material.button.MaterialButtonToggleGroup";

	private static boolean insideToggleGroup(Node n) {
		for (Node p = n.getParentNode(); p != null; p = p.getParentNode()) {
			if (TOGGLE_GROUP.equals(p.getNodeName())) return true;
		}
		return false;
	}

	@Test
	public void toggleLabelColorsAreOnlyUsedForToggles() throws Exception {
		File[] files = LAYOUTS.listFiles();
		assertTrue(files != null && files.length > 100);
		List<String> problems = new ArrayList<>();
		for (File f : files) {
			String s = new String(Files.readAllBytes(f.toPath()),
					StandardCharsets.UTF_8);
			if (!s.contains(TOGGLE_TEXT)) continue;
			NodeList all = DocumentBuilderFactory.newInstance()
					.newDocumentBuilder().parse(f)
					.getElementsByTagName("*");
			for (int i = 0; i < all.getLength(); i++) {
				Element e = (Element) all.item(i);
				if (TOGGLE_TEXT.equals(e.getAttribute("android:textColor"))
						&& !insideToggleGroup(e)) {
					problems.add(f.getName() + ": " + e.getAttribute("android:id"));
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void noScreenPaintsAFixedBackgroundOrCard() throws Exception {
		File[] files = LAYOUTS.listFiles();
		assertTrue(files != null && files.length > 100);
		List<String> problems = new ArrayList<>();
		for (File f : files) {
			String s = new String(Files.readAllBytes(f.toPath()),
					StandardCharsets.UTF_8);
			for (String fixed : new String[] {
					"android:background=\"@color/zerion_dark_bg\"",
					"android:background=\"@color/zerion_card_bg\"",
					"android:background=\"@color/zerion_surface_elevated\""}) {
				if (s.contains(fixed)) problems.add(f.getName() + ": " + fixed);
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void connectionStatusCardsFollowTheTheme() throws Exception {
		String s = new String(Files.readAllBytes(
				new File(LAYOUTS, "fragment_tor_status.xml").toPath()),
				StandardCharsets.UTF_8);
		assertFalse(s.contains("@color/zerion_surface_elevated"));
		assertTrue(s.contains("?attr/zerionColorSurfaceVariant"));
	}
}
