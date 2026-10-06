package com.professor.zerion.android.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LibraryLoggingSilenceTest {

	@Test
	public void theStandardStreamsDropEverything() {
		PrintStream out = System.out;
		PrintStream err = System.err;
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		PrintStream watched = new PrintStream(sink, true);
		System.setOut(watched);
		System.setErr(watched);
		try {
			SilentStandardStreams.install();
			System.err.println("SLF4J: Failed to load class");
			System.out.println("library chatter");
			new Exception("library trace").printStackTrace();
		} finally {
			System.setOut(out);
			System.setErr(err);
		}
		assertEquals(0, sink.size());
	}

	@Test
	public void theApplicationInstallsItFirst() throws Exception {
		String app = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/"
						+ "ZerionApplicationImpl.java")), StandardCharsets.UTF_8);
		int i = app.indexOf("protected void attachBaseContext(Context base) {");
		String firstLine = app.substring(i, app.indexOf(';', i));
		assertTrue(firstLine.contains("SilentStandardStreams.install()"));
	}

	@Test
	public void releaseStripsLogPrintln() throws Exception {
		String rules = new String(Files.readAllBytes(Paths.get(
				"proguard-rules.txt")), StandardCharsets.UTF_8);
		int i = rules.indexOf("-assumenosideeffects class android.util.Log {");
		String block = rules.substring(i, rules.indexOf("}", i));
		assertTrue(block.contains("public static *** println(...);"));
	}
}
