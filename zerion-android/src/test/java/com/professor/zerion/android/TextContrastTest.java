package com.professor.zerion.android;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TextContrastTest {

	private static final File RES = new File("src/main/res");
	private static final File JAVA = new File("src/main/java");
	private static final double TEXT = 4.5;
	private static final double LARGE_TEXT = 3.0;
	private static final double GRAPHIC = 3.0;
	private static final int[] DAY_SCREENS = {0xFFF4F5F7, 0xFFFFFFFF};
	private static final int[] NIGHT_SCREENS = {0xFF0F1014, 0xFF1C1D22,
			0xFF000000};
	private static final int[] DAY_SURFACES = {0xFFF4F5F7, 0xFFFFFFFF,
			0xFFECEEF2};
	private static final int[] NIGHT_SURFACES = {0xFF0F1014, 0xFF1C1D22,
			0xFF252631, 0xFF2E3039, 0xFF000000};
	private static final List<String> OUT_BUBBLES = Arrays.asList(
			"@drawable/msg_out", "@drawable/msg_out_top",
			"@drawable/notice_out_bottom");
	private static final List<String> BUBBLE_COLORS = Arrays.asList(
			"@color/bubble_blue", "@color/bubble_purple", "@color/bubble_green",
			"@color/bubble_orange", "@color/bubble_pink", "@color/bubble_cyan");
	private static final Map<String, String> INFLATED_INTO =
			Collections.singletonMap("list_item_channel_attachment.xml",
					"@drawable/msg_in");
	private static final Set<String> OVER_IMAGES = new HashSet<>(Arrays.asList(
			"item_gallery_image.xml#@+id/video_icon",
			"text_avatar_view.xml#@+id/textAvatarView"));
	private static final List<String> TEXT_TAGS = Arrays.asList("TextView",
			"EmojiTextView", "AppCompatTextView", "MaterialTextView", "Button",
			"MaterialButton", "EditText", "EmojiEditText", "TextInputEditText",
			"CheckBox", "MaterialCheckBox", "SwitchMaterial", "MaterialSwitch",
			"RadioButton", "Chip");
	private static final List<String> BUTTON_TAGS = Arrays.asList("Button",
			"MaterialButton", "AppCompatButton");

	private static final class Mode {
		final String name;
		final boolean night;
		final Map<String, String> colors;
		final Map<String, String> theme;
		final int[] screens;

		Mode(String name, boolean night, Map<String, String> colors,
				Map<String, String> theme, int[] screens) {
			this.name = name;
			this.night = night;
			this.colors = colors;
			this.theme = theme;
			this.screens = screens;
		}
	}

	private static Element parse(File f) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(f).getDocumentElement();
	}

	private static List<Element> children(Element parent) {
		List<Element> out = new ArrayList<>();
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i) instanceof Element) {
				out.add((Element) nodes.item(i));
			}
		}
		return out;
	}

	private static Map<String, String> colors(String... folders)
			throws Exception {
		Map<String, String> out = new HashMap<>();
		for (String folder : folders) {
			File[] files = new File(RES, folder)
					.listFiles((d, n) -> n.endsWith(".xml"));
			if (files == null) continue;
			for (File f : files) {
				for (Element e : children(parse(f))) {
					if (e.getTagName().equals("color")) {
						out.put(e.getAttribute("name"),
								e.getTextContent().trim());
					}
				}
			}
		}
		return out;
	}

	private static Map<String, Map<String, String>> styles() throws Exception {
		Map<String, Map<String, String>> raw = new LinkedHashMap<>();
		Map<String, String> parents = new HashMap<>();
		File[] files = new File(RES, "values")
				.listFiles((d, n) -> n.endsWith(".xml"));
		assertNotNull(files);
		for (File f : files) {
			for (Element s : children(parse(f))) {
				if (!s.getTagName().equals("style")) continue;
				String name = s.getAttribute("name");
				Map<String, String> items = new LinkedHashMap<>();
				for (Element item : children(s)) {
					items.put(item.getAttribute("name"),
							item.getTextContent().trim());
				}
				raw.put(name, items);
				String parent = s.getAttribute("parent");
				if (parent.startsWith("@style/")) parent = parent.substring(7);
				if (!s.hasAttribute("parent") && name.contains(".")) {
					parent = name.substring(0, name.lastIndexOf('.'));
				}
				parents.put(name, parent);
			}
		}
		Map<String, Map<String, String>> out = new HashMap<>();
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
			out.put(name, merged);
		}
		return out;
	}

	private static List<Mode> modes(Map<String, Map<String, String>> styles)
			throws Exception {
		Map<String, String> day = colors("values");
		Map<String, String> night = colors("values", "values-night");
		Map<String, String> base = styles.get("ZerionTheme");
		assertNotNull(base);
		List<Mode> out = new ArrayList<>();
		for (boolean isNight : new boolean[] {false, true}) {
			out.add(new Mode(isNight ? "night" : "day", isNight,
					isNight ? night : day, base,
					isNight ? NIGHT_SCREENS : DAY_SCREENS));
			for (Map.Entry<String, Map<String, String>> s : styles.entrySet()) {
				if (!s.getKey().startsWith("AccentOverlay.")) continue;
				Map<String, String> theme = new HashMap<>(base);
				theme.putAll(s.getValue());
				out.add(new Mode((isNight ? "night " : "day ") + s.getKey(),
						isNight, isNight ? night : day, theme,
						isNight ? NIGHT_SCREENS : DAY_SCREENS));
			}
		}
		return out;
	}

	private static Integer color(String ref, Mode m) {
		for (int depth = 0; ref != null && depth < 20; depth++) {
			ref = ref.trim();
			if (ref.startsWith("#")) return hex(ref);
			if (ref.equals("@android:color/white")) return 0xFFFFFFFF;
			if (ref.equals("@android:color/black")) return 0xFF000000;
			if (ref.equals("@android:color/transparent")
					|| ref.equals("@null")) {
				return 0;
			}
			if (ref.startsWith("@color/")) {
				String name = ref.substring(7);
				if (m.colors.containsKey(name)) {
					ref = m.colors.get(name);
					continue;
				}
				File list = new File(new File(RES, "color"), name + ".xml");
				return list.isFile() ? defaultState(list, m) : null;
			}
			if (ref.startsWith("?")) {
				ref = m.theme.get(ref.substring(1).replace("attr/", ""));
				continue;
			}
			return null;
		}
		return null;
	}

	private static Integer defaultState(File list, Mode m) {
		try {
			for (Element item : children(parse(list))) {
				NamedNodeMap a = item.getAttributes();
				boolean stateful = false;
				for (int i = 0; i < a.getLength(); i++) {
					if (a.item(i).getNodeName().startsWith("android:state_")) {
						stateful = true;
					}
				}
				if (stateful) continue;
				Integer c = color(item.getAttribute("android:color"), m);
				if (c != null && item.hasAttribute("android:alpha")) {
					int alpha = (int) Math.round(((c >>> 24) & 0xFF)
							* Double.parseDouble(
							item.getAttribute("android:alpha")));
					c = (alpha << 24) | (c & 0xFFFFFF);
				}
				return c;
			}
			return null;
		} catch (Exception e) {
			throw new AssertionError(list.getName(), e);
		}
	}

	private static int hex(String s) {
		String h = s.substring(1);
		long v = Long.parseLong(h, 16);
		if (h.length() == 6) v |= 0xFF000000L;
		return (int) v;
	}

	private static double channel(int c) {
		double s = c / 255.0;
		return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
	}

	private static double luminance(int c) {
		return 0.2126 * channel((c >> 16) & 0xFF)
				+ 0.7152 * channel((c >> 8) & 0xFF)
				+ 0.0722 * channel(c & 0xFF);
	}

	private static int over(int top, int bottom) {
		double a = ((top >>> 24) & 0xFF) / 255.0;
		int out = 0xFF000000;
		for (int shift : new int[] {16, 8, 0}) {
			int v = (int) Math.round(((top >> shift) & 0xFF) * a
					+ ((bottom >> shift) & 0xFF) * (1 - a));
			out |= v << shift;
		}
		return out;
	}

	private static double ratio(int a, int b) {
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double worst(int fg, List<Integer> backdrops) {
		double w = Double.MAX_VALUE;
		for (int b : backdrops) w = Math.min(w, ratio(over(fg, b), b));
		return w;
	}

	private static List<Integer> layer(List<Integer> fills,
			List<Integer> backdrops) {
		List<Integer> out = new ArrayList<>();
		for (int f : fills) {
			for (int b : backdrops) out.add(over(f, b));
		}
		return out;
	}

	private static Integer shapeFill(String name, Mode m) {
		for (String folder : m.night ? new String[] {"drawable-night",
				"drawable"} : new String[] {"drawable"}) {
			File f = new File(new File(RES, folder), name + ".xml");
			if (!f.isFile()) continue;
			try {
				Element root = parse(f);
				if (!root.getTagName().equals("shape")) return null;
				for (Element c : children(root)) {
					if (c.getTagName().equals("solid")) {
						return color(c.getAttribute("android:color"), m);
					}
				}
				return null;
			} catch (Exception e) {
				throw new AssertionError(f.getName(), e);
			}
		}
		return null;
	}

	private static List<Integer> fills(Map<String, String> a, Mode m) {
		for (String key : new String[] {"app:backgroundTint",
				"android:backgroundTint", "app:cardBackgroundColor"}) {
			if (a.containsKey(key)) {
				Integer c = color(a.get(key), m);
				return c == null ? null : Arrays.asList(c);
			}
		}
		String bg = a.get("android:background");
		if (bg == null) return null;
		if (OUT_BUBBLES.contains(bg)) {
			List<Integer> out = new ArrayList<>();
			out.add(shapeFill(bg.substring(10), m));
			for (String b : BUBBLE_COLORS) out.add(color(b, m));
			return out;
		}
		Integer c = bg.startsWith("@drawable/")
				? shapeFill(bg.substring(10), m) : color(bg, m);
		return c == null ? null : Arrays.asList(c);
	}

	private static Map<String, String> attrs(Element e,
			Map<String, Map<String, String>> styles) {
		Map<String, String> out = new HashMap<>();
		String style = e.getAttribute("style");
		if (style.startsWith("@style/")
				&& styles.containsKey(style.substring(7))) {
			for (Map.Entry<String, String> i :
					styles.get(style.substring(7)).entrySet()) {
				out.put(i.getKey().contains(":") ? i.getKey()
						: "app:" + i.getKey(), i.getValue());
			}
		}
		NamedNodeMap a = e.getAttributes();
		for (int i = 0; i < a.getLength(); i++) {
			out.put(a.item(i).getNodeName(), a.item(i).getNodeValue());
		}
		return out;
	}

	private static double textSize(Map<String, String> a,
			Map<String, String> dimens) {
		String v = a.getOrDefault("android:textSize", "");
		if (v.startsWith("@dimen/")) {
			v = dimens.getOrDefault(v.substring(7), "14sp");
		}
		Matcher m = Pattern.compile("([\\d.]+)sp").matcher(v);
		return m.matches() ? Double.parseDouble(m.group(1)) : 14.0;
	}

	private static void inline(Element e) throws Exception {
		for (Element c : children(e)) {
			if (!c.getTagName().equals("include")) {
				inline(c);
				continue;
			}
			String layout = c.getAttribute("layout");
			File f = new File(new File(RES, "layout"),
					layout.replace("@layout/", "") + ".xml");
			if (!layout.startsWith("@layout/") || !f.isFile()) continue;
			Document doc = e.getOwnerDocument();
			Element sub = (Element) doc.importNode(parse(f), true);
			inline(sub);
			if (sub.getTagName().equals("merge")) {
				for (Element s : children(sub)) e.insertBefore(s, c);
				e.removeChild(c);
			} else {
				e.replaceChild(sub, c);
			}
		}
	}

	private static Set<String> includeOnly() throws Exception {
		Set<String> included = new TreeSet<>();
		File[] layouts = new File(RES, "layout").listFiles();
		assertNotNull(layouts);
		Pattern p = Pattern.compile("layout=\"@layout/([a-z0-9_]+)\"");
		for (File f : layouts) {
			Matcher m = p.matcher(new String(Files.readAllBytes(f.toPath()),
					StandardCharsets.UTF_8));
			while (m.find()) included.add(m.group(1));
		}
		StringBuilder java = new StringBuilder();
		List<File> stack = new ArrayList<>(Arrays.asList(JAVA));
		while (!stack.isEmpty()) {
			File f = stack.remove(stack.size() - 1);
			File[] list = f.listFiles();
			if (list != null) {
				stack.addAll(Arrays.asList(list));
			} else if (f.getName().endsWith(".java")
					|| f.getName().endsWith(".kt")) {
				java.append(new String(Files.readAllBytes(f.toPath()),
						StandardCharsets.UTF_8));
			}
		}
		Set<String> out = new HashSet<>();
		for (String name : included) {
			if (!java.toString().contains("R.layout." + name)) {
				out.add(name + ".xml");
			}
		}
		return out;
	}

	private static final class Scan {
		final List<String> problems = new ArrayList<>();
		final Map<String, Map<String, String>> styles;
		final Map<String, String> dimens;
		int checked = 0;

		Scan(Map<String, Map<String, String>> styles,
				Map<String, String> dimens) {
			this.styles = styles;
			this.dimens = dimens;
		}

		void check(String where, String ref, Mode m, List<Integer> bgs,
				double need) {
			Integer fg = color(ref, m);
			if (fg == null || ((fg >>> 24) & 0xFF) == 0) return;
			checked++;
			double r = worst(fg, bgs);
			if (r < need) {
				problems.add(String.format(Locale.ROOT,
						"%s (%s) %s is %.2f:1, needs %.1f:1", where, m.name,
						ref, r, need));
			}
		}

		void walk(String file, Element e, Mode m, List<Integer> bgs) {
			Map<String, String> a = attrs(e, styles);
			String id = a.getOrDefault("android:id", e.getTagName());
			if ("gone".equals(a.get("android:visibility"))
					&& "0dp".equals(a.get("android:layout_width"))
					&& "0dp".equals(a.get("android:layout_height"))) {
				return;
			}
			if (OVER_IMAGES.contains(file + "#" + id)) return;
			List<Integer> f = fills(a, m);
			boolean hasFill = false;
			if (f != null && !f.contains(null) && !f.isEmpty()
					&& ((f.get(0) >>> 24) & 0xFF) > 0) {
				bgs = layer(f, bgs);
				hasFill = true;
			}
			String tag = e.getTagName().substring(
					e.getTagName().lastIndexOf('.') + 1);
			String where = file + " " + id;
			boolean iconOnlyButton = BUTTON_TAGS.contains(tag)
					&& !a.containsKey("android:text")
					&& !a.containsKey("app:text")
					&& !a.containsKey("tools:text");
			boolean text = !iconOnlyButton && (TEXT_TAGS.contains(tag)
					|| tag.endsWith("TextView") || tag.endsWith("EditText")
					|| (tag.endsWith("Button") && !tag.contains("Image")
					&& !tag.contains("Floating")));
			if (text) {
				String fg = a.get("android:textColor");
				String style = a.getOrDefault("style", "");
				List<Integer> textBgs = bgs;
				if (fg == null && BUTTON_TAGS.contains(tag)) {
					if (style.contains("Text") || style.contains("Outline")
							|| style.contains("Icon")
							|| style.contains("Flat")) {
						fg = "?attr/colorPrimary";
					} else {
						fg = "?attr/colorOnPrimary";
						if (!hasFill) {
							textBgs = layer(Arrays.asList(
									color("?attr/colorPrimary", m)), bgs);
						}
					}
				}
				if (fg == null) fg = "@color/zerion_text_primary";
				double size = textSize(a, dimens);
				boolean bold = a.getOrDefault("android:textStyle", "")
						.contains("bold");
				boolean large = size >= 24 || (bold && size >= 18.66);
				check(where, fg, m, textBgs, large ? LARGE_TEXT : TEXT);
				for (String k : new String[] {"app:hintTextColor",
						"android:textColorLink", "app:tabSelectedTextColor",
						"android:textColorHint"}) {
					if (a.containsKey(k)) {
						check(where + " " + k, a.get(k), m, bgs, TEXT);
					}
				}
			}
			for (String k : new String[] {"app:tint", "android:tint",
					"app:iconTint", "app:endIconTint", "app:startIconTint",
					"app:navigationIconTint", "android:buttonTint",
					"app:buttonTint"}) {
				if (!a.containsKey(k)) continue;
				if (tag.contains("FloatingActionButton") && !hasFill) continue;
				check(where + " " + k, a.get(k), m, bgs, GRAPHIC);
			}
			for (Element c : children(e)) walk(file, c, m, bgs);
		}
	}

	private static List<Integer> list(int[] values) {
		List<Integer> out = new ArrayList<>();
		for (int v : values) out.add(v);
		return out;
	}

	@Test
	public void everyLayoutTextAndIconIsReadable() throws Exception {
		Map<String, Map<String, String>> styles = styles();
		Map<String, String> dimens = new HashMap<>();
		for (Element e : children(parse(new File(RES, "values/dimens.xml")))) {
			dimens.put(e.getAttribute("name"), e.getTextContent().trim());
		}
		Set<String> skip = includeOnly();
		Scan scan = new Scan(styles, dimens);
		File[] layouts = new File(RES, "layout")
				.listFiles((d, n) -> n.endsWith(".xml"));
		assertNotNull(layouts);
		Arrays.sort(layouts);
		for (Mode m : modes(styles)) {
			for (File f : layouts) {
				if (skip.contains(f.getName())) continue;
				Element root = parse(f);
				inline(root);
				List<Integer> under = list(m.screens);
				String parent = INFLATED_INTO.get(f.getName());
				if (parent != null) {
					under = layer(fills(Collections.singletonMap(
							"android:background", parent), m), under);
				}
				scan.walk(f.getName(), root, m, under);
			}
		}
		assertTrue("checked " + scan.checked, scan.checked > 10000);
		Set<String> unique = new TreeSet<>(scan.problems);
		assertEquals(String.join("\n", unique), 0, unique.size());
	}

	private static Element byId(Element e, String id) {
		if (id.equals(e.getAttribute("android:id"))) return e;
		for (Element c : children(e)) {
			Element found = byId(c, id);
			if (found != null) return found;
		}
		return null;
	}

	@Test
	public void theDecoyCalculatorEqualsKeyIsReadable() throws Exception {
		Map<String, Map<String, String>> styles = styles();
		Element root = parse(new File(RES,
				"layout/activity_decoy_calculator.xml"));
		Element key = byId(root, "@+id/btnEq");
		assertNotNull(key);
		Map<String, String> a = attrs(key, styles);
		double size = textSize(a, new HashMap<>());
		double need = size >= 24 ? LARGE_TEXT : TEXT;
		List<String> problems = new ArrayList<>();
		for (Mode m : modes(styles)) {
			Integer screen = color(root.getAttribute("android:background"), m);
			Integer fill = color(a.get("android:background"), m);
			Integer text = color(a.get("android:textColor"), m);
			assertNotNull(m.name, screen);
			assertNotNull(m.name, fill);
			assertNotNull(m.name, text);
			double label = ratio(text, fill);
			if (label < need) {
				problems.add(String.format(Locale.ROOT,
						"the = label (%s) is %.2f:1, needs %.1f:1", m.name,
						label, need));
			}
			double edge = ratio(fill, screen);
			if (edge < GRAPHIC) {
				problems.add(String.format(Locale.ROOT,
						"the = key against the calculator (%s) is %.2f:1",
						m.name, edge));
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void sentBubblesKeepWhiteTextReadable() throws Exception {
		List<String> problems = new ArrayList<>();
		Map<String, Map<String, String>> styles = styles();
		for (Mode m : modes(styles)) {
			List<String> fills = new ArrayList<>(BUBBLE_COLORS);
			fills.add("@color/zerion_message_sent");
			for (String fill : fills) {
				Integer bg = color(fill, m);
				assertNotNull(fill, bg);
				for (String fg : new String[] {"@color/zerion_text_primary_inverse",
						"@color/private_message_date_inverse",
						"@color/zerion_text_link_inverse"}) {
					double r = ratio(color(fg, m), bg);
					if (r < TEXT) {
						problems.add(String.format(Locale.ROOT,
								"%s on %s (%s) is %.2f:1", fg, fill, m.name, r));
					}
				}
				int inset = over(color("@color/bubble_out_inset", m), bg);
				double r = ratio(0xFFFFFFFF, inset);
				if (r < TEXT) {
					problems.add(String.format(Locale.ROOT,
							"white on inset of %s (%s) is %.2f:1", fill, m.name,
							r));
				}
			}
			int pill = color("@color/msg_status_bubble_background", m);
			for (int backdrop : new int[] {0xFFFFFFFF, 0xFF000000}) {
				int bg = over(pill, backdrop);
				double r = ratio(color("@color/msg_status_bubble_foreground",
						m), bg);
				if (r < TEXT) {
					problems.add(String.format(Locale.ROOT,
							"time pill (%s) is %.2f:1", m.name, r));
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void codeSetColorsAreReadable() throws Exception {
		List<String> problems = new ArrayList<>();
		Map<String, Map<String, String>> styles = styles();
		String[] texts = {"@color/zerion_accent_text",
				"@color/zerion_destructive_text", "@color/zerion_success_text",
				"@color/zerion_warning_text", "@color/zerion_button_text_positive",
				"@color/zerion_button_text_neutral",
				"@color/zerion_button_text_negative", "@color/zerion_text_primary",
				"@color/zerion_text_secondary", "@color/zerion_hint",
				"?attr/zerionAccentText", "?android:textColorLink",
				"?attr/colorError"};
		for (Mode m : modes(styles)) {
			int[] surfaces = m.night ? NIGHT_SURFACES : DAY_SURFACES;
			int toolbar = over(0x25000000, color("@color/zerion_dark_bg", m));
			for (String fg : texts) {
				Integer c = color(fg, m);
				assertNotNull(fg + " " + m.name, c);
				List<Integer> backdrops = list(surfaces);
				if (fg.equals("@color/zerion_text_primary")
						|| fg.equals("@color/zerion_text_secondary")) {
					backdrops.add(toolbar);
				}
				double r = worst(c, backdrops);
				if (r < TEXT) {
					problems.add(String.format(Locale.ROOT, "%s (%s) is %.2f:1",
							fg, m.name, r));
				}
			}
			int tinted = over(0x1F26B7F0, surfaces[0]);
			List<Integer> iconBackdrops = list(surfaces);
			iconBackdrops.add(tinted);
			for (String fg : new String[] {"@color/zerion_accent_icon",
					"@color/zerion_accent_large_text",
					"?attr/colorControlNormal"}) {
				double r = worst(color(fg, m), iconBackdrops);
				if (r < GRAPHIC) {
					problems.add(String.format(Locale.ROOT, "%s (%s) is %.2f:1",
							fg, m.name, r));
				}
			}
			for (String fill : new String[] {"@color/zerion_success_fill",
					"@color/zerion_warning_fill", "@color/zerion_error_fill"}) {
				double r = ratio(0xFFFFFFFF, color(fill, m));
				if (r < TEXT) {
					problems.add(String.format(Locale.ROOT,
							"white on %s (%s) is %.2f:1", fill, m.name, r));
				}
			}
			int inverse = color("?attr/colorSurfaceInverse", m);
			for (String fg : new String[] {"?attr/colorOnSurfaceInverse",
					"@color/zerion_accent_text_inverse"}) {
				double r = ratio(color(fg, m), inverse);
				if (r < TEXT) {
					problems.add(String.format(Locale.ROOT,
							"%s on the snackbar (%s) is %.2f:1", fg, m.name, r));
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}
}
