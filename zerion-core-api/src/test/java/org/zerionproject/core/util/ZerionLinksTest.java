package org.zerionproject.core.util;

import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ZerionLinksTest {

	private static final String REST = "://channel/AbC2/dEf7?k=Xy&o=ab";

	@Test
	public void everyAsciiCaseOfTheSchemeBecomesLowercase() {
		for (int mask = 0; mask < 64; mask++) {
			StringBuilder scheme = new StringBuilder();
			for (int i = 0; i < 6; i++) {
				char c = "zerion".charAt(i);
				scheme.append((mask & (1 << i)) != 0
						? Character.toUpperCase(c) : c);
			}
			String link = scheme + REST;
			assertTrue(link, ZerionLinks.hasZerionScheme(link));
			assertEquals(link, "zerion" + REST,
					ZerionLinks.normalizeScheme(link));
		}
	}

	@Test
	public void theSchemePatternMatchesExactlyTheAsciiCasesOfTheScheme() {
		Pattern scheme = Pattern.compile(ZerionLinks.SCHEME_PATTERN);
		int matched = 0;
		for (int mask = 0; mask < 64; mask++) {
			StringBuilder s = new StringBuilder();
			for (int i = 0; i < 6; i++) {
				char c = "zerion".charAt(i);
				s.append((mask & (1 << i)) != 0
						? Character.toUpperCase(c) : c);
			}
			if (scheme.matcher(s).matches()) matched++;
		}
		assertEquals(64, matched);
		String[] lookalikes = {
				"zer\u0131on", "ZER\u0130ON", "\uff5aerion", "zerio",
				"zerion\u200b", "zerionx", "ZERIO\u039d"
		};
		for (String other : lookalikes) {
			assertFalse(other, scheme.matcher(other).matches());
		}
	}

	@Test
	public void theSchemePatternUsesOnlyAsciiLetterClassesAndNoCaseFlag() {
		assertEquals("[zZ][eE][rR][iI][oO][nN]", ZerionLinks.SCHEME_PATTERN);
	}

	@Test
	public void nothingAfterTheSchemeChanges() {
		assertEquals("zerion://CHANNEL/AbC?K=Q",
				ZerionLinks.normalizeScheme("ZERION://CHANNEL/AbC?K=Q"));
		assertEquals("zerion://aBcD",
				ZerionLinks.normalizeScheme("Zerion://aBcD"));
	}

	@Test
	public void aLowercaseLinkIsReturnedAsItIs() {
		String link = "zerion" + REST;
		assertSame(link, ZerionLinks.normalizeScheme(link));
	}

	@Test
	public void otherSchemesAndLookalikesAreLeftAlone() {
		String[] others = {
				"briar" + REST,
				"zerio" + REST,
				"zerionx" + REST,
				"zerion:/channel/x",
				"zerion//channel/x",
				" zerion" + REST,
				"zer\u0131on" + REST,
				"ZER\u0130ON" + REST,
				"\uff5aerion" + REST,
				"zerion\u200b" + REST,
				"",
				"zerion",
				"ZERION:/"
		};
		for (String other : others) {
			assertFalse(other, ZerionLinks.hasZerionScheme(other));
			assertSame(other, other, ZerionLinks.normalizeScheme(other));
		}
	}
}
