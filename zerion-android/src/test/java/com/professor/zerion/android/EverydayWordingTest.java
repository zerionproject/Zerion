package com.professor.zerion.android;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
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
import static org.junit.Assert.assertTrue;

public class EverydayWordingTest {

	private static final File RES = new File("src/main/res");
	private static final File CODE = new File(
			"src/main/java/com/professor/zerion/android");
	private static final List<String> SCREEN_PACKAGES = Arrays.asList(
			"grouptr", "conversation", "channel");
	private static final List<String> KEY_PREFIXES = Arrays.asList(
			"grouptr_", "conversation_", "voice_call_", "channels_",
			"channel_");

	private static final List<String> TECHNICAL_WORDS = Arrays.asList(
			"onion", "epoch", "manifest", "publisher", "\\bkeys?\\b",
			"\\bhex\\b", "\\btor\\b", "i2p");

	private static final Map<String, String> ADVANCED_SCREEN_KEYS =
			new LinkedHashMap<>();

	static {
		ADVANCED_SCREEN_KEYS.put("identity_fingerprint_hybrid_format",
				"The safety code dialog repeats the identity lines Settings"
						+ " shows under My fingerprint, so the two can be compared.");
		ADVANCED_SCREEN_KEYS.put("identity_fingerprint_classical_format",
				"The safety code dialog repeats the identity lines Settings"
						+ " shows under My fingerprint, so the two can be compared.");
	}

	private static final Map<String, String> ADVANCED_SCREEN_FILES =
			new LinkedHashMap<>();

	static {
		ADVANCED_SCREEN_FILES.put("ChatSettingsActivity.java",
				"Identity verification lives in the chat's settings, the"
						+ " place the owner allows technical detail.");
		ADVANCED_SCREEN_FILES.put("ContactConnectionLines.java",
				"The per-contact Connections sheet is a diagnostics screen"
						+ " and may name the transport.");
	}

	private static final Map<String, String> HEX_FOR_STORAGE_METHODS =
			new LinkedHashMap<>();

	static {
		HEX_FOR_STORAGE_METHODS.put("ChannelCommentsActivity#commentDraftKey",
				"Hex names a draft preference, it is never shown.");
		HEX_FOR_STORAGE_METHODS.put("ChannelFeedActivity#thumbnailKey",
				"Hex names a thumbnail cache entry, it is never shown.");
		HEX_FOR_STORAGE_METHODS.put("ChannelFeedActivity#bytesToHex",
				"Hex names attachment cache entries, it is never shown.");
		HEX_FOR_STORAGE_METHODS.put("GroupTrMediaCache#key",
				"Hex names media cache entries, it is never shown.");
		HEX_FOR_STORAGE_METHODS.put("MediaViewer#newToken",
				"Hex names a staged media file, it is never shown.");
		HEX_FOR_STORAGE_METHODS.put("CallHex#bytesToHex",
				"Hex encodes call identifiers for the wire, it is never"
						+ " shown.");
	}

	private static final List<String> UI_SINKS = Arrays.asList(
			"setText(", "setTitle(", "setMessage(", "setSubtitle(",
			"setContentDescription(", "setHint(", "getString(",
			"getQuantityString(", "Toast.makeText(", ".line(", ".titled(",
			"setPlaceholderText(", "setError(");

	private static final List<String> RETIRED_KEYS = Arrays.asList(
			"grouptr_member_key_message",
			"grouptr_stealth_name_msg",
			"grouptr_incognito_summary",
			"conversation_subtitle_online_tor",
			"conversation_subtitle_online_i2p",
			"conversation_subtitle_online_both",
			"voice_call_status_connecting_tor",
			"voice_call_status_video_connecting_tor",
			"voice_call_video_no_tor",
			"channels_comment_anonymous",
			"channels_comment_author_since");

