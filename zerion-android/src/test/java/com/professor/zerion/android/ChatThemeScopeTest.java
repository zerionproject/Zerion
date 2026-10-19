package com.professor.zerion.android;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ChatThemeScopeTest {

	private static final File RES = new File("src/main/res");
	private static final Pattern SCOPE = Pattern.compile("(activity_conversation"
			+ "|activity_grouptr_.*|activity_channel_.*|list_item_conversation_.*"
			+ "|list_item_grouptr_.*|list_item_channel.*|list_item_home_.*"
			+ "|fragment_home_list|view_home_empty"
			+ "|reply_preview|view_link_preview.*|voice_message_view.*"
			+ "|voice_recording_overlay|emoji_text_input_view|text_input_view"
			+ "|view_composite_send_button|view_grouptr_.*|dialog_.*channel"
			+ "|dialog_channel_.*|sheet_channel_.*"
			+ "|dialog_add_delegation|dialog_apply_to_join"
			+ "|dialog_attachment_picker|dialog_disappearing_messages"
			+ "|dialog_avatar_fullscreen|item_media_thumbnail"
			+ "|list_item_contact_connection|fragment_conversation_settings"
			+ "|image_preview|list_item_image)\\.xml");
	private static final Set<String> GRADIENT_AND_COLOR = new HashSet<>(
			Arrays.asList("android:color", "android:startColor",
					"android:centerColor", "android:endColor"));
	private static final Set<String> STATIC_ACCENTS = new HashSet<>(
			Arrays.asList("zerion_primary_accent", "zerion_accent_text",
					"zerion_accent_icon", "zerion_cyan"));
	private static final Set<String> COLOR_ATTRS = new HashSet<>(Arrays.asList(
			"textColor", "textColorLink", "textColorHint", "tint",
			"backgroundTint", "iconTint", "strokeColor", "cardBackgroundColor",
			"progressTint", "thumbTint", "progressBackgroundTint", "trackTint",
			"indicatorColor", "trackColor", "titleTextColor",
			"subtitleTextColor", "navigationIconTint", "boxStrokeColor",
			"hintTextColor", "rippleColor", "chipBackgroundColor",
			"background", "foregroundTint", "buttonTint", "color"));
	private static final Set<String> FREE_SIZES = new HashSet<>(Arrays.asList(
			"list_item_grouptr_admin_member.xml#@+id/memberAvatar",
			"activity_grouptr_admin.xml#@+id/headerAvatar",
			"activity_grouptr_conversation.xml#@+id/groupAvatar",
			"dialog_attachment_picker.xml#🎁"));
	private static final List<String> TEXT_TAGS = Arrays.asList("TextView",
			"EditText", "Button", "RadioButton", "CheckBox", "Switch",
			"MaterialSwitch", "Chip");

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

	private static String local(String name) {
		return name.substring(name.indexOf(':') + 1);
	}

	private static Map<String, Map<String, String>> styles() throws Exception {
		Map<String, Map<String, String>> raw = new LinkedHashMap<>();
		Map<String, String> parents = new HashMap<>();
		File[] files = new File(RES, "values")
				.listFiles((d, n) -> n.endsWith(".xml"));
		assertNotNull(files);
		for (File f : files) {
			NodeList nodes = parse(f).getElementsByTagName("style");
			for (int i = 0; i < nodes.getLength(); i++) {
				Element s = (Element) nodes.item(i);
				String name = s.getAttribute("name");
				Map<String, String> items = new LinkedHashMap<>();
				NodeList list = s.getElementsByTagName("item");
				for (int j = 0; j < list.getLength(); j++) {
					Element item = (Element) list.item(j);
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
			while (cur != null && raw.containsKey(cur) && !chain.contains(cur)) {
				chain.add(0, cur);
				cur = parents.get(cur);
			}
			Map<String, String> merged = new HashMap<>();
			for (String link : chain) merged.putAll(raw.get(link));
			out.put(name, merged);
		}
		return out;
	}

	private static Set<String> layoutsInScope() throws Exception {
		File[] files = new File(RES, "layout")
				.listFiles((d, n) -> SCOPE.matcher(n).matches());
		assertNotNull(files);
		Set<String> out = new TreeSet<>();
		List<String> todo = new ArrayList<>();
		for (File f : files) todo.add(f.getName());
		while (!todo.isEmpty()) {
			String name = todo.remove(todo.size() - 1);
			if (!out.add(name)) continue;
			for (Element e : all(parse(new File(new File(RES, "layout"),
					name)))) {
				String layout = e.getTagName().equals("ViewStub")
						? e.getAttribute("android:layout")
						: e.getAttribute("layout");
				if ((e.getTagName().equals("include")
						|| e.getTagName().equals("ViewStub"))
						&& layout.startsWith("@layout/")) {
					todo.add(layout.substring(8) + ".xml");
				}
			}
		}
		return out;
	}

	private boolean themedList(String name) throws Exception {
		File f = new File(new File(RES, "color"), name + ".xml");
		if (!f.isFile()) return false;
		for (Element item : all(parse(f))) {
			if (!item.getTagName().equals("item")) continue;
			if (!item.getAttribute("android:color").startsWith("?")) {
				return false;
			}
		}
		return true;
	}

	private void checkColor(List<String> problems, Set<String> drawables,
			String where, String attr, String value) throws Exception {
		if (value.startsWith("?") || value.equals("@null")
				|| value.equals("@android:color/transparent")) {
			return;
		}
		if (value.startsWith("@drawable/")) {
			drawables.add(value.substring(10));
			return;
		}
		if (value.startsWith("@color/")) {
			String name = value.substring(7);
			if (STATIC_ACCENTS.contains(name)) {
				problems.add(where + " " + attr + " uses the fixed accent "
						+ value);
				return;
			}
			if (themedList(name)) return;
		}
		problems.add(where + " " + attr + " uses " + value
				+ " instead of a theme color");
	}

	private static void noteUntinted(Set<String> untinted,
			Map<String, String> attrs, String image, String... tints) {
		for (String key : new String[] {"android:" + image, "app:" + image,
				image}) {
			String v = attrs.get(key);
			if (v == null || !v.startsWith("@drawable/")) continue;
			boolean tinted = false;
			for (String t : tints) {
				tinted |= attrs.containsKey("android:" + t)
						|| attrs.containsKey("app:" + t)
						|| attrs.containsKey(t);
			}
			if (!tinted) untinted.add(v.substring(10));
		}
	}

	private void checkLayout(List<String> problems, Set<String> drawables,
			Set<String> untinted, String file,
			Map<String, Map<String, String>> styles) throws Exception {
		for (Element e : all(parse(new File(new File(RES, "layout"), file)))) {
			String tag = e.getTagName().substring(
					e.getTagName().lastIndexOf('.') + 1);
			String id = e.hasAttribute("android:id")
					? e.getAttribute("android:id") : tag;
			String where = file + " " + id;
			Map<String, String> attrs = new HashMap<>();
			String style = e.getAttribute("style");
			if (style.startsWith("@style/")
					&& styles.containsKey(style.substring(7))) {
				attrs.putAll(styles.get(style.substring(7)));
			}
			NamedNodeMap a = e.getAttributes();
			for (int i = 0; i < a.getLength(); i++) {
				Node n = a.item(i);
				if (n.getNodeName().startsWith("tools:")) continue;
				attrs.put(n.getNodeName(), n.getNodeValue());
			}
			for (Map.Entry<String, String> at : attrs.entrySet()) {
				if (!COLOR_ATTRS.contains(local(at.getKey()))) continue;
				checkColor(problems, drawables, where, at.getKey(),
						at.getValue());
			}
			noteUntinted(untinted, attrs, "src", "tint");
			noteUntinted(untinted, attrs, "srcCompat", "tint");
			noteUntinted(untinted, attrs, "background", "backgroundTint");
			noteUntinted(untinted, attrs, "icon", "iconTint");
			boolean text = false;
			for (String t : TEXT_TAGS) text |= tag.endsWith(t);
			if (!text) continue;
			String freeKey = file + "#" + (e.hasAttribute("android:id")
					? id : e.getAttribute("android:text"));
			if (e.hasAttribute("android:textSize")
					&& !FREE_SIZES.contains(freeKey)) {
				problems.add(where + " sets its own text size instead of"
						+ " using the type scale");
			}
			String appearance = attrs.getOrDefault("android:textAppearance",
					"");
			if (!appearance.isEmpty() && !appearance.startsWith(
					"@style/TextAppearance.Zerion.")) {
				problems.add(where + " uses " + appearance
						+ " outside the type scale");
			}
			if ((appearance.endsWith(".Title")
					|| appearance.endsWith(".Headline"))
					&& !"@font/bricolage_grotesque".equals(
					attrs.get("android:fontFamily"))) {
				problems.add(where + " uses a display appearance without"
						+ " the display font");
			}
		}
	}

	private void checkVector(List<String> problems, Set<String> drawables,
			String name, Element root) throws Exception {
		String where = "@drawable/" + name + " drawn without a tint";
		if (root.hasAttribute("android:tint")) {
			checkColor(problems, drawables, where, "android:tint",
					root.getAttribute("android:tint"));
			return;
		}
		for (Element e : all(root)) {
			for (String attr : new String[] {"android:fillColor",
					"android:strokeColor"}) {
				if (e.hasAttribute(attr)) {
					checkColor(problems, drawables, where, attr,
							e.getAttribute(attr));
				}
			}
		}
	}

	private void checkDrawable(List<String> problems, Set<String> drawables,
			Set<String> untinted, String name, Set<String> seen)
			throws Exception {
		if (!seen.add(name)) return;
		File f = new File(new File(RES, "drawable"), name + ".xml");
		if (!f.isFile()) return;
		Element root = parse(f);
		if (root.getTagName().equals("vector")
				|| root.getTagName().equals("animated-vector")) {
			if (untinted.contains(name)) {
				checkVector(problems, drawables, name, root);
			}
			return;
		}
		for (Element e : all(root)) {
			NamedNodeMap a = e.getAttributes();
			for (int i = 0; i < a.getLength(); i++) {
				Node n = a.item(i);
				String attr = n.getNodeName();
				if (GRADIENT_AND_COLOR.contains(attr)) {
					checkColor(problems, drawables, "@drawable/" + name + " "
							+ e.getTagName(), attr, n.getNodeValue());
				} else if (attr.equals("android:drawable")) {
					String v = n.getNodeValue();
					if (v.startsWith("@drawable/")) {
						drawables.add(v.substring(10));
					} else {
						checkColor(problems, drawables, "@drawable/" + name
								+ " " + e.getTagName(), attr, v);
					}
				}
			}
		}
	}

	@Test
	public void chatGroupAndChannelScreensTakeEveryColorFromTheTheme()
			throws Exception {
		Map<String, Map<String, String>> styles = styles();
		Set<String> layouts = layoutsInScope();
		assertTrue("layouts " + layouts.size(), layouts.size() > 50);
		List<String> problems = new ArrayList<>();
		Set<String> drawables = new TreeSet<>();
		Set<String> untinted = new TreeSet<>();
		for (String l : layouts) {
			checkLayout(problems, drawables, untinted, l, styles);
		}
		drawables.addAll(untinted);
		Set<String> seen = new HashSet<>();
		while (!seen.containsAll(drawables)) {
			for (String d : new ArrayList<>(drawables)) {
				checkDrawable(problems, drawables, untinted, d, seen);
			}
		}
		assertTrue("drawables " + seen.size(), seen.size() > 20);
		Set<String> unique = new TreeSet<>(problems);
		assertEquals(String.join("\n", unique), 0, unique.size());
	}
}
