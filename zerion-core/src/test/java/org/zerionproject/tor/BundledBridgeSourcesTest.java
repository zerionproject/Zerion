package org.zerionproject.tor;

import org.junit.Test;
import org.zerionproject.tor.CircumventionProvider.BridgeType;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Locale.US;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BundledBridgeSourcesTest {

	private static final String SOURCES =
			"org/zerionproject/tor/bridge-sources.txt";

	private static final String TOR_BROWSER = "pt_config";
	private static final String MOAT = "moat";
	private static final String NOT_BUNDLED = "not-bundled";
	private static final String NO_PUBLIC_SOURCE = "no-public-source";

	private static final String LYREBIRD_BUILD =
			"../packaging/lyrebird-android/build-lyrebird-android.sh";

	private static final int[] LYREBIRD_OLDEST_SHIPPED = {0, 6, 2};

	private static final Map<String, Map<String, int[]>> LYREBIRD_ARGUMENTS =
			new HashMap<>();

	static {
		lyrebirdParses("obfs4", LYREBIRD_OLDEST_SHIPPED,
				"cert", "iat-mode", "node-id", "public-key");
		lyrebirdParses("meek_lite", LYREBIRD_OLDEST_SHIPPED,
				"url", "front", "utls");
		lyrebirdParses("meek_lite", new int[] {0, 8, 0}, "targets");
		lyrebirdParses("snowflake", LYREBIRD_OLDEST_SHIPPED,
				"ampcache", "sqsqueue", "sqscreds", "fronts", "front", "ice",
				"max", "url", "utls-nosni", "utls-imitate", "fingerprint",
				"proxy");
	}

	private final Map<String, List<String>> sections = readSources();

	@Test
	public void everyBundledLineUsesOnlyArgumentsThePinnedLyrebirdParses() {
		int[] pinned = pinnedLyrebirdVersion();
		assertTrue(version(pinned),
				compare(pinned, LYREBIRD_OLDEST_SHIPPED) >= 0);
		int checked = 0;
		for (BridgeType type : BridgeType.values()) {
			for (String cc : countries()) {
				List<String> lines = bundledOrNull(type, cc);
				if (lines == null) continue;
				for (String line : lines) {
					assertEquals(type + " " + cc + ": " + line,
							new ArrayList<String>(),
							lyrebirdProblems(pinned, line));
					checked++;
				}
			}
		}
		assertTrue(checked > 0);
	}

	@Test
	public void meekTargetsNeedLyrebird080() {
		String targets = "meek_lite 192.0.2.20:80 "
				+ "targets=https://a.example.org|b.example.net,"
				+ "https://c.example.org|d.example.net+e.example.net "
				+ "utls=HelloRandomizedALPN";
		assertEquals(new ArrayList<String>(),
				lyrebirdProblems(new int[] {0, 8, 0}, targets));
		assertEquals(new ArrayList<String>(),
				lyrebirdProblems(new int[] {0, 9, 0}, targets));
		List<String> old = lyrebirdProblems(new int[] {0, 7, 0}, targets);
		assertTrue(old.toString(), old.contains(
				"meek_lite argument targets needs lyrebird 0.8.0, pinned 0.7.0"));
		assertTrue(old.toString(), old.contains("meek_lite without url"));
		String url = "meek_lite 192.0.2.20:80 url=https://a.example.org "
				+ "front=b.example.net utls=HelloRandomizedALPN";
		assertEquals(new ArrayList<String>(),
				lyrebirdProblems(LYREBIRD_OLDEST_SHIPPED, url));
	}

	@Test
	public void argumentsLyrebirdIgnoresOrRefusesAreReported() {
		int[] v = {0, 9, 0};
		String fp = "2B280B23E1107BB62ABFC40DDCC8824814F80A72";
		String obfs4 = "obfs4 192.0.2.1:443 " + fp + " ";
		assertTrue(lyrebirdProblems(v, obfs4 + "cert=abc iat-mode=0 colour=blue")
				.contains("obfs4 argument colour is not parsed by lyrebird"));
		assertTrue(lyrebirdProblems(v, obfs4 + "cert=abc")
				.contains("obfs4 without iat-mode"));
		assertTrue(lyrebirdProblems(v, obfs4 + "iat-mode=0")
				.contains("obfs4 without cert"));
		assertEquals(new ArrayList<String>(), lyrebirdProblems(v,
				obfs4 + "node-id=ab public-key=cd iat-mode=0"));
		assertTrue(lyrebirdProblems(v, obfs4 + "cert=a cert=b iat-mode=0")
				.contains("argument cert given twice"));
		assertTrue(lyrebirdProblems(v, obfs4 + "cert iat-mode=0")
				.contains("not a key=value pair: cert"));
		assertTrue(lyrebirdProblems(v, "webtunnel 192.0.2.1:443 " + fp
				+ " url=https://a.example.org")
				.contains("transport webtunnel is not bundled"));
		assertTrue(lyrebirdProblems(v, "snowflake 192.0.2.3 " + fp)
				.contains("not an address and port: 192.0.2.3"));
		assertEquals(new ArrayList<String>(),
				lyrebirdProblems(v, "192.0.2.1:443 " + fp));
		assertTrue(lyrebirdProblems(v, "192.0.2.1:443 " + fp + " cert=a")
				.contains("arguments on a plain bridge"));
	}

	private static void lyrebirdParses(String transport, int[] since,
			String... arguments) {
		Map<String, int[]> m = LYREBIRD_ARGUMENTS.get(transport);
		if (m == null) {
			m = new HashMap<>();
			LYREBIRD_ARGUMENTS.put(transport, m);
		}
		for (String a : arguments) m.put(a, since);
	}

	private static List<String> lyrebirdProblems(int[] pinned, String line) {
		List<String> problems = new ArrayList<>();
		String[] t = line.trim().split(" +");
		boolean vanilla = isAddress(t[0]);
		int i = vanilla ? 1 : 2;
		String transport = t[0];
		if (!vanilla) {
			if (!LYREBIRD_ARGUMENTS.containsKey(transport)) {
				problems.add("transport " + transport + " is not bundled");
				return problems;
			}
			if (t.length < 2 || !isAddress(t[1])) {
				problems.add("not an address and port: "
						+ (t.length < 2 ? "" : t[1]));
				return problems;
			}
		}
		if (i < t.length && t[i].matches("[0-9A-F]{40}")) i++;
		if (vanilla) {
			if (i != t.length) problems.add("arguments on a plain bridge");
			return problems;
		}
		Map<String, int[]> parsed = LYREBIRD_ARGUMENTS.get(transport);
		Set<String> keys = new HashSet<>();
		for (; i < t.length; i++) {
			int eq = t[i].indexOf('=');
			if (eq <= 0) {
				problems.add("not a key=value pair: " + t[i]);
				continue;
			}
			String key = t[i].substring(0, eq);
			if (!keys.add(key)) problems.add("argument " + key + " given twice");
			int[] since = parsed.get(key);
			if (since == null) {
				problems.add(transport + " argument " + key
						+ " is not parsed by lyrebird");
			} else if (compare(pinned, since) < 0) {
				problems.add(transport + " argument " + key + " needs lyrebird "
						+ version(since) + ", pinned " + version(pinned));
			}
		}
		if (transport.equals("meek_lite")) {
			boolean targets = keys.contains("targets")
					&& compare(pinned, parsed.get("targets")) >= 0;
			if (!targets && !keys.contains("url")) {
				problems.add("meek_lite without url");
			}
		}
		if (transport.equals("obfs4")) {
			if (!keys.contains("iat-mode")) problems.add("obfs4 without iat-mode");
			if (!keys.contains("cert") && !(keys.contains("node-id")
					&& keys.contains("public-key"))) {
				problems.add("obfs4 without cert");
			}
		}
		return problems;
	}

	private static boolean isAddress(String s) {
		return s.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}:[0-9]{1,5}")
				|| s.matches("\\[[0-9a-fA-F:]+\\]:[0-9]{1,5}");
	}

	private static int compare(int[] a, int[] b) {
		for (int k = 0; k < 3; k++) {
			if (a[k] != b[k]) return a[k] < b[k] ? -1 : 1;
		}
		return 0;
	}

	private static String version(int[] v) {
		return v[0] + "." + v[1] + "." + v[2];
	}

	private static int[] pinnedLyrebirdVersion() {
		File build = new File(LYREBIRD_BUILD);
		String text;
		try {
			text = new String(Files.readAllBytes(build.toPath()),
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError(build.getAbsolutePath(), e);
		}
		Matcher m = Pattern.compile("^LYREBIRD_TAG="
				+ "(?:lyrebird-)?([0-9]+)\\.([0-9]+)\\.([0-9]+)$",
				Pattern.MULTILINE).matcher(text);
		assertTrue(LYREBIRD_BUILD, m.find());
		int[] v = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
				Integer.parseInt(m.group(3))};
		assertFalse(LYREBIRD_BUILD, m.find());
		return v;
	}

	@Test
	public void theSourceCopyNamesWhereAndWhenItWasTaken() {
		String header = String.join("\n", comments());
		assertTrue(header.contains("https://gitlab.torproject.org/"
				+ "tpo/applications/tor-browser-build/-/raw/"));
		assertTrue(header.contains(
				"/projects/tor-expert-bundle/pt_config.json"));
		assertTrue(header.contains(
				"https://bridges.torproject.org/moat/circumvention/map"));
		assertTrue(header.matches(
				"(?s).*fetched 20[0-9]{2}-[0-9]{2}-[0-9]{2}.*"));
		assertFalse(section(TOR_BROWSER, "obfs4").isEmpty());
		assertFalse(section(TOR_BROWSER, "snowflake").isEmpty());
		assertFalse(section(TOR_BROWSER, "meek").isEmpty());
	}

	@Test
	public void defaultSnowflakeAndMeekAreTheTorBrowserLines() {
		assertEquals(section(TOR_BROWSER, "snowflake"),
				bundled(BridgeType.SNOWFLAKE, "zz"));
		assertEquals(section(TOR_BROWSER, "meek"),
				bundled(BridgeType.MEEK, "zz"));
	}

	@Test
	public void defaultObfs4IsTheTorBrowserListLessTheLinesThatFailed() {
		List<String> official = section(TOR_BROWSER, "obfs4");
		List<String> bundled = bundled(BridgeType.DEFAULT_OBFS4, "zz");
		List<String> left = section(NOT_BUNDLED, "obfs4");
		assertFalse(bundled.isEmpty());
		for (String line : bundled) {
			assertTrue(line, official.contains(line));
			assertFalse(line, left.contains(line));
		}
		for (String line : left) assertTrue(line, official.contains(line));
		Set<String> union = new HashSet<>(bundled);
		union.addAll(left);
		assertEquals(new HashSet<>(official), union);
		List<String> inOrder = new ArrayList<>(official);
		inOrder.retainAll(bundled);
		assertEquals(inOrder, bundled);
	}

	@Test
	public void countrySnowflakeAndMeekAreTheMoatLines() {
		for (String cc : countries()) {
			if (cc.equals("zz")) continue;
			for (BridgeType type : new BridgeType[] {BridgeType.SNOWFLAKE,
					BridgeType.MEEK}) {
				List<String> moat = sections.get(key(MOAT, cc, name(type)));
				List<String> file = bundledOrNull(type, cc);
				if (moat == null) {
					assertNull(type + " " + cc, file);
				} else {
					assertEquals(type + " " + cc, moat, file);
				}
			}
		}
	}

	@Test
	public void linesWithoutAPublicTorProjectSourceAreDeclared() {
		assertEquals(section(NO_PUBLIC_SOURCE, "obfs4"),
				bundled(BridgeType.NON_DEFAULT_OBFS4, "zz"));
		assertEquals(section(NO_PUBLIC_SOURCE, "vanilla"),
				bundled(BridgeType.VANILLA, "zz"));
		Set<String> official = new HashSet<>();
		for (Map.Entry<String, List<String>> e : sections.entrySet()) {
			if (e.getKey().startsWith(TOR_BROWSER + " ")
					|| e.getKey().startsWith(MOAT + " ")) {
				official.addAll(e.getValue());
			}
		}
		for (String t : new String[] {"obfs4", "vanilla"}) {
			for (String line : section(NO_PUBLIC_SOURCE, t)) {
				assertFalse(line, official.contains(line));
			}
		}
	}

	@Test
	public void everyBundledFileIsCoveredByASource() {
		for (BridgeType type : BridgeType.values()) {
			for (String cc : countries()) {
				if (bundledOrNull(type, cc) == null) continue;
				boolean covered = cc.equals("zz")
						|| ((type == BridgeType.SNOWFLAKE
						|| type == BridgeType.MEEK)
						&& sections.containsKey(key(MOAT, cc, name(type))));
				assertTrue(type + " " + cc, covered);
			}
		}
		for (String k : sections.keySet()) {
			String[] parts = k.split(" ");
			assertEquals(k, 3, parts.length);
			assertTrue(k, Arrays.asList(TOR_BROWSER, MOAT, NOT_BUNDLED,
					NO_PUBLIC_SOURCE).contains(parts[0]));
		}
	}

	@Test
	public void everyBundledLineIsATransportLineWithoutStrayCharacters() {
		for (BridgeType type : BridgeType.values()) {
			for (String cc : countries()) {
				List<String> lines = bundledOrNull(type, cc);
				if (lines == null) continue;
				assertFalse(type + " " + cc, lines.isEmpty());
				for (String line : lines) {
					assertEquals(line, line.trim(), line);
					for (int i = 0; i < line.length(); i++) {
						char c = line.charAt(i);
						assertTrue(line, c >= 0x20 && c < 0x7F);
					}
					String first = line.split(" ")[0];
					switch (type) {
						case DEFAULT_OBFS4:
						case NON_DEFAULT_OBFS4:
							assertEquals(line, "obfs4", first);
							break;
						case SNOWFLAKE:
							assertEquals(line, "snowflake", first);
							break;
						case MEEK:
							assertEquals(line, "meek_lite", first);
							break;
						case VANILLA:
							assertTrue(line, first.matches("[0-9.]+:[0-9]+"));
							break;
						default:
							fail(line);
					}
				}
			}
		}
	}

	private static String name(BridgeType type) {
		switch (type) {
			case SNOWFLAKE:
				return "snowflake";
			case MEEK:
				return "meek";
			case VANILLA:
				return "vanilla";
			default:
				return "obfs4";
		}
	}

	private static String key(String source, String scope, String transport) {
		return source + " " + scope + " " + transport;
	}

	private List<String> section(String source, String transport) {
		List<String> lines = sections.get(key(source, "zz", transport));
		return lines == null ? new ArrayList<>() : lines;
	}

	private static Set<String> countries() {
		Set<String> out = new TreeSet<>();
		out.add("zz");
		for (String c : Locale.getISOCountries()) out.add(c.toLowerCase(US));
		for (String[] list : new String[][] {
				CircumventionProvider.COUNTRIES_DEFAULT_OBFS4,
				CircumventionProvider.COUNTRIES_NON_DEFAULT_OBFS4,
				CircumventionProvider.COUNTRIES_VANILLA,
				CircumventionProvider.COUNTRIES_MEEK,
				CircumventionProvider.COUNTRIES_SNOWFLAKE}) {
			for (String c : list) out.add(c.toLowerCase(US));
		}
		return out;
	}

	private static List<String> bundled(BridgeType type, String cc) {
		List<String> lines = bundledOrNull(type, cc);
		assertNotNull(type + " " + cc, lines);
		return lines;
	}

	private static List<String> bundledOrNull(BridgeType type, String cc) {
		String name = "bridges-" + type.letter + "-" + cc;
		InputStream in = BundledBridgeSourcesTest.class.getClassLoader()
				.getResourceAsStream(name);
		if (in == null) return null;
		List<String> lines = new ArrayList<>();
		try (Scanner s = new Scanner(in, "UTF-8")) {
			while (s.hasNextLine()) {
				String line = s.nextLine();
				if (!line.trim().isEmpty()) lines.add(line.trim());
			}
		}
		return lines;
	}

	private static List<String> comments() {
		List<String> out = new ArrayList<>();
		for (String line : rawSources()) {
			if (line.startsWith("#")) out.add(line);
		}
		return out;
	}

	private static Map<String, List<String>> readSources() {
		Map<String, List<String>> out = new LinkedHashMap<>();
		List<String> current = null;
		for (String line : rawSources()) {
			if (line.isEmpty() || line.startsWith("#")) continue;
			if (line.startsWith("[") && line.endsWith("]")) {
				String k = line.substring(1, line.length() - 1);
				assertFalse(k, out.containsKey(k));
				current = new ArrayList<>();
				out.put(k, current);
				continue;
			}
			assertNotNull(line, current);
			assertFalse(line, current.contains(line));
			current.add(line);
		}
		return out;
	}

	private static List<String> rawSources() {
		InputStream in = BundledBridgeSourcesTest.class.getClassLoader()
				.getResourceAsStream(SOURCES);
		assertNotNull(SOURCES, in);
		try {
			byte[] all = readAll(in);
			String text = new String(all, StandardCharsets.UTF_8);
			assertFalse("CR in " + SOURCES, text.contains("\r"));
			List<String> lines = new ArrayList<>();
			for (String l : text.split("\n")) lines.add(l.trim());
			return lines;
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static byte[] readAll(InputStream in) throws IOException {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
			return out.toByteArray();
		} finally {
			in.close();
		}
	}
}
