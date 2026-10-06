package com.professor.zerion.android.vault.ui;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

public class WebEngineAbsenceTest {

	@Test
	public void noScreenOfTheAppUsesAWebEngine() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main"))) {
			for (Path p : (Iterable<Path>) files::iterator) {
				String n = p.toString();
				if (!n.endsWith(".java") && !n.endsWith(".xml")) continue;
				String s = new String(Files.readAllBytes(p),
						StandardCharsets.UTF_8);
				if (s.contains("<WebView")
						|| s.contains("android.webkit.WebView;")
						|| s.contains("new WebView(")) {
					offenders.add(n);
				}
			}
		}
		assertTrue("web engine used in: " + offenders, offenders.isEmpty());
	}
}
