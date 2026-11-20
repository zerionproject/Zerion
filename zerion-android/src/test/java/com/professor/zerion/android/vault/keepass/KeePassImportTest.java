package com.professor.zerion.android.vault.keepass;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class KeePassImportTest {

	private static KdbxEntry entry(List<String> path, boolean inBin,
			boolean binary, String... keyValues) {
		Map<String, String> strings = new LinkedHashMap<>();
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			strings.put(keyValues[i], keyValues[i + 1]);
		}
		return new KdbxEntry(strings, path, inBin, binary);
	}

	private static KdbxReader.Document document(KdbxEntry... entries) {
		return new KdbxReader.Document(Arrays.asList(entries), 0);
	}

	@Test
	public void folderLineAndCustomFieldsGoIntoNotes() {
		KdbxEntry e = entry(Arrays.asList("Work", "Servers"), false, false,
				"Title", "db01", "UserName", "root", "Password", "pw",
				"URL", "", "Notes", "Postgres", "Port", "5432",
				"otp", "otpauth://totp/a?secret=B");
		KeePassImport.Password p = KeePassImport.map(e, "Untitled");
		assertEquals("db01", p.title);
		assertEquals("Folder: Work / Servers\nPostgres\nPort: 5432\n"
				+ "otp: otpauth://totp/a?secret=B", p.notes);
	}

	@Test
	public void rootEntriesHaveNoFolderLine() {
		KdbxEntry e = entry(Collections.<String>emptyList(), false, false,
				"Title", "Mail", "Password", "pw", "Notes", "  hello  ");
		assertEquals("hello", KeePassImport.map(e, "Untitled").notes);
	}

	@Test
	public void emptyTitleUsesTheUrlHostOrUntitled() {
		KdbxEntry withUrl = entry(Collections.<String>emptyList(), false, false,
				"Title", " ", "UserName", "u", "URL",
				"https://user:pw@login.example.net:8443/path?q=1#f");
		assertEquals("login.example.net",
				KeePassImport.map(withUrl, "Untitled").title);
		KdbxEntry noUrl = entry(Collections.<String>emptyList(), false, false,
				"UserName", "u", "Password", "p");
		assertEquals("Untitled", KeePassImport.map(noUrl, "Untitled").title);
		KdbxEntry bareHost = entry(Collections.<String>emptyList(), false,
				false, "URL", "example.org");
		assertEquals("example.org",
				KeePassImport.map(bareHost, "Untitled").title);
	}

	@Test
	public void entriesWithoutTitleUserPasswordOrUrlAreDropped() {
		KdbxEntry e = entry(Collections.<String>emptyList(), false, true,
				"Title", "", "Notes", "only notes and a file", "Custom", "x");
		assertNull(KeePassImport.map(e, "Untitled"));
	}

	@Test
	public void longTitlesAreCutToTheVaultLimit() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 150; i++) b.append('t');
		KdbxEntry e = entry(Collections.<String>emptyList(), false, false,
				"Title", b.toString(), "Password", "p");
		assertEquals(KeePassImport.MAX_TITLE_LENGTH,
				KeePassImport.map(e, "Untitled").title.length());
	}

	@Test
	public void lineBreaksInSingleLineFieldsAreFlattened() {
		KdbxEntry e = entry(Arrays.asList("A\nB"), false, false,
				"Title", "two\nlines", "UserName", "u\r\nv", "Password",
				"keep\nlines", "URL", "https://x.org/\n");
		KeePassImport.Password p = KeePassImport.map(e, "Untitled");
		assertEquals("two lines", p.title);
		assertEquals("u  v", p.username);
		assertEquals("keep\nlines", p.password);
		assertEquals("https://x.org/", p.url);
		assertEquals("Folder: A B", p.notes);
	}

	@Test
	public void recycleBinDuplicatesAndEmptyEntriesAreCountedAsSkipped() {
		KdbxEntry keep = entry(Collections.<String>emptyList(), false, false,
				"Title", "A", "Password", "1");
		KdbxEntry same = entry(Collections.<String>emptyList(), false, false,
				"Title", "A", "Password", "1");
		KdbxEntry other = entry(Collections.<String>emptyList(), false, false,
				"Title", "A", "Password", "2");
		KdbxEntry binned = entry(Collections.<String>emptyList(), true, false,
				"Title", "B", "Password", "3");
		KdbxEntry empty = entry(Collections.<String>emptyList(), false, false,
				"Title", "", "Notes", "n");
		KdbxReader.Document doc = new KdbxReader.Document(
				Arrays.asList(keep, same, other, binned, empty), 4);
		KeePassImport.Result result = KeePassImport.map(doc, "Untitled");
		assertEquals(2, result.passwords.size());
		assertEquals("1", result.passwords.get(0).password);
		assertEquals("2", result.passwords.get(1).password);
		assertEquals(4 + 1 + 1 + 1, result.skipped);
	}

	@Test
	public void hostExtractionHandlesOddInput() {
		assertEquals("example.org", KeePassImport.host("http://example.org"));
		assertEquals("example.org", KeePassImport.host("example.org:8080/x"));
		assertEquals("[::1]", KeePassImport.host("http://[::1]:80/"));
		assertNull(KeePassImport.host(""));
		assertNull(KeePassImport.host("https://"));
		assertNull(KeePassImport.host("not a url"));
	}

	@Test
	public void emptyDocumentImportsNothing() {
		KeePassImport.Result result = KeePassImport.map(document(), "Untitled");
		assertEquals(0, result.passwords.size());
		assertEquals(0, result.skipped);
	}
}
