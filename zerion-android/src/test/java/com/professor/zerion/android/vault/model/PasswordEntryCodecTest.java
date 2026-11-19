package com.professor.zerion.android.vault.model;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PasswordEntryCodecTest {

	private static String encode(String t, String u, String p, String url,
			String n) {
		return new String(PasswordEntryCodec.encode(t, u, p, url, n),
				StandardCharsets.UTF_8);
	}

	@Test
	public void fieldsAreWrittenInAFixedOrder() {
		assertEquals("{\"title\":\"t\",\"username\":\"u\",\"password\":"
						+ "\"p\",\"url\":\"l\",\"notes\":\"n\"}",
				encode("t", "u", "p", "l", "n"));
	}

	@Test
	public void missingFieldsAreWrittenEmpty() {
		assertEquals("{\"title\":\"t\",\"username\":\"\",\"password\":"
						+ "\"\",\"url\":\"\",\"notes\":\"\"}",
				encode("t", null, null, null, null));
	}

	@Test
	public void controlCharactersAndSlashesRoundTrip() {
		String odd = "a\\b\nc\rd\te";
		PasswordEntry e = PasswordEntryCodec.decode(
				encode(odd, odd, odd, odd, odd));
		assertEquals(odd, e.title);
		assertEquals(odd, e.username);
		assertEquals(odd, e.password);
		assertEquals(odd, e.url);
		assertEquals(odd, e.notes);
	}

	@Test
	public void quotesAndEscapeLikeTextRoundTrip() {
		String[] values = {"x\"y", "\"", "end\\", "a\\nb", "\\\"", "\\\\n",
				"tab\\t and \\r", "\"title\":\"", "é€🔒"};
		for (String v : values) {
			PasswordEntry e = PasswordEntryCodec.decode(
					encode(v, v, v, v, v));
			assertEquals(v, e.title);
			assertEquals(v, e.username);
			assertEquals(v, e.password);
			assertEquals(v, e.url);
			assertEquals(v, e.notes);
		}
	}

	@Test
	public void aFieldIsReadOnlyUpToItsOwnClosingQuote() {
		PasswordEntry e = PasswordEntryCodec.decode(
				encode("t", "u", "x\"y", "", "z"));
		assertEquals("x\"y", e.password);
		assertEquals("", e.url);
		assertEquals("z", e.notes);
	}

	@Test
	public void anUnterminatedFieldReadsAsEmpty() {
		assertEquals("", PasswordEntryCodec.decode(
				"{\"title\":\"no end").title);
		assertEquals("", PasswordEntryCodec.decode(
				"{\"title\":\"ends in a slash\\").title);
	}

	@Test
	public void anUnknownEscapeIsKeptAsWritten() {
		assertEquals("a\\qb", PasswordEntryCodec.decode(
				"{\"title\":\"a\\qb\"}").title);
	}

	@Test
	public void unknownShapesDecodeToEmptyFields() {
		PasswordEntry e = PasswordEntryCodec.decode("not json");
		assertEquals("", e.title);
		assertEquals("", e.password);
	}

	@Test
	public void aPasswordEntryCarriesNoKind() {
		PasswordEntry e = PasswordEntryCodec.decode(encode("t", "u", "p", "l", "n"));
		assertEquals(PasswordEntry.KIND_PASSWORD, e.kind);
		assertFalse(e.isBookmark());
		assertFalse(PasswordEntryCodec.isBookmark(encode("t", "u", "p", "l", "n")));
	}

	@Test
	public void aBookmarkRoundTripsWithItsKind() {
		String json = new String(PasswordEntryCodec.encodeBookmark("Shop",
				"https://shop.example.org/", "fast delivery"),
				StandardCharsets.UTF_8);
		assertEquals("{\"title\":\"Shop\",\"username\":\"\",\"password\":"
				+ "\"\",\"url\":\"https://shop.example.org/\",\"notes\":"
				+ "\"fast delivery\",\"kind\":\"bookmark\"}", json);
		assertTrue(PasswordEntryCodec.isBookmark(json));
		PasswordEntry e = PasswordEntryCodec.decode(json);
		assertTrue(e.isBookmark());
		assertEquals("Shop", e.title);
		assertEquals("https://shop.example.org/", e.url);
		assertEquals("fast delivery", e.notes);
		assertEquals("", e.username);
		assertEquals("", e.password);
	}

	@Test
	public void anUnknownKindIsKeptButIsNotABookmark() {
		String json = new String(PasswordEntryCodec.encode("totp", "t", "u",
				"p", "l", "n"), StandardCharsets.UTF_8);
		PasswordEntry e = PasswordEntryCodec.decode(json);
		assertEquals("totp", e.kind);
		assertFalse(e.isBookmark());
		assertFalse(PasswordEntryCodec.isBookmark(json));
		assertEquals("p", e.password);
	}

	@Test
	public void aKindWrittenInsideAnotherFieldIsNotRead() {
		String trick = "\",\"kind\":\"bookmark";
		String json = encode("t", "u", "p", "l", trick);
		assertFalse(PasswordEntryCodec.isBookmark(json));
		PasswordEntry e = PasswordEntryCodec.decode(json);
		assertEquals(trick, e.notes);
		assertEquals(PasswordEntry.KIND_PASSWORD, e.kind);
		assertEquals(PasswordEntry.KIND_BOOKMARK,
				PasswordEntry.bookmark("t", "https://a.example", "").kind);
	}
}
