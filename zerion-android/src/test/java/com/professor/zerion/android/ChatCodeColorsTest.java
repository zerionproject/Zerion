package com.professor.zerion.android;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChatCodeColorsTest {

	private static final File SRC =
			new File("src/main/java/com/professor/zerion/android");
	private static final String[] PACKAGES = {"conversation", "grouptr",
			"channel"};
	private static final Pattern LITERAL = Pattern.compile(
			"\\.(setBackgroundColor|setTextColor|setTint|setColorFilter"
					+ "|setColor|setHintTextColor|setLinkTextColor)\\(\\s*"
					+ "(0x[0-9A-Fa-f]{6,8}|Color\\.(WHITE|BLACK))");
	private static final Pattern STATIC_ACCENT = Pattern.compile(
			"R\\.color\\.(zerion_primary_accent|zerion_accent_text"
					+ "|zerion_accent_icon|zerion_cyan|zerion_accent|zerion_primary"
					+ "|color_primary|zerion_text_link)\\b");
	private static final Pattern RESOURCE_COLOR = Pattern.compile(
			"getColor\\([^;]*?R\\.color\\.\\w+");

	private static List<File> sources() {
		List<File> out = new ArrayList<>();
		List<File> stack = new ArrayList<>();
		for (String p : PACKAGES) stack.add(new File(SRC, p));
		while (!stack.isEmpty()) {
			File f = stack.remove(stack.size() - 1);
			File[] list = f.listFiles();
			if (list != null) {
				stack.addAll(Arrays.asList(list));
			} else if (f.getName().endsWith(".java")) {
				out.add(f);
			}
		}
		return out;
	}

	@Test
	public void chatCodeTakesItsColorsFromTheTheme() throws Exception {
		List<String> problems = new ArrayList<>();
		List<File> files = sources();
		assertTrue("scanned " + files.size(), files.size() > 50);
		for (File f : files) {
			String s = new String(Files.readAllBytes(f.toPath()),
					StandardCharsets.UTF_8);
			for (Pattern p : new Pattern[] {LITERAL, STATIC_ACCENT,
					RESOURCE_COLOR}) {
				Matcher m = p.matcher(s);
				while (m.find()) {
					problems.add(f.getName() + ": " + m.group());
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}
}
