package com.professor.zerion.android;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class UnthemedActivitiesTest {

	private static final File MAIN = new File("src/main");
	private static final File RES = new File(MAIN, "res");
	private static final String PACKAGE = "com.professor.zerion";
	private static final Pattern REFERENCE = Pattern.compile(
			"R\\.(layout|drawable|style|color)\\.(\\w+)");

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

	private static final class Styles {
		final Map<String, Map<String, String>> items = new LinkedHashMap<>();
		final Map<String, String> parents = new HashMap<>();

		Styles() throws Exception {
			File[] files = new File(RES, "values")
					.listFiles((d, n) -> n.endsWith(".xml"));
			assertNotNull(files);
			for (File f : files) {
				NodeList nodes = parse(f).getElementsByTagName("style");
				for (int i = 0; i < nodes.getLength(); i++) {
					Element s = (Element) nodes.item(i);
					String name = s.getAttribute("name");
					Map<String, String> map = new LinkedHashMap<>();
					NodeList list = s.getElementsByTagName("item");
					for (int j = 0; j < list.getLength(); j++) {
						Element item = (Element) list.item(j);
						map.put(item.getAttribute("name"),
								item.getTextContent().trim());
					}
					items.put(name, map);
					String parent = s.getAttribute("parent");
					if (parent.startsWith("@style/")) {
						parent = parent.substring(7);
					}
					if (!s.hasAttribute("parent") && name.contains(".")) {
						parent = name.substring(0, name.lastIndexOf('.'));
					}
					parents.put(name, parent);
				}
			}
		}

		boolean derivesFromZerionTheme(String name) {
			for (int depth = 0; name != null && depth < 20; depth++) {
				if (name.equals("ZerionTheme")) return true;
				name = parents.get(name);
			}
			return false;
		}
	}

	private static final class Walk {
		final Styles styles;
		final Set<String> seen = new TreeSet<>();
		final List<String> problems = new ArrayList<>();

		Walk(Styles styles) {
			this.styles = styles;
		}

		void value(String where, String v) throws Exception {
			v = v.trim();
			if (v.startsWith("?attr/zerion") || v.startsWith("?zerion")) {
				problems.add(where + " uses " + v);
			} else if (v.startsWith("@layout/")) {
				resource("layout", v.substring(8));
			} else if (v.startsWith("@drawable/")) {
				resource("drawable", v.substring(10));
			} else if (v.startsWith("@color/")) {
				resource("color", v.substring(7));
			} else if (v.startsWith("@style/")) {
				style(v.substring(7));
			}
		}

		void style(String name) throws Exception {
			for (int depth = 0; name != null && depth < 20; depth++) {
				if (!seen.add("style/" + name)) return;
				Map<String, String> map = styles.items.get(name);
				if (map == null) return;
				for (Map.Entry<String, String> item : map.entrySet()) {
					value("@style/" + name + " " + item.getKey(),
							item.getValue());
				}
				name = styles.parents.get(name);
			}
		}

		void resource(String type, String name) throws Exception {
			if (!seen.add(type + "/" + name)) return;
			File dir = new File(RES, type);
			File f = new File(dir, name + ".xml");
			if (!f.isFile()) return;
			for (Element e : all(parse(f))) {
				NamedNodeMap a = e.getAttributes();
				for (int i = 0; i < a.getLength(); i++) {
					if (a.item(i).getNodeName().startsWith("xmlns")) continue;
					value(type + "/" + name + ".xml " + e.getTagName() + " "
							+ a.item(i).getNodeName(), a.item(i).getNodeValue());
				}
			}
		}
	}

	@Test
	public void screensWithoutTheZerionThemeUseNoThemeColors()
			throws Exception {
		Styles styles = new Styles();
		Element manifest = parse(new File(MAIN, "AndroidManifest.xml"));
		Element application = (Element) manifest
				.getElementsByTagName("application").item(0);
		String appTheme = application.getAttribute("android:theme");
		List<String> unthemed = new ArrayList<>();
		Walk walk = new Walk(styles);
		NodeList activities = manifest.getElementsByTagName("activity");
		for (int i = 0; i < activities.getLength(); i++) {
			Element activity = (Element) activities.item(i);
			String theme = activity.hasAttribute("android:theme")
					? activity.getAttribute("android:theme") : appTheme;
			String styleName = theme.startsWith("@style/")
					? theme.substring(7) : null;
			if (styleName != null && styles.derivesFromZerionTheme(styleName)) {
				continue;
			}
			String name = activity.getAttribute("android:name");
			if (name.startsWith(".")) name = PACKAGE + name;
			unthemed.add(name);
			if (styleName != null) walk.style(styleName);
			File source = new File(new File(MAIN, "java"),
					name.replace('.', '/') + ".java");
			assertTrue(source.getPath(), source.isFile());
			String java = new String(Files.readAllBytes(source.toPath()),
					StandardCharsets.UTF_8);
			Matcher m = REFERENCE.matcher(java);
			while (m.find()) {
				if (m.group(1).equals("style")) {
					walk.style(m.group(2).replace('_', '.'));
				} else {
					walk.resource(m.group(1), m.group(2));
				}
			}
		}
		assertTrue("unthemed " + unthemed, unthemed.size() >= 4);
		assertTrue(unthemed.toString(), unthemed.contains(
				PACKAGE + ".android.conversation.VideoPlayerActivity"));
		Set<String> unique = new TreeSet<>(walk.problems);
		assertEquals(String.join("\n", unique), 0, unique.size());
	}
}
