package com.professor.zerion.android.security;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UncoveredWindowsTest {

	private static final Pattern RAW_BUILDER = Pattern.compile(
			"new\\s+(androidx\\.appcompat\\.app\\.|android\\.app\\.)?"
					+ "AlertDialog\\.Builder\\(|"
					+ "new\\s+(com\\.google\\.android\\.material\\.dialog\\.)?"
					+ "MaterialAlertDialogBuilder\\(");
	private static final Pattern MENU_ITEM = Pattern.compile(
			"getMenu\\(\\)\\s*\\.add\\(([^;]*)\\);");

	private static List<Path> sources() throws IOException {
		List<Path> out = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
			for (Path p : (Iterable<Path>) files::iterator) {
				if (p.toString().endsWith(".java")) out.add(p);
			}
		}
		return out;
	}

	private static String read(Path p) throws IOException {
		return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
	}

	private static String read(String relative) throws IOException {
		return read(Paths.get("src/main/java/com/professor/zerion/android",
				relative));
	}

	@Test
	public void everyAlertDialogUsesTheProtectingBuilder() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path p : sources()) {
			if (p.endsWith("SecureAlertDialogBuilder.java")) continue;
			String s = read(p);
			boolean importsBuilder = s.contains(
					"import androidx.appcompat.app.AlertDialog.Builder;")
					|| s.contains("import android.app.AlertDialog.Builder;");
			if (RAW_BUILDER.matcher(s).find()
					|| (importsBuilder && s.contains("new Builder("))) {
				offenders.add(p.toString());
			}
		}
		assertTrue("alert dialogs without the protecting builder: "
				+ offenders, offenders.isEmpty());
	}

	@Test
	public void everyProgressDialogIsProtectedBeforeItIsShown()
			throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path p : sources()) {
			String s = read(p);
			if (!s.contains("ProgressDialog(")) continue;
			if (!s.contains("SecureDialogs.applyHostPolicy(")
					&& !s.contains("SecureDialogs.protectSecret(")) {
				offenders.add(p.toString());
			}
		}
		assertTrue("progress dialogs without protection: " + offenders,
				offenders.isEmpty());
	}

	@Test
	public void menusListOnlyFixedLabels() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path p : sources()) {
			String s = read(p);
			if (!s.contains("PopupMenu(")) continue;
			Matcher m = MENU_ITEM.matcher(s);
			while (m.find()) {
				if (!m.group(1).contains("R.string.")) {
					offenders.add(p + ": " + m.group(1));
				}
			}
		}
		assertTrue("menu items that are not fixed labels: " + offenders,
				offenders.isEmpty());
	}

	@Test
	public void toastsDoNotNameContactsOrGroups() throws IOException {
		assertFalse("the nearby pairing toast names the contact",
				read("contact/add/nearby/AddNearbyContactFragment.java")
						.contains("R.string.nearby_pairing_success,"));
		assertFalse("the removal toast names the group",
				read("grouptr/GroupTrListFragment.java")
						.contains("R.string.grouptr_removed_by_admin"));
	}

	@Test
	public void errorToastsDoNotCarryExceptionText() throws IOException {
		String s = read("util/UiUtils.java");
		int start = s.indexOf("public static void handleException(");
		int end = s.indexOf("\n\t}", start);
		String body = s.substring(start, end);
		assertFalse("the error toast shows the exception message",
				body.contains("getMessage()"));
		assertFalse("the error toast shows the exception class",
				body.contains("getSimpleName()"));
	}
}
