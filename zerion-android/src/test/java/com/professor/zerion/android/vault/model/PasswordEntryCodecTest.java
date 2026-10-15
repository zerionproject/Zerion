package com.professor.zerion.android.vault.model;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

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
}
