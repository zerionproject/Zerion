package com.professor.zerion.android.util;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SafeFileNamesTest {

	private static String clean(String raw) {
		return SafeFileNames.sanitize(raw, "file");
	}

	private static void assertSafe(String raw) throws Exception {
		String safe = clean(raw);
		assertFalse(raw, safe.isEmpty());
		assertFalse(raw, safe.contains("/") || safe.contains("\\"));
		assertFalse(raw, safe.contains(".."));
		assertFalse(raw, safe.startsWith(".") || safe.startsWith("-"));
		assertTrue(raw + " -> " + safe, safe.codePointCount(0, safe.length())
				<= SafeFileNames.MAX_LENGTH);
		assertTrue(raw, safe.getBytes(StandardCharsets.UTF_8).length
				<= SafeFileNames.MAX_UTF8_BYTES);
		for (int i = 0; i < safe.length(); ) {
			int cp = safe.codePointAt(i);
			int type = Character.getType(cp);
			assertFalse(raw + " keeps U+" + Integer.toHexString(cp),
					type == Character.CONTROL || type == Character.FORMAT
							|| type == Character.SURROGATE
							|| type == Character.PRIVATE_USE);
			assertFalse(raw, "<>:\"|?*".indexOf(cp) >= 0);
			i += Character.charCount(cp);
		}
		File dir = new File("saved").getCanonicalFile();
		assertEquals(raw, dir, new File(dir, safe).getCanonicalFile()
				.getParentFile());
	}

	@Test
	public void directoriesOfEitherKindAreDropped() throws Exception {
		assertEquals("passwd", clean("../../etc/passwd"));
		assertEquals("cmd.exe", clean("..\\..\\Windows\\System32\\cmd.exe"));
		assertEquals("IMG_20261018_104833.jpg",
				clean("/storage/emulated/0/DCIM/Camera/IMG_20261018_104833.jpg"));
		assertEquals("x", clean("a/../../b\\..\\x"));
		assertEquals("file", clean("../"));
		assertEquals("file", clean("..\\"));
		assertEquals("C_secret.pdf", clean("C:secret.pdf"));
		for (String raw : new String[] {"../../etc/passwd", "..", ".",
				"/data/data/pkg/files/x", "a/../../b", "C:\\temp\\x",
				"..\\..\\x", "\\\\server\\share\\x.txt", "x/..",
				"\uFF0E\uFF0E\uFF0Fx", "..\u2215..\u2215x", "a\uFF3Cb"}) {
			assertSafe(raw);
		}
	}

	@Test
	public void directionAndInvisibleCharactersAreRemoved() throws Exception {
		assertEquals("invoicefdp.exe", clean("invoice\u202Efdp.exe"));
		assertEquals("passport.pdf", clean("pass\u200Bport\u200D.pdf"));
		assertEquals("report.pdf", clean("\uFEFFre\u2066port\u2069.pdf"));
		assertEquals("abc.txt", clean("a\u0000b\u0007c\u007F.txt"));
		assertEquals("a b.txt", clean("a\nb\t.txt"));
		assertEquals("a b.txt", clean("a\u00A0\u3000b.txt"));
		assertEquals("ab.txt", clean("a\u00ADb\u061C\u180E.txt"));
		assertEquals("ab", clean("a\uE000b\uDB80"));
		assertEquals("ab.png", clean("a\uFE0F\u034Fb\u3164.png"));
		assertEquals("file", clean("\u202E\u200B\u2066"));
		for (String raw : new String[] {"invoice\u202Efdp.exe",
				"\u202Dsecret\u202C", "x\uDB40\uDC01y", "\u0085\u2028\u2029z"}) {
			assertSafe(raw);
		}
	}

	@Test
	public void lettersAreKeptInTheirComposedForm() {
		assertEquals("caf\u00E9.txt", clean("cafe\u0301.txt"));
		assertEquals("\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.pdf",
				clean("\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.pdf"));
		assertEquals("\u5199\u771F 1.jpg", clean("\u5199\u771F 1.jpg"));
	}

	@Test
	public void reservedCharactersBecomeUnderscores() {
		assertEquals("a_b_c_d_e_f_g_h.txt", clean("a<b>c:d\"e|f?g*h.txt"));
		assertEquals("a_b.txt", clean("a<>b.txt"));
		assertEquals("a_b", clean("a\uFF0Fb"));
	}

	@Test
	public void noNameIsHiddenStartsWithADashOrIsOnlyDots() throws Exception {
		assertEquals("hidden", clean(".hidden"));
		assertEquals("rf.txt", clean("-rf.txt"));
		assertEquals("help", clean("--help"));
		assertEquals("x", clean(" - . -x"));
		assertEquals("a.b.txt", clean("a...b....txt"));
		assertEquals("report.pdf", clean("report.pdf. . ."));
		for (String raw : new String[] {"", " ", ".", "..", "...", ". .",
				"....", "-", "--", "  .  "}) {
			assertEquals(raw, "file", clean(raw));
			assertSafe(raw);
		}
		assertEquals("file", SafeFileNames.sanitize(null, "file"));
	}

	@Test
	public void reservedWindowsNamesAreNotUsedAsTheyAre() {
		assertEquals("_CON", clean("CON"));
		assertEquals("_con.txt", clean("con.txt"));
		assertEquals("_LPT1.pdf", clean("LPT1.pdf"));
		assertEquals("_Aux", clean("Aux"));
		assertEquals("_nul.tar.gz", clean("nul.tar.gz"));
		assertEquals("_COM9.jpg", clean("COM9 .jpg"));
		assertEquals("console.txt", clean("console.txt"));
	}

	@Test
	public void superscriptDeviceNamesAreReservedToo() {
		assertEquals("_COM\u00B9", clean("COM\u00B9"));
		assertEquals("_com\u00B2.txt", clean("com\u00B2.txt"));
		assertEquals("_COM\u00B3.pdf", clean("COM\u00B3.pdf"));
		assertEquals("_LPT\u00B9", clean("LPT\u00B9"));
		assertEquals("_lpt\u00B2.jpg", clean("lpt\u00B2.jpg"));
		assertEquals("_LPT\u00B3.png", clean("LPT\u00B3.png"));
		assertEquals("COM\u2074.txt", clean("COM\u2074.txt"));
	}

	@Test
	public void aJoinerStaysOnlyBetweenTwoEmoji() {
		String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
		assertEquals("a family emoji keeps its joiners", family + ".png",
				clean(family + ".png"));
		String coder = "\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB";
		assertEquals(coder + " 2.jpg", clean(coder + " 2.jpg"));
		assertEquals("\u2764\u200D\uD83D\uDD25",
				clean("\u2764\uFE0F\u200D\uD83D\uDD25"));
		assertEquals("ab.txt", clean("a\u200Db.txt"));
		assertEquals("\uD83D\uDE00a", clean("\uD83D\uDE00\u200Da"));
		assertEquals("a\uD83D\uDE00", clean("a\u200D\uD83D\uDE00"));
		assertEquals("\uD83D\uDE00.png", clean("\uD83D\uDE00\u200D.png"));
		assertEquals("\uD83D\uDE00\u200D\uD83D\uDE00",
				clean("\uD83D\uDE00\u200D\u200D\uD83D\uDE00"));
		assertEquals("x", clean("\u200Dx"));
		StringBuilder many = new StringBuilder();
		for (int i = 0; i < 40; i++) many.append("\uD83D\uDC68\u200D");
		String cut = clean(many + "\uD83D\uDC68.png");
		assertTrue(cut, cut.endsWith(".png"));
		assertFalse("a cut leaves no joiner at the end",
				cut.endsWith("\u200D.png"));
	}

	@Test
	public void aJoinerBetweenSymbolsThatAreNoEmojiGoes() {
		assertEquals("\u00A9\u00AE.txt", clean("\u00A9\u200D\u00AE.txt"));
		assertEquals("\u2318\u2325", clean("\u2318\u200D\u2325"));
		assertEquals("\u2122x", clean("\u2122\u200Dx"));
		assertEquals("\uD83C\uDFC3\u200D\u2640",
				clean("\uD83C\uDFC3\u200D\u2640\uFE0F"));
	}

	@Test
	public void aLongNameIsCutButKeepsItsExtension() throws Exception {
		StringBuilder raw = new StringBuilder();
		for (int i = 0; i < 300; i++) raw.append('a');
		String safe = clean(raw + ".pdf");
		assertEquals(SafeFileNames.MAX_LENGTH, safe.length());
		assertTrue(safe, safe.endsWith(".pdf"));
		assertSafe(raw + ".pdf");

		StringBuilder emoji = new StringBuilder();
		for (int i = 0; i < 100; i++) emoji.append("\uD83D\uDE00");
		String cut = clean(emoji + ".png");
		assertTrue(cut, cut.endsWith(".png"));
		assertFalse("a pair is split", Character.isHighSurrogate(
				cut.charAt(cut.length() - 5)));
		assertSafe(emoji + ".png");

		StringBuilder dots = new StringBuilder();
		for (int i = 0; i < 59; i++) dots.append('b');
		assertEquals(dots + ".pdf", clean(dots + ". . . . . . . .pdf"));
	}

	@Test
	public void anExtensionThatIsNotPlainStaysPartOfTheName() {
		assertEquals("notes.t_t", clean("notes.t*t"));
		assertEquals("photo.JPEG2000x", clean("photo.JPEG2000x"));
		assertEquals("photo.jpg", clean("photo.JPG"));
	}

	@Test
	public void theFallbackIsCleanedToo() {
		assertEquals("x", SafeFileNames.sanitize("..", "../x"));
		assertEquals("file", SafeFileNames.sanitize("..", "\u202E"));
	}

	@Test
	public void aSavedFileIsNamedByItsKindAndTheDay() {
		long now = 1_760_000_000_000L;
		String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
				.format(new Date(now));
		assertEquals("PDF document " + stamp + ".pdf",
				SavedFileNames.named("PDF document", "application/pdf", now));
		assertEquals("Image " + stamp + ".jpg",
				SavedFileNames.named("Image", "image/jpeg", now));
		assertEquals(stamp + ".txt",
				SavedFileNames.named("", "text/plain", now));
		assertEquals("i_nd " + stamp,
				SavedFileNames.named("\u202Ek/i:n\u200Bd", "", now));
		StringBuilder longKind = new StringBuilder();
		for (int i = 0; i < 80; i++) longKind.append('k');
		String named = SavedFileNames.named(longKind.toString(),
				"application/pdf", now);
		assertTrue(named, named.endsWith(" " + stamp + ".pdf"));
		assertTrue(named, named.length() <= SafeFileNames.MAX_LENGTH);
	}
}
