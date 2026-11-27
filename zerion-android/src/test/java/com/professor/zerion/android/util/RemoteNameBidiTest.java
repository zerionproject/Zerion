package com.professor.zerion.android.util;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RemoteNameBidiTest {

	private static final String RLO = "\u202E";
	private static final String LRI = "\u2066";
	private static final String PDI = "\u2069";

	private static Author author(String name) {
		return new Author(new AuthorId(getRandomId()), 1, name,
				new SignaturePublicKey(getRandomBytes(32)));
	}

	@Test
	public void aContactNameLosesItsDirectionControls() {
		assertEquals("Bobexe.txt", UiUtils.getContactDisplayName(
				author("Bob" + RLO + "exe.txt"), null));
		assertEquals("Alice", UiUtils.getContactDisplayName(
				author(LRI + "Alice" + PDI), null));
	}

	@Test
	public void aNameCannotReorderTheAliasAndNameLayout() {
		assertEquals("Mum (Eve)", UiUtils.getContactDisplayName(
				author(RLO + "Eve"), "Mum"));
		assertEquals("Mum (Eve)", UiUtils.getContactDisplayName(
				author(LRI + "Eve" + PDI), "Mum"));
	}

	@Test
	public void ordinaryLettersAndEmojiStay() {
		assertEquals("Zo\u00eb \uD83D\uDE00",
				DisplayNames.clean("Zo\u00eb \uD83D\uDE00"));
		assertEquals("\u05d3\u05e0\u05d4",
				DisplayNames.clean("\u05d3\u05e0\u05d4"));
	}

	@Test
	public void everyInvisibleOrControlCharacterGoes() {
		String hidden = "\u0000\u0007\u200B\u200C\u200D\u200E\u200F\u202A"
				+ "\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\uFEFF"
				+ "\u2028\u2029\uDB80\uDC00\uD800";
		assertEquals("ab", DisplayNames.clean("a" + hidden + "b"));
		assertEquals("", DisplayNames.clean(null));
	}

	@Test
	public void joinedEmojiFlagsAndJoinedLettersStay() {
		String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
		assertEquals(family, DisplayNames.clean(family));
		String scotland = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62"
				+ "\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F";
		assertEquals(scotland, DisplayNames.clean(scotland));
		String persian = "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645";
		assertEquals(persian, DisplayNames.clean(persian));
		assertEquals(family, DisplayNames.clean(RLO + family));
	}

	@Test
	public void aJoinerOrTagOutOfPlaceGoes() {
		assertEquals("ab", DisplayNames.clean("\u200Dab\u200D"));
		assertEquals("a  b", DisplayNames.clean("a \u200C b"));
		assertEquals("ab", DisplayNames.clean("a\uDB40\uDC67b"));
	}

	@Test
	public void lettersTheSystemDoesNotKnowYetAndBmpPrivateUseStay() {
		assertEquals("a\uE000b", DisplayNames.clean("a\uE000b"));
		assertEquals("a\uD83E\uDEFAb", DisplayNames.clean("a\uD83E\uDEFAb"));
	}

	@Test
	public void aNameOfOnlyHiddenCharactersShowsAPlaceholder() {
		assertEquals("?", UiUtils.getContactDisplayName(
				author(RLO + LRI), null));
	}
}
