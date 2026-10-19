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
					"@drawable/bg_channel_card");
	private static final Set<String> OVER_IMAGES = new HashSet<>(Arrays.asList(
			"item_gallery_image.xml#@+id/video_icon",
			"text_avatar_view.xml#@+id/textAvatarView",
			"list_item_grouptr_video_in.xml#@+id/videoPlayIcon",
			"list_item_grouptr_video_out.xml#@+id/videoPlayIcon"));
	private static final List<String> TEXT_TAGS = Arrays.asList("TextView",
			"EmojiTextView", "AppCompatTextView", "MaterialTextView", "Button",
			"MaterialButton", "EditText", "EmojiEditText", "TextInputEditText",
			"CheckBox", "MaterialCheckBox", "SwitchMaterial", "MaterialSwitch",
			"RadioButton", "Chip");
	private static final List<String> BUTTON_TAGS = Arrays.asList("Button",
			"MaterialButton", "AppCompatButton");
	private static final List<String> BARS = Arrays.asList("2dp", "3dp",
			"4dp");

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
		Map<String, String> amoled = new HashMap<>(base);
		assertNotNull(styles.get("AmoledOverlay"));
		amoled.putAll(styles.get("AmoledOverlay"));
		List<Mode> out = new ArrayList<>();
		for (String variant : new String[] {"day", "night", "night amoled"}) {
			boolean isNight = !variant.equals("day");
			Map<String, String> under = variant.equals("night amoled")
					? amoled : base;
			out.add(new Mode(variant, isNight, isNight ? night : day, under,
					isNight ? NIGHT_SCREENS : DAY_SCREENS));
			for (Map.Entry<String, Map<String, String>> s : styles.entrySet()) {
				if (!s.getKey().startsWith("AccentOverlay.")) continue;
				Map<String, String> theme = new HashMap<>(under);
				theme.putAll(s.getValue());
				out.add(new Mode(variant + " " + s.getKey(), isNight,
						isNight ? night : day, theme,
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

	private static void putStyle(Map<String, String> out, String style,
			Map<String, Map<String, String>> styles) {
		if (style == null || !style.startsWith("@style/")
				|| !styles.containsKey(style.substring(7))) {
			return;
		}
		for (Map.Entry<String, String> i :
				styles.get(style.substring(7)).entrySet()) {
			out.put(i.getKey().contains(":") ? i.getKey()
					: "app:" + i.getKey(), i.getValue());
		}
	}

	private static Map<String, String> attrs(Element e,
			Map<String, Map<String, String>> styles) {
		Map<String, String> direct = new HashMap<>();
		putStyle(direct, e.getAttribute("style"), styles);
		NamedNodeMap a = e.getAttributes();
		for (int i = 0; i < a.getLength(); i++) {
			direct.put(a.item(i).getNodeName(), a.item(i).getNodeValue());
		}
		Map<String, String> out = new HashMap<>();
		putStyle(out, direct.get("android:textAppearance"), styles);
		out.putAll(direct);
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
			String tag = e.getTagName().substring(
					e.getTagName().lastIndexOf('.') + 1);
			String where = file + " " + id;
			if (tag.equals("View") && BARS.contains(a.getOrDefault(
					"android:layout_width", "")) && f != null
					&& !f.contains(null)) {
				for (int fill : f) {
					checked++;
					double r = worst(fill, bgs);
					if (r < GRAPHIC) {
						problems.add(String.format(Locale.ROOT,
								"%s bar (%s) %s is %.2f:1, needs %.1f:1",
								where, m.name, a.get("android:background"),
								r, GRAPHIC));
					}
				}
			}
			boolean hasFill = false;
			if (f != null && !f.contains(null) && !f.isEmpty()
					&& ((f.get(0) >>> 24) & 0xFF) > 0) {
				bgs = layer(f, bgs);
				hasFill = true;
			}
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
				"?attr/zerionColorAccentText", "?android:textColorLink",
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

	static final String[] TOKENS = {"zerionColorBackground",
			"zerionColorSurface", "zerionColorSurfaceVariant",
			"zerionColorOnSurface", "zerionColorOnSurfaceMuted",
			"zerionColorDivider", "zerionColorAccent", "zerionColorOnAccent",
			"zerionColorAccentText", "zerionColorAccentIcon",
			"zerionColorBubbleIn", "zerionColorOutline",
			"zerionColorOnBubbleIn", "zerionColorOnBubbleInMuted",
			"zerionColorBubbleInInset", "zerionColorBubbleOut",
			"zerionColorOnBubbleOut", "zerionColorOnBubbleOutMuted",
			"zerionColorBubbleOutInset", "zerionColorDanger",
			"zerionColorDangerText", "zerionColorSuccess",
			"zerionColorWarning", "zerionColorStatusMuted",
			"zerionColorPresenceOnline", "zerionColorPresenceOffline",
			"zerionColorScrim", "zerionColorOnScrim",
			"zerionColorMediaBackdrop", "zerionColorMessageSelectedOwn",
			"zerionColorMessageSelectedReceived",
			"zerionColorChannelCardSelected",
			"zerionColorSelectionForeground"};
	private static final double DIVIDER = 1.1;

	private static int token(String name, Mode m) {
		Integer c = color("?attr/" + name, m);
		assertNotNull(name + " (" + m.name + ")", c);
		return c;
	}

	private static void need(List<String> problems, String what, int fg,
			List<Integer> backdrops, double min, Mode m) {
		double r = worst(fg, backdrops);
		if (r < min) {
			problems.add(String.format(Locale.ROOT, "%s (%s) is %.2f:1, needs"
					+ " %.1f:1", what, m.name, r, min));
		}
	}

	@Test
	public void everyThemeDefinesEveryToken() throws Exception {
		Map<String, Map<String, String>> styles = styles();
		Set<String> declared = new TreeSet<>();
		for (Element e : children(parse(new File(RES, "values/attrs.xml")))) {
			if (e.getTagName().equals("attr")
					&& e.getAttribute("name").startsWith("zerionColor")) {
				declared.add(e.getAttribute("name"));
			}
		}
		assertEquals(new TreeSet<>(Arrays.asList(TOKENS)), declared);
		for (Mode m : modes(styles)) {
			for (String t : TOKENS) token(t, m);
		}
	}

	@Test
	public void themeTokensAreReadableInEveryTheme() throws Exception {
		List<String> problems = new ArrayList<>();
		for (Mode m : modes(styles())) {
			List<Integer> screens = Arrays.asList(
					token("zerionColorBackground", m),
					token("zerionColorSurface", m),
					token("zerionColorSurfaceVariant", m));
			for (String t : new String[] {"zerionColorOnSurface",
					"zerionColorOnSurfaceMuted", "zerionColorAccentText",
					"zerionColorDangerText", "zerionColorSuccess",
					"zerionColorWarning", "zerionColorStatusMuted"}) {
				need(problems, t, token(t, m), screens, TEXT, m);
			}
			for (String t : new String[] {"zerionColorAccentIcon",
					"zerionColorDanger"}) {
				need(problems, t, token(t, m), screens, GRAPHIC, m);
			}
			need(problems, "zerionColorDivider", token("zerionColorDivider",
					m), screens.subList(0, 2), DIVIDER, m);
			need(problems, "zerionColorOnAccent on the accent",
					token("zerionColorOnAccent", m), Arrays.asList(
							token("zerionColorAccent", m)), TEXT, m);

			int in = token("zerionColorBubbleIn", m);
			List<Integer> inBackdrops = Arrays.asList(in,
					over(token("zerionColorBubbleInInset", m), in));
			for (String t : new String[] {"zerionColorOnBubbleIn",
					"zerionColorOnBubbleInMuted", "zerionColorAccentText",
					"zerionColorDangerText"}) {
				need(problems, t + " in an incoming bubble", token(t, m),
						inBackdrops, TEXT, m);
			}
			need(problems, "zerionColorAccentIcon in an incoming bubble",
					token("zerionColorAccentIcon", m), inBackdrops, GRAPHIC,
					m);

			List<Integer> outFills = new ArrayList<>();
			outFills.add(token("zerionColorBubbleOut", m));
			for (String b : BUBBLE_COLORS) outFills.add(color(b, m));
			List<Integer> outBackdrops = new ArrayList<>(outFills);
			outBackdrops.addAll(layer(Arrays.asList(
					token("zerionColorBubbleOutInset", m)), outFills));
			for (String t : new String[] {"zerionColorOnBubbleOut",
					"zerionColorOnBubbleOutMuted"}) {
				need(problems, t + " in an outgoing bubble", token(t, m),
						outBackdrops, TEXT, m);
			}

			need(problems, "zerionColorOnScrim", token("zerionColorOnScrim",
					m), layer(Arrays.asList(token("zerionColorScrim", m)),
					Arrays.asList(0xFFFFFFFF, 0xFF000000)), TEXT, m);
			need(problems, "zerionColorOnScrim on the media backdrop",
					token("zerionColorOnScrim", m), layer(Arrays.asList(
							token("zerionColorMediaBackdrop", m)),
					Arrays.asList(0xFFFFFFFF, 0xFF000000)), TEXT, m);
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theIncomingBubbleFollowsTheTheme() throws Exception {
		List<String> problems = new ArrayList<>();
		for (Mode m : modes(styles())) {
			double bubble = luminance(token("zerionColorBubbleIn", m));
			double text = luminance(token("zerionColorOnBubbleIn", m));
			boolean light = bubble > 0.7 && text < 0.05;
			boolean dark = bubble < 0.05 && text > 0.7;
			if (m.night ? !dark : !light) {
				problems.add(m.name + " incoming bubble is not "
						+ (m.night ? "dark" : "light") + " with "
						+ (m.night ? "light" : "dark") + " text");
			}
			for (String d : new String[] {"msg_in", "msg_in_top", "notice_in",
					"notice_in_bottom"}) {
				Integer fill = shapeFill(d, m);
				if (fill == null || fill != token("zerionColorBubbleIn", m)) {
					problems.add(m.name + " @drawable/" + d
							+ " is not the incoming bubble color");
				}
			}
			for (String d : new String[] {"msg_out", "msg_out_top",
					"notice_out", "notice_out_bottom"}) {
				Integer fill = shapeFill(d, m);
				if (fill == null || fill != token("zerionColorBubbleOut", m)) {
					problems.add(m.name + " @drawable/" + d
							+ " is not the outgoing bubble color");
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void amoledDarkensTheSurfaces() throws Exception {
		Map<String, Map<String, String>> styles = styles();
		for (Mode m : modes(styles)) {
			if (!m.name.startsWith("night amoled")) continue;
			assertEquals(m.name, 0xFF000000, token("zerionColorBackground",
					m));
			assertTrue(m.name, luminance(token("zerionColorSurface", m))
					< 0.01);
		}
	}

	private static final double SEPARATION = 1.2;
	private static final double INSET = 1.15;

	private static Map<String, String> effectiveDimens() throws Exception {
		Map<String, String> out = new HashMap<>();
		for (String folder : new String[] {"values", "values-v21"}) {
			File f = new File(new File(RES, folder), "dimens.xml");
			if (!f.isFile()) continue;
			for (Element e : children(parse(f))) {
				if (e.getTagName().equals("dimen")) {
					out.put(e.getAttribute("name"), e.getTextContent().trim());
				}
			}
		}
		return out;
	}

	private static double size(String v, Map<String, String> dimens) {
		for (int depth = 0; v != null && v.startsWith("@dimen/")
				&& depth < 10; depth++) {
			v = dimens.get(v.substring(7));
		}
		if (v == null) return 0;
		Matcher m = Pattern.compile("([\\d.]+)(dp|dip|px|sp)").matcher(v);
		return m.matches() ? Double.parseDouble(m.group(1)) : 0;
	}

	private static Element firstShape(String name, Mode m) throws Exception {
		for (String folder : m.night ? new String[] {"drawable-night",
				"drawable"} : new String[] {"drawable"}) {
			File f = new File(new File(RES, folder), name + ".xml");
			if (!f.isFile()) continue;
			Element root = parse(f);
			if (root.getTagName().equals("shape")) return root;
			NodeList shapes = root.getElementsByTagName("shape");
			return shapes.getLength() == 0 ? null : (Element) shapes.item(0);
		}
		return null;
	}

	private static Element child(Element parent, String tag) {
		for (Element c : children(parent)) {
			if (c.getTagName().equals(tag)) return c;
		}
		return null;
	}

	@Test
	public void incomingBubblesAndCardsStandOutFromTheScreen()
			throws Exception {
		Map<String, String> dimens = effectiveDimens();
		List<String> problems = new ArrayList<>();
		for (Mode m : modes(styles())) {
			int screen = token("zerionColorBackground", m);
			for (String d : new String[] {"msg_in", "msg_in_top", "notice_in",
					"notice_in_bottom", "bg_contact_card"}) {
				Element shape = firstShape(d, m);
				assertNotNull(d, shape);
				Element solid = child(shape, "solid");
				assertNotNull(d, solid);
				int fill = color(solid.getAttribute("android:color"), m);
				double best = ratio(over(fill, screen), screen);
				Element stroke = child(shape, "stroke");
				if (stroke != null && size(stroke.getAttribute("android:width"),
						dimens) > 0) {
					int edge = color(stroke.getAttribute("android:color"), m);
					best = Math.max(best, ratio(over(edge, screen), screen));
				}
				if (best < SEPARATION) {
					problems.add(String.format(Locale.ROOT,
							"@drawable/%s (%s) stands out by %.2f:1, needs"
									+ " %.2f:1", d, m.name, best, SEPARATION));
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theInsetStandsOutInsideTheIncomingBubble() throws Exception {
		List<String> problems = new ArrayList<>();
		for (Mode m : modes(styles())) {
			int bubble = token("zerionColorBubbleIn", m);
			int inset = over(token("zerionColorBubbleInInset", m), bubble);
			double r = ratio(inset, bubble);
			if (r < INSET) {
				problems.add(String.format(Locale.ROOT,
						"the inset in an incoming bubble (%s) is %.2f:1",
						m.name, r));
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void mutedOutgoingTextStaysWhiteAsNoVisiblyMutedWhitePasses()
			throws Exception {
		for (Mode m : modes(styles())) {
			assertEquals(m.name, 0xFFFFFFFF,
					token("zerionColorOnBubbleOutMuted", m));
			List<Integer> bubbles = new ArrayList<>();
			bubbles.add(token("zerionColorBubbleOut", m));
			for (String b : BUBBLE_COLORS) bubbles.add(color(b, m));
			for (int alpha = 0x80; alpha <= 0xF2; alpha++) {
				int muted = (alpha << 24) | 0xFFFFFF;
				assertTrue(String.format(Locale.ROOT, "%s white at alpha %02x"
						+ " passes on every bubble color, so muted outgoing"
						+ " text could be visibly muted", m.name, alpha),
						worst(muted, bubbles) < TEXT);
			}
		}
	}

	@Test
	public void callQualityColorsAreReadableOnTheirPill() throws Exception {
		List<String> problems = new ArrayList<>();
		for (Mode m : modes(styles())) {
			Integer pill = shapeFill("bg_network_quality", m);
			assertNotNull(m.name, pill);
			List<Integer> under = layer(Arrays.asList(pill), list(m.screens));
			for (String t : new String[] {"zerionColorSuccess",
					"zerionColorWarning", "zerionColorDangerText"}) {
				need(problems, t + " on the call quality pill", token(t, m),
						under, TEXT, m);
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	private static Element innerShape(String name) throws Exception {
		NodeList shapes = parse(new File(RES, "drawable/" + name + ".xml"))
				.getElementsByTagName("shape");
		assertEquals(name, 2, shapes.getLength());
		return (Element) shapes.item(1);
	}

	@Test
	public void presenceDotsDifferByMoreThanColor() throws Exception {
		List<String> problems = new ArrayList<>();
		Element online = innerShape("bg_presence_online");
		Element offline = innerShape("bg_presence_offline");
		for (Mode m : modes(styles())) {
			int screen = token("zerionColorBackground", m);
			int on = color(child(online, "solid").getAttribute(
					"android:color"), m);
			Element ring = child(offline, "stroke");
			Element hole = child(offline, "solid");
			int off = color((ring != null ? ring : hole).getAttribute(
					"android:color"), m);
			if (ring == null || hole == null || color(hole.getAttribute(
					"android:color"), m) != screen) {
				problems.add(m.name + " the offline dot is not a hollow ring");
			}
			double apart = ratio(on, off);
			if (apart < 2.5) {
				problems.add(String.format(Locale.ROOT,
						"online and offline dots (%s) are %.2f:1 apart",
						m.name, apart));
			}
			for (int dot : new int[] {on, off}) {
				double r = ratio(dot, screen);
				if (r < GRAPHIC) {
					problems.add(String.format(Locale.ROOT,
							"a presence dot %08x (%s) is %.2f:1 on the screen",
							dot, m.name, r));
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void voicePlayButtonsStandOutInEveryBubble() throws Exception {
		Map<String, String> dimens = effectiveDimens();
		List<String> problems = new ArrayList<>();
		for (Mode m : modes(styles())) {
			for (String file : new String[] {"voice_message_view.xml",
					"voice_message_view_out.xml"}) {
				List<Integer> bubbles = new ArrayList<>();
				if (file.contains("_out")) {
					bubbles.add(token("zerionColorBubbleOut", m));
					for (String b : BUBBLE_COLORS) bubbles.add(color(b, m));
				} else {
					bubbles.add(token("zerionColorBubbleIn", m));
				}
				Element button = byId(parse(new File(RES, "layout/" + file)),
						"@+id/playPauseButton");
				assertNotNull(file, button);
				int fill = color(button.getAttribute("android:backgroundTint"),
						m);
				Integer edge = null;
				if (size(button.getAttribute("app:strokeWidth"), dimens) > 0) {
					edge = color(button.getAttribute("app:strokeColor"), m);
				}
				for (int bubble : bubbles) {
					double best = ratio(over(fill, bubble), bubble);
					if (edge != null) {
						best = Math.max(best, ratio(over(edge, bubble),
								bubble));
					}
					if (best < GRAPHIC) {
						problems.add(String.format(Locale.ROOT,
								"%s play button (%s) on %08x is %.2f:1",
								file, m.name, bubble, best));
					}
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	private static final String[][] STATE_LINES = {
			{"list_item_conversation_msg_out.xml", "@+id/time", "out"},
			{"list_item_conversation_notice_out.xml", "@+id/time", "out"},
			{"list_item_conversation_secret_out.xml", "@+id/time", "out"},
			{"list_item_grouptr_post_out.xml", "@+id/postTime", "out"},
			{"list_item_grouptr_voice_out.xml", "@+id/voiceTime", "out"},
			{"list_item_grouptr_image_out.xml", "@+id/imageTime", "scrim"},
			{"list_item_grouptr_video_out.xml", "@+id/videoTime", "scrim"},
			{"list_item_channel_post.xml", "@+id/channelPostTimestampView",
					"in"},
			{"list_item_channel_comment.xml", "@+id/commentTimestamp",
					"in"}};
	private static final String[] NOT_SENT_OUTLINES = {
			"bg_message_not_sent_out", "bg_message_not_sent_in",
			"bg_message_not_sent_row", "bg_message_not_sent_card"};

	@Test
	public void messageStatesAreReadableOnEveryBubbleAndTheme()
			throws Exception {
		Map<String, Map<String, String>> styles = styles();
		Map<String, String> dimens = effectiveDimens();
		Element actions = parse(new File(RES,
				"layout/view_message_not_sent_actions.xml"));
		List<String> problems = new ArrayList<>();
		for (Mode m : modes(styles)) {
			List<Integer> screens = list(m.screens);
			List<Integer> out = new ArrayList<>();
			out.add(token("zerionColorBubbleOut", m));
			for (String b : BUBBLE_COLORS) out.add(color(b, m));
			Map<String, List<Integer>> under = new HashMap<>();
			under.put("out", layer(out, screens));
			under.put("in", layer(Arrays.asList(token("zerionColorBubbleIn",
					m)), screens));
			under.put("scrim", layer(Arrays.asList(token("zerionColorScrim",
					m)), Arrays.asList(0xFFFFFFFF, 0xFF000000)));
			under.put("screen", screens);
			for (String[] line : STATE_LINES) {
				String where = line[0] + " " + line[1];
				Element view = byId(parse(new File(RES, "layout/" + line[0])),
						line[1]);
				assertNotNull(where, view);
				String fg = attrs(view, styles).get("android:textColor");
				assertNotNull(where + " has no text color", fg);
				Integer c = color(fg, m);
				assertNotNull(where + " " + fg, c);
				need(problems, where + " state label", c, under.get(line[2]),
						TEXT, m);
			}
			for (String id : new String[] {"@+id/messageEditButton",
					"@+id/messageRetryButton", "@+id/messageDeleteButton"}) {
				Element button = byId(actions, id);
				assertNotNull(id, button);
				need(problems, id, color(button.getAttribute(
						"android:textColor"), m), screens, TEXT, m);
			}
			Element retry = byId(actions, "@+id/messageRetryButton");
			for (String k : new String[] {"app:strokeColor", "app:iconTint"}) {
				need(problems, "Retry " + k, color(retry.getAttribute(k), m),
						screens, GRAPHIC, m);
			}
			for (String d : NOT_SENT_OUTLINES) {
				Element shape = firstShape(d, m);
				assertNotNull(d, shape);
				Element stroke = child(shape, "stroke");
				assertNotNull(d + " has no outline", stroke);
				assertTrue(d + " outline is too thin", size(stroke.getAttribute(
						"android:width"), dimens) >= 1.5);
				need(problems, d + " outline", color(stroke.getAttribute(
						"android:color"), m), screens, GRAPHIC, m);
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}
}
