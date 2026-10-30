package com.professor.zerion.android.util;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;

public class SecureClipboardOnlyTest {

	@Test
	public void everyCopyGoesThroughSecureClipboard() throws IOException {
		List<String> offenders = new ArrayList<>();
		Path root = Paths.get("src/main/java");
		try (Stream<Path> files = Files.walk(root)) {
			files.filter(p -> p.toString().endsWith(".java"))
					.filter(p -> !p.getFileName().toString()
							.equals("SecureClipboard.java"))
					.forEach(p -> {
						try {
							String src = new String(Files.readAllBytes(p),
									StandardCharsets.UTF_8);
							if (src.contains(".setPrimaryClip(")) {
								offenders.add(root.relativize(p).toString());
							}
						} catch (IOException e) {
							throw new AssertionError(e);
						}
					});
		}
		assertEquals("copies that skip the sensitive flag and the timed "
				+ "clear", new ArrayList<String>(), offenders);
	}
}
