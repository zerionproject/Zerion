package com.professor.zerion.android.network;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TorStatusMonitorCodeAnalysisTest {

	private static final String SOURCE_FILE_PATH =
			"src/main/java/com/professor/zerion/android/network/TorStatusMonitor.java";

	@Test
	public void testNoUsageOfGetTotalRxBytes() throws IOException {
		String sourceCode = readSourceFile();

		boolean containsForbiddenMethod = sourceCode.contains("getTotalRxBytes");

		assertFalse(
				"TorStatusMonitor should NOT use TrafficStats.getTotalRxBytes() " +
				"as it measures ALL device traffic, not just Zerion's traffic. " +
				"Use getUidRxBytes(appUid) instead.",
				containsForbiddenMethod
		);
	}

	@Test
	public void testNoUsageOfGetTotalTxBytes() throws IOException {
		String sourceCode = readSourceFile();

		boolean containsForbiddenMethod = sourceCode.contains("getTotalTxBytes");

		assertFalse(
				"TorStatusMonitor should NOT use TrafficStats.getTotalTxBytes() " +
				"as it measures ALL device traffic, not just Zerion's traffic. " +
				"Use getUidTxBytes(appUid) instead.",
				containsForbiddenMethod
		);
	}

	@Test
	public void testUsesGetUidRxBytes() throws IOException {
		String sourceCode = readSourceFile();

		boolean containsCorrectMethod = sourceCode.contains("getUidRxBytes");

		assertTrue(
				"TorStatusMonitor MUST use TrafficStats.getUidRxBytes(appUid) " +
				"to measure only Zerion's download traffic.",
				containsCorrectMethod
		);
	}

	@Test
	public void testUsesGetUidTxBytes() throws IOException {
		String sourceCode = readSourceFile();

		boolean containsCorrectMethod = sourceCode.contains("getUidTxBytes");

		assertTrue(
				"TorStatusMonitor MUST use TrafficStats.getUidTxBytes(appUid) " +
				"to measure only Zerion's upload traffic.",
				containsCorrectMethod
		);
	}

	@Test
	public void testAppUidFieldExists() throws IOException {
		String sourceCode = readSourceFile();

		Pattern appUidPattern = Pattern.compile("(private|protected)?\\s*(final)?\\s*int\\s+appUid");
		boolean hasAppUidField = appUidPattern.matcher(sourceCode).find();

		assertTrue(
				"TorStatusMonitor MUST have an appUid field to store the app's UID " +
				"for use with getUidRxBytes/getUidTxBytes.",
				hasAppUidField
		);
	}

	@Test
	public void testAppUidUsesProcessMyUid() throws IOException {
		String sourceCode = readSourceFile();

		boolean usesProcessMyUid = sourceCode.contains("Process.myUid()") ||
				sourceCode.contains("android.os.Process.myUid()");

		assertTrue(
				"TorStatusMonitor MUST use Process.myUid() to get the app's UID.",
				usesProcessMyUid
		);
	}

	@Test
	public void testHandlesUnsupportedTrafficStats() throws IOException {
		String sourceCode = readSourceFile();

		boolean checksUnsupported = sourceCode.contains("UNSUPPORTED") ||
				sourceCode.contains("TrafficStats.UNSUPPORTED");

		assertTrue(
				"TorStatusMonitor MUST check for TrafficStats.UNSUPPORTED " +
				"to handle devices where traffic stats are not available.",
				checksUnsupported
		);
	}

	@Test
	public void testBandwidthNeverNegative() throws IOException {
		String sourceCode = readSourceFile();

		boolean clampsToZero = sourceCode.contains("Math.max(0,") ||
				sourceCode.contains("Math.max( 0,");

		assertTrue(
				"TorStatusMonitor MUST clamp bandwidth values to non-negative " +
				"using Math.max(0, value) to prevent negative rates from being displayed.",
				clampsToZero
		);
	}

	private String readSourceFile() throws IOException {
		File currentDir = new File(System.getProperty("user.dir"));
		File sourceFile = new File(currentDir, SOURCE_FILE_PATH);

		if (!sourceFile.exists()) {
			File parentDir = currentDir.getParentFile();
			if (parentDir != null) {
				sourceFile = new File(parentDir, "zerion-android/" + SOURCE_FILE_PATH);
			}
		}

		if (!sourceFile.exists()) {
			sourceFile = findSourceFile(currentDir);
		}

		if (sourceFile == null || !sourceFile.exists()) {
			fail("Could not find TorStatusMonitor.java source file for analysis");
		}

		StringBuilder content = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new FileReader(sourceFile))) {
			String line;
			while ((line = reader.readLine()) != null) {
				content.append(line).append("\n");
			}
		}
		return content.toString();
	}

	private File findSourceFile(File startDir) {
		File[] files = startDir.listFiles();
		if (files == null) return null;

		for (File file : files) {
			if (file.isDirectory()) {
				if (file.getName().equals("TorStatusMonitor.java")) {
					return file;
				}
				File found = findSourceFile(file);
				if (found != null) return found;
			} else if (file.getName().equals("TorStatusMonitor.java")) {
				return file;
			}
		}
		return null;
	}
}
