package com.professor.zerion.android;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SourceBidiControlTest {

	private static boolean isBidiControl(int cp) {
		return (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)
				|| cp == 0x200E || cp == 0x200F;
	}

	private static boolean scanned(Path p) {
		String name = p.getFileName().toString();
		if (!name.endsWith(".java") && !name.endsWith(".kt")
				&& !name.endsWith(".xml")) {
			return false;
		}
		for (Path part : p) {
			String s = part.toString();
			if (s.equals("build") || s.equals(".gradle")) return false;
		}
		return true;
	}

	@Test
	public void noSourceFileContainsALiteralDirectionMark() throws IOException {
		File root = new File("..").getCanonicalFile();
		File[] modules = root.listFiles(f -> f.isDirectory()
				&& f.getName().startsWith("zerion-")
				&& new File(f, "src").isDirectory());
		assertTrue(modules != null && modules.length >= 5);
		List<String> problems = new ArrayList<>();
		int files = 0;
		for (File module : modules) {
			List<Path> paths;
			try (Stream<Path> walk = Files.walk(new File(module, "src").toPath())) {
				paths = new ArrayList<>();
				walk.filter(Files::isRegularFile).filter(
						SourceBidiControlTest::scanned).forEach(paths::add);
			}
			for (Path p : paths) {
				files++;
				String text = new String(Files.readAllBytes(p),
						StandardCharsets.UTF_8);
				int line = 1;
				for (int i = 0; i < text.length(); ) {
					int cp = text.codePointAt(i);
					if (cp == '\n') line++;
					if (isBidiControl(cp)) {
						problems.add(root.toPath().relativize(p) + ":" + line
								+ " has U+" + Integer.toHexString(cp)
								.toUpperCase(java.util.Locale.ROOT));
					}
					i += Character.charCount(cp);
				}
			}
		}
		assertTrue("scanned only " + files + " files", files > 1000);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}
}
