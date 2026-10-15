package com.professor.zerion.android;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AccentContrastTest {

	private static final File RES = new File("src/main/res");
	private static final double MIN_TEXT_CONTRAST = 4.5;
	private static final String ACCENT = "@color/zerion_primary_accent";
	private static final String ON_ACCENT = "@color/zerion_on_accent";
	private static final String[] FOREGROUND_ATTRS = {"android:textColor",
			"app:tint", "android:tint", "app:iconTint"};

	private static final class Palette {
		final String name;
		final Map<String, String> colors = new HashMap<>();
		final Map<String, String> theme;

		Palette(String name, Map<String, String> theme) {
			this.name = name;
			this.theme = theme;
		}
	}

	private static Element parse(File f) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(f).getDocumentElement();
	}

	private static List<Element> children(Element parent, String tag) {
		List<Element> out = new ArrayList<>();
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node n = nodes.item(i);
			if (n instanceof Element && (tag == null
					|| ((Element) n).getTagName().equals(tag))) {
				out.add((Element) n);
			}
		}
		return out;
	}

	private static void loadColors(File folder, Map<String, String> out)
			throws Exception {
		File[] files = folder.listFiles((d, n) -> n.endsWith(".xml"));
		if (files == null) return;
		for (File f : files) {
			for (Element e : children(parse(f), "color")) {
				out.put(e.getAttribute("name"), e.getTextContent().trim());
			}
		}
	}

	private static Map<String, Map<String, String>> styles() throws Exception {
		Map<String, Map<String, String>> raw = new LinkedHashMap<>();
		Map<String, String> parents = new HashMap<>();
		File[] files = new File(RES, "values")
				.listFiles((d, n) -> n.endsWith(".xml"));
		assertNotNull(files);
		for (File f : files) {
			for (Element s : children(parse(f), "style")) {
				String name = s.getAttribute("name");
				Map<String, String> items = new LinkedHashMap<>();
				for (Element item : children(s, "item")) {
					items.put(item.getAttribute("name"),
							item.getTextContent().trim());
				}
				raw.put(name, items);
				String parent = s.getAttribute("parent");
				if (parent.startsWith("@style/")) parent = parent.substring(7);
				if (parent.isEmpty() && name.contains(".")
						&& !s.hasAttribute("parent")) {
					parent = name.substring(0, name.lastIndexOf('.'));
				}
				parents.put(name, parent);
			}
		}
		Map<String, Map<String, String>> resolved = new HashMap<>();
		for (String name : raw.keySet()) {
			List<String> chain = new ArrayList<>();
			String cur = name;
			while (cur != null && raw.containsKey(cur)
					&& !chain.contains(cur)) {
				chain.add(0, cur);
				cur = parents.get(cur);
			}
			Map<String, String> merged = new HashMap<>();
			for (String link : chain) merged.putAll(raw.get(link));
			resolved.put(name, merged);
		}
		return resolved;
	}

	private static List<Palette> palettes() throws Exception {
		Map<String, Map<String, String>> styles = styles();
		Map<String, String> theme = styles.get("ZerionTheme");
		assertNotNull(theme);
		Palette day = new Palette("day", theme);
		loadColors(new File(RES, "values"), day.colors);
		Palette night = new Palette("night", theme);
		night.colors.putAll(day.colors);
		loadColors(new File(RES, "values-night"), night.colors);
		List<Palette> out = new ArrayList<>();
		out.add(day);
		out.add(night);
		return out;
	}

	private static Integer color(String ref, Palette p) {
		for (int depth = 0; ref != null && depth < 20; depth++) {
			ref = ref.trim();
			if (ref.startsWith("#")) return parseHex(ref);
			if (ref.equals("@android:color/white")) return 0xFFFFFFFF;
			if (ref.equals("@android:color/black")) return 0xFF000000;
			if (ref.equals("@android:color/transparent")) return 0;
			if (ref.startsWith("@color/")) {
				String name = ref.substring(7);
				if (p.colors.containsKey(name)) {
					ref = p.colors.get(name);
					continue;
				}
				File list = new File(new File(RES, "color"), name + ".xml");
				return list.isFile() ? defaultStateColor(list, p) : null;
			}
			if (ref.startsWith("?")) {
				String attr = ref.substring(1).replace("attr/", "");
				ref = p.theme.get(attr);
				continue;
			}
			return null;
		}
		return null;
	}

	private static Integer defaultStateColor(File list, Palette p) {
		try {
			for (Element item : children(parse(list), "item")) {
				boolean stateful = false;
				for (int i = 0; i < item.getAttributes().getLength(); i++) {
					if (item.getAttributes().item(i).getNodeName()
							.startsWith("android:state_")) {
						stateful = true;
					}
				}
				if (stateful) continue;
				Integer c = color(item.getAttribute("android:color"), p);
				if (c == null) return null;
				if (item.hasAttribute("android:alpha")) {
					int a = (int) Math.round(((c >>> 24) & 0xFF) * Double
							.parseDouble(item.getAttribute("android:alpha")));
					c = (a << 24) | (c & 0xFFFFFF);
				}
				return c;
			}
			return null;
		} catch (Exception e) {
			throw new AssertionError(list.getName(), e);
		}
	}

	private static int parseHex(String hex) {
		String h = hex.substring(1);
		if (h.length() == 3 || h.length() == 4) {
			StringBuilder sb = new StringBuilder();
			for (char ch : h.toCharArray()) sb.append(ch).append(ch);
			h = sb.toString();
		}
		long v = Long.parseLong(h, 16);
		if (h.length() == 6) v |= 0xFF000000L;
		return (int) v;
	}

	private static double channel(int c) {
		double s = c / 255.0;
		return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
	}

	private static double luminance(int rgb) {
		return 0.2126 * channel((rgb >> 16) & 0xFF)
				+ 0.7152 * channel((rgb >> 8) & 0xFF)
				+ 0.0722 * channel(rgb & 0xFF);
	}

	private static int over(int top, int bottom) {
		double a = ((top >>> 24) & 0xFF) / 255.0;
		int r = (int) Math.round(((top >> 16) & 0xFF) * a
				+ ((bottom >> 16) & 0xFF) * (1 - a));
		int g = (int) Math.round(((top >> 8) & 0xFF) * a
				+ ((bottom >> 8) & 0xFF) * (1 - a));
		int b = (int) Math.round((top & 0xFF) * a + (bottom & 0xFF) * (1 - a));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static double ratio(int a, int b) {
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double contrast(int foreground, int background) {
		double worst = Double.MAX_VALUE;
		for (int backdrop : new int[] {0xFF000000, 0xFFFFFFFF}) {
			int bg = over(background, backdrop);
			worst = Math.min(worst, ratio(over(foreground, bg), bg));
		}
		return worst;
	}

	private static boolean isAccent(Integer fill, Palette p) {
		Integer accent = color(ACCENT, p);
		assertNotNull(accent);
		return fill != null && ((fill >>> 24) & 0xFF) >= 0x80
				&& (fill & 0xFFFFFF) == (accent & 0xFFFFFF);
	}

	private static void check(List<String> problems, String where,
			String fgRef, String bgRef, Palette p) {
		Integer fg = color(fgRef, p);
		Integer bg = color(bgRef, p);
		if (fg == null || bg == null) {
			problems.add(where + " (" + p.name + ") cannot resolve "
					+ fgRef + " on " + bgRef);
			return;
		}
		double r = contrast(fg, bg);
		if (r < MIN_TEXT_CONTRAST) {
			problems.add(String.format(java.util.Locale.ROOT,
					"%s (%s) %s on %s is %.2f:1", where, p.name, fgRef,
					bgRef, r));
		}
	}

	@Test
	public void onAccentTokenIsReadableOnTheAccent() throws Exception {
		List<String> problems = new ArrayList<>();
		for (Palette p : palettes()) {
			for (String bg : new String[] {ACCENT, "@color/zerion_cyan",
					"@color/zerion_accent", "@color/msg_selected_background"}) {
				check(problems, "token", ON_ACCENT, bg, p);
			}
			check(problems, "token", "@color/md_theme_onPrimary",
					"@color/md_theme_primary", p);
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void themeOnPrimaryIsReadableForEveryAccent() throws Exception {
		Map<String, Map<String, String>> styles = styles();
		List<String> problems = new ArrayList<>();
		int overlays = 0;
		for (Palette p : palettes()) {
			check(problems, "ZerionTheme", "?attr/colorOnPrimary",
					"?attr/colorPrimary", p);
			for (Map.Entry<String, Map<String, String>> s :
					styles.entrySet()) {
				if (!s.getKey().startsWith("AccentOverlay.")) continue;
				overlays++;
				Map<String, String> merged = new HashMap<>(p.theme);
				merged.putAll(s.getValue());
				Palette overlay = new Palette(p.name, merged);
				overlay.colors.putAll(p.colors);
				check(problems, s.getKey(), "?attr/colorOnPrimary",
						"?attr/colorPrimary", overlay);
			}
		}
		assertTrue(overlays >= 10);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void filledStylesUseTheOnAccentLabel() throws Exception {
		Map<String, Map<String, String>> styles = styles();
		List<String> problems = new ArrayList<>();
		for (Palette p : palettes()) {
			check(problems, "filled button",
					"@color/zerion_button_filled_foreground",
					"@color/zerion_button_filled_background", p);
			for (String name : new String[] {"Zerion.Button.Primary",
					"Zerion.Button.Filled"}) {
				Map<String, String> s = styles.get(name);
				assertNotNull(name, s);
				check(problems, name, s.get("android:textColor"),
						s.get("backgroundTint"), p);
				check(problems, name, s.get("iconTint"),
						s.get("backgroundTint"), p);
			}
			Map<String, String> fab = styles.get("Zerion.FAB.Small");
			assertNotNull(fab);
			check(problems, "Zerion.FAB.Small", fab.get("tint"),
					fab.get("backgroundTint"), p);
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	private static Integer fill(Map<String, String> attrs, Palette p) {
		String tint = attrs.get("app:backgroundTint");
		if (tint == null) tint = attrs.get("android:backgroundTint");
		if (tint != null) return color(tint, p);
		String bg = attrs.get("android:background");
		if (bg == null) return null;
		if (bg.startsWith("@drawable/")) {
			String name = bg.substring(10);
			for (String folder : p.name.equals("night")
					? new String[] {"drawable-night", "drawable"}
					: new String[] {"drawable"}) {
				File f = new File(new File(RES, folder), name + ".xml");
				if (!f.isFile()) continue;
				try {
					Element root = parse(f);
					if (!root.getTagName().equals("shape")) return null;
					List<Element> solid = children(root, "solid");
					if (solid.isEmpty()) return null;
					return color(solid.get(0).getAttribute("android:color"),
							p);
				} catch (Exception e) {
					throw new AssertionError(f.getName(), e);
				}
			}
			return null;
		}
		return color(bg, p);
	}

	private static Map<String, String> attrs(Element e,
			Map<String, Map<String, String>> styles) {
		Map<String, String> out = new HashMap<>();
		String style = e.getAttribute("style");
		if (style.startsWith("@style/")) {
			Map<String, String> s = styles.get(style.substring(7));
			if (s != null) {
				for (Map.Entry<String, String> item : s.entrySet()) {
					String key = item.getKey();
					out.put(key.contains(":") ? key : "app:" + key,
							item.getValue());
				}
			}
		}
		for (int i = 0; i < e.getAttributes().getLength(); i++) {
			Node a = e.getAttributes().item(i);
			out.put(a.getNodeName(), a.getNodeValue());
		}
		return out;
	}

	@Test
	public void contentOnAccentFillsIsReadable() throws Exception {
		Map<String, Map<String, String>> styles = styles();
		List<String> problems = new ArrayList<>();
		int checked = 0;
		File[] layouts = new File(RES, "layout")
				.listFiles((d, n) -> n.endsWith(".xml"));
		assertNotNull(layouts);
		for (Palette p : palettes()) {
			for (File f : layouts) {
				Element root = parse(f);
				NodeList all = root.getElementsByTagName("*");
				List<Element> elements = new ArrayList<>();
				elements.add(root);
				for (int i = 0; i < all.getLength(); i++) {
					elements.add((Element) all.item(i));
				}
				for (Element e : elements) {
					Map<String, String> a = attrs(e, styles);
					Integer bg = fill(a, p);
					if (!isAccent(bg, p)) continue;
					List<Map<String, String>> targets = new ArrayList<>();
					targets.add(a);
					for (Element child : children(e, null)) {
						Map<String, String> ca = attrs(child, styles);
						if (fill(ca, p) == null) targets.add(ca);
					}
					for (Map<String, String> t : targets) {
						for (String key : FOREGROUND_ATTRS) {
							String fg = t.get(key);
							if (fg == null) continue;
							checked++;
							Integer fc = color(fg, p);
							if (fc == null) {
								problems.add(f.getName() + " " + key
										+ " cannot resolve " + fg);
								continue;
							}
							double r = contrast(fc, bg);
							if (r < MIN_TEXT_CONTRAST) {
								problems.add(String.format(
										java.util.Locale.ROOT,
										"%s %s (%s) %s is %.2f:1 on the accent",
										f.getName(), key, p.name, fg, r));
							}
						}
					}
				}
			}
		}
		assertTrue("checked " + checked, checked >= 60);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}
}
