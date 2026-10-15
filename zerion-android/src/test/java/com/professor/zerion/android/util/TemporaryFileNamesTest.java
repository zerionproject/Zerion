package com.professor.zerion.android.util;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TemporaryFileNamesTest {

	private static final Path SRC = Paths.get("src/main/java");
	private static final Pattern TEMP_FILE =
			Pattern.compile("createTempFile\\(\\s*([^,]+),");
	private static final Pattern LITERAL = Pattern.compile("\"([^\"]*)\"");
	private static final Pattern REMUX_PREFIX = Pattern.compile(
			"\"([^\"]+)\",\\s*(?:true|false|allowWebm)\\)");

	private static String[] sweptPrefixes() throws Exception {
		Field f = CacheSweeper.class.getDeclaredField("TEMP_FILE_PREFIXES");
		f.setAccessible(true);
		return (String[]) f.get(null);
	}

	private static boolean swept(String prefix, String[] swept) {
		for (String s : swept) {
			if (prefix.startsWith(s)) return true;
		}
		return false;
	}

	private static List<Path> sources() throws IOException {
		List<Path> out = new ArrayList<>();
		try (Stream<Path> s = Files.walk(SRC)) {
			s.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
		}
		return out;
	}

	@Test
	public void everyTemporaryFileHasAFixedPrefixThatTheSweepsKnow()
			throws Exception {
		String[] swept = sweptPrefixes();
		int found = 0;
		for (Path p : sources()) {
			String src = new String(Files.readAllBytes(p),
					StandardCharsets.UTF_8);
			Matcher m = TEMP_FILE.matcher(src);
			while (m.find()) {
				found++;
				String arg = m.group(1).trim();
				String where = SRC.relativize(p) + ": " + arg;
				Matcher literal = LITERAL.matcher(arg);
				if (literal.matches()) {
					assertTrue(where + " is not swept",
							swept(literal.group(1), swept));
					continue;
				}
				assertTrue(where + " builds its name from data",
						p.endsWith("MetadataStripper.java")
								&& arg.equals("prefix"));
				Matcher remux = REMUX_PREFIX.matcher(src);
				int calls = 0;
				while (remux.find()) {
					calls++;
					assertTrue(remux.group(1) + " is not swept",
							swept(remux.group(1), swept));
				}
				assertTrue(calls > 0);
			}
		}
		assertTrue(found >= 8);
	}

	@Test
	public void namesAreNeverJoinedFromMessageOrContactData()
			throws Exception {
		Pattern joined = Pattern.compile(
				"(createTempFile|new (java\\.io\\.)?File)\\([^;]*"
						+ "(getBody|getText|getName\\(\\)|contact|author|"
						+ "message|getAuthor|getContactId)[^;]*;",
				Pattern.CASE_INSENSITIVE);
		for (Path p : sources()) {
			String rel = SRC.relativize(p).toString().replace('\\', '/');
			if (!rel.contains("/conversation/") && !rel.contains("/grouptr/")
					&& !rel.contains("/channel/") && !rel.contains("/attachment/")
					&& !rel.contains("/util/")) {
				continue;
			}
			String src = new String(Files.readAllBytes(p),
					StandardCharsets.UTF_8);
			Matcher m = joined.matcher(src);
			if (m.find()) {
				fail(rel + " names a file after message or contact data: "
						+ m.group());
			}
		}
	}
}
