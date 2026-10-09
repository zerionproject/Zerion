package com.professor.zerion.android;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TranslationResourcesTest {

	private static final File RES = new File("src/main/res");
	private static final Pattern PLACEHOLDER =
			Pattern.compile("%(\\d+\\$)?[sdf]|%%");
	private static final Set<String> NOT_LOCALES = new HashSet<>(
			java.util.Arrays.asList("values-night", "values-v21", "values-v31",
					"values-ldrtl", "values-iw"));

	private static final class Res {
		final String type;
		final String text;
		final Map<String, String> quantities = new HashMap<>();
		final List<String> items = new ArrayList<>();
		final int links;
		final boolean translatable;

		Res(Element e) {
			type = e.getTagName();
			text = e.getTextContent();
			links = e.getElementsByTagName("a").getLength();
			translatable = !"false".equals(e.getAttribute("translatable"));
			NodeList children = e.getElementsByTagName("item");
			for (int i = 0; i < children.getLength(); i++) {
				Element item = (Element) children.item(i);
				if (type.equals("plurals")) {
					quantities.put(item.getAttribute("quantity"),
							item.getTextContent());
				} else {
					items.add(item.getTextContent());
				}
			}
		}
	}

	private static Map<String, Res> load(File folder, List<String> problems)
			throws Exception {
		Map<String, Res> out = new HashMap<>();
		File[] files = folder.listFiles((d, n) -> n.endsWith(".xml"));
		if (files == null) return out;
		for (File f : files) {
			Element root = DocumentBuilderFactory.newInstance()
					.newDocumentBuilder().parse(f).getDocumentElement();
			NodeList nodes = root.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				Node n = nodes.item(i);
				if (!(n instanceof Element)) continue;
				Element e = (Element) n;
				String tag = e.getTagName();
				if (!tag.equals("string") && !tag.equals("plurals")
						&& !tag.equals("string-array")) {
					continue;
				}
				String key = tag + ":" + e.getAttribute("name");
				if (out.put(key, new Res(e)) != null) {
					problems.add(folder.getName() + " defines " + key
							+ " twice");
				}
			}
		}
		return out;
	}

	private static Set<String> placeholders(String text) {
		Set<String> found = new TreeSet<>();
		Matcher m = PLACEHOLDER.matcher(text);
		while (m.find()) found.add(m.group());
		return found;
	}

	@Test
	public void everyTranslationFitsTheEnglishResource() throws Exception {
		List<String> problems = new ArrayList<>();
		Map<String, Res> english = load(new File(RES, "values"), problems);
		assertTrue(english.size() > 1000);
		File[] folders = RES.listFiles((d, n) -> n.startsWith("values-")
				&& !NOT_LOCALES.contains(n));
		assertTrue(folders != null && folders.length >= 40);
		for (File folder : folders) {
			if (!new File(folder, "strings.xml").isFile()) continue;
			String locale = folder.getName();
			for (Map.Entry<String, Res> entry :
					load(folder, problems).entrySet()) {
				String key = entry.getKey();
				Res t = entry.getValue();
				Res en = english.get(key);
				if (en == null) {
					problems.add(locale + " has " + key
							+ ", which English does not");
					continue;
				}
				if (!en.translatable && !en.text.equals(t.text)) {
					problems.add(locale + " translates " + key);
				}
				if (en.type.equals("string")) {
					if (!placeholders(en.text).equals(
							placeholders(t.text))) {
						problems.add(locale + " " + key + " has placeholders "
								+ placeholders(t.text) + ", English "
								+ placeholders(en.text));
					}
					if (en.links != t.links) {
						problems.add(locale + " " + key
								+ " has a different number of links");
					}
				} else if (en.type.equals("plurals")) {
					Set<String> allowed = placeholders(
							en.quantities.getOrDefault("other", ""));
					for (Map.Entry<String, String> q :
							t.quantities.entrySet()) {
						if (!allowed.containsAll(placeholders(q.getValue()))) {
							problems.add(locale + " " + key + "/" + q.getKey()
									+ " has placeholders English lacks");
						}
					}
				} else if (en.items.size() != t.items.size()) {
					problems.add(locale + " " + key + " has "
							+ t.items.size() + " items, English "
							+ en.items.size());
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}
}