	private static Map<String, String> englishTexts() throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		File[] files = new File(RES, "values")
				.listFiles((d, n) -> n.endsWith(".xml"));
		assertTrue(files != null && files.length > 0);
		for (File f : files) {
			Element root = DocumentBuilderFactory.newInstance()
					.newDocumentBuilder().parse(f).getDocumentElement();
			NodeList nodes = root.getChildNodes();
			for (int i = 0; i < nodes.getLength(); i++) {
				Node n = nodes.item(i);
				if (!(n instanceof Element)) continue;
				Element e = (Element) n;
				String tag = e.getTagName();
				if (tag.equals("string") || tag.equals("plurals")) {
					out.put(e.getAttribute("name"), e.getTextContent());
				}
			}
		}
		return out;
	}

	private static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()),
				StandardCharsets.UTF_8);
	}

	private static void collectJava(File dir, List<File> out) {
		File[] children = dir.listFiles();
		if (children == null) return;
		for (File c : children) {
			if (c.isDirectory()) collectJava(c, out);
			else if (c.getName().endsWith(".java")) out.add(c);
		}
	}

	private static List<File> screenSources() {
		List<File> out = new ArrayList<>();
		for (String pkg : SCREEN_PACKAGES) {
			collectJava(new File(CODE, pkg), out);
		}
		assertTrue(out.size() > 10);
		return out;
	}

	static Set<String> keysShownOnScreens(List<File> sources,
			Map<String, String> english) throws Exception {
		Set<String> keys = new TreeSet<>();
		Pattern ref = Pattern.compile("R\\.(?:string|plurals)\\.(\\w+)");
		for (File f : sources) {
			if (ADVANCED_SCREEN_FILES.containsKey(f.getName())) continue;
			Matcher m = ref.matcher(read(f));
			while (m.find()) keys.add(m.group(1));
		}
		for (String key : english.keySet()) {
			for (String prefix : KEY_PREFIXES) {
				if (key.startsWith(prefix)) keys.add(key);
			}
		}
		return keys;
	}

	static List<String> problemsIn(String key, String text,
			List<String> words) {
		List<String> problems = new ArrayList<>();
		String lower = text.replace("\\", "").toLowerCase(Locale.ROOT);
		for (String word : words) {
			if (Pattern.compile(word).matcher(lower).find()) {
				problems.add(key + " says \"" + word + "\": " + text);
			}
		}
		return problems;
	}

	@Test
	public void groupChatAndChannelScreensUseEverydayWords()
			throws Exception {
		Map<String, String> english = englishTexts();
		List<String> problems = new ArrayList<>();
		for (String key : keysShownOnScreens(screenSources(), english)) {
			if (ADVANCED_SCREEN_KEYS.containsKey(key)) continue;
			String text = english.get(key);
			if (text != null) {
				problems.addAll(problemsIn(key, text, TECHNICAL_WORDS));
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theChatSubtitleNamesNoTransport() throws Exception {
		Map<String, String> english = englishTexts();
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, String> e : english.entrySet()) {
			if (!e.getKey().startsWith("conversation_subtitle_")
					&& !e.getKey().startsWith("voice_call_")) {
				continue;
			}
			problems.addAll(problemsIn(e.getKey(), e.getValue(),
					Arrays.asList("\\btor\\b", "i2p")));
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
		assertEquals("Online · End-to-end encrypted",
				english.get("conversation_subtitle_online"));
		assertEquals("Video needs an online connection",
				english.get("voice_call_video_no_connection"));
	}

	private static final Pattern METHOD_HEAD = Pattern.compile(
			"^\\t+(?:(?:public|private|protected|static|final|synchronized)"
					+ "\\s+)*[\\w<>\\[\\],.? ]+\\s+(\\w+)\\s*\\([^;=]*$");

	static String enclosingMethod(List<String> lines, int index) {
		for (int i = index; i >= 0; i--) {
			String line = lines.get(i);
			if (line.contains(" new ") || line.contains("return ")) continue;
			Matcher m = METHOD_HEAD.matcher(line);
			if (m.find()) {
				String name = m.group(1);
				if (!Arrays.asList("if", "for", "while", "switch", "catch",
						"synchronized").contains(name)) {
					return name;
				}
			}
		}
		return "?";
	}

	static List<String> hexProblems(String fileName, String source) {
		List<String> problems = new ArrayList<>();
		List<String> lines = Arrays.asList(source.split("\n", -1));
		String base = fileName.replace(".java", "");
		for (int i = 0; i < lines.size(); i++) {
			if (!lines.get(i).contains("%02x")) continue;
			String where = base + "#" + enclosingMethod(lines, i);
			if (!HEX_FOR_STORAGE_METHODS.containsKey(where)) {
				problems.add(where + " formats bytes as hex outside a"
						+ " storage helper");
			}
		}
		for (String statement : source.split(";")) {
			int hex = statement.indexOf("toHexString(");
			int format = statement.indexOf("%02x");
			if (hex < 0 || (format >= 0 && format < hex)) hex = format;
			if (hex < 0) continue;
			for (String sink : UI_SINKS) {
				int at = statement.indexOf(sink);
				if (at >= 0 && at < hex) {
					problems.add(base + " hands hex to " + sink + ": "
							+ statement.trim().replaceAll("\\s+", " "));
				}
			}
		}
		return problems;
	}

	@Test
	public void noScreenBuildsAHexLabel() throws Exception {
		List<String> problems = new ArrayList<>();
		for (File f : screenSources()) {
			problems.addAll(hexProblems(f.getName(), read(f)));
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theHexCheckSeesThroughAHelperAndAUiSink() {
		String helper = "class Probe {\n"
				+ "\tprivate static String fingerprint(byte[] key) {\n"
				+ "\t\tStringBuilder sb = new StringBuilder();\n"
				+ "\t\tsb.append(String.format(Locale.US, \"%02x\", key[0]));\n"
				+ "\t\treturn sb.toString();\n"
				+ "\t}\n"
				+ "}\n";
		List<String> problems = hexProblems("ChannelCommentsActivity.java",
				helper);
		assertEquals(String.join("\n", problems), 1, problems.size());
		assertTrue(problems.get(0),
				problems.get(0).startsWith("ChannelCommentsActivity#fingerprint"));
		String sink = "class Probe {\n"
				+ "\tvoid bind(TextView name, byte[] key) {\n"
				+ "\t\tname.setText(StringUtils.toHexString(key)\n"
				+ "\t\t\t\t.substring(0, 16));\n"
				+ "\t}\n"
				+ "}\n";
		problems = hexProblems("GroupTrAdminActivity.java", sink);
		assertEquals(String.join("\n", problems), 1, problems.size());
		assertTrue(problems.get(0), problems.get(0).contains("setText("));
		String allowed = "class Probe {\n"
				+ "\tprivate String commentDraftKey() {\n"
				+ "\t\tStringBuilder sb = new StringBuilder();\n"
				+ "\t\tfor (byte b : id) sb.append(String.format(\n"
				+ "\t\t\t\tLocale.US, \"%02x\", b & 0xFF));\n"
				+ "\t\treturn sb.toString();\n"
				+ "\t}\n"
				+ "}\n";
		assertEquals(0, hexProblems("ChannelCommentsActivity.java",
				allowed).size());
		String mapKey = "class Probe {\n"
				+ "\tvoid remember(byte[] key, String name) {\n"
				+ "\t\tnames.put(StringUtils.toHexString(key), name);\n"
				+ "\t}\n"
				+ "}\n";
		assertEquals(0, hexProblems("GroupTrConversationActivity.java",
				mapKey).size());
	}

	@Test
	public void theAllowlistsNameRealFilesKeysAndMethods() throws Exception {
		Map<String, String> english = englishTexts();
		for (Map.Entry<String, String> e : ADVANCED_SCREEN_KEYS.entrySet()) {
			assertTrue(e.getKey(), english.containsKey(e.getKey()));
			assertTrue(e.getKey(), e.getValue().length() > 20);
		}
		Map<String, String> sources = new LinkedHashMap<>();
		for (File f : screenSources()) sources.put(f.getName(), read(f));
		for (Map.Entry<String, String> e : ADVANCED_SCREEN_FILES.entrySet()) {
			assertTrue(e.getKey(), sources.containsKey(e.getKey()));
			assertTrue(e.getKey(), e.getValue().length() > 20);
		}
		for (Map.Entry<String, String> e : HEX_FOR_STORAGE_METHODS.entrySet()) {
			String[] parts = e.getKey().split("#");
			String source = sources.get(parts[0] + ".java");
			assertTrue(e.getKey(), source != null);
			List<String> lines = Arrays.asList(source.split("\n", -1));
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).contains("%02x")
						&& parts[1].equals(enclosingMethod(lines, i))) {
					found = true;
				}
			}
			assertTrue(e.getKey() + " no longer formats hex", found);
			assertTrue(e.getKey(), e.getValue().length() > 20);
		}
	}

	@Test
	public void retiredStringsAreGoneFromEveryLanguage() throws Exception {
		File[] folders = RES.listFiles((d, n) -> n.startsWith("values"));
		assertTrue(folders != null && folders.length >= 40);
		List<String> problems = new ArrayList<>();
		for (File folder : folders) {
			if (folder.getName().equals("values-iw")) continue;
			File strings = new File(folder, "strings.xml");
			if (!strings.isFile()) continue;
			String text = read(strings);
			for (String key : RETIRED_KEYS) {
				if (text.contains("name=\"" + key + "\"")) {
					problems.add(folder.getName() + " still has " + key);
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}
}
