package com.professor.zerion.android.util;

import android.text.SpannableStringBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class Utf8ByteLengthFilterTest {

	private final Utf8ByteLengthFilter filter = new Utf8ByteLengthFilter(64);

	@Test
	public void aNameThatFitsIsKeptAsTyped() {
		assertNull(filter.filter("Applicant", 0, 9,
				new SpannableStringBuilder(""), 0, 0));
	}

	@Test
	public void wideCharactersStopAtTheByteLimitNotTheCharacterLimit() {
		StringBuilder chinese = new StringBuilder();
		for (int i = 0; i < 30; i++) chinese.append('\u4e2d');
		CharSequence kept = filter.filter(chinese, 0, chinese.length(),
				new SpannableStringBuilder(""), 0, 0);
		assertEquals(21, kept.length());
		assertEquals(63, Utf8ByteLengthFilter.bytes(kept));
	}

	@Test
	public void aCharacterOutsideTheBasicPlaneIsNeverCutInHalf() {
		String smile = new String(Character.toChars(0x1F600));
		StringBuilder typed = new StringBuilder();
		for (int i = 0; i < 20; i++) typed.append(smile);
		CharSequence kept = filter.filter(typed, 0, typed.length(),
				new SpannableStringBuilder(""), 0, 0);
		assertEquals(64, Utf8ByteLengthFilter.bytes(kept));
		assertEquals(32, kept.length());
	}

	@Test
	public void textAlreadyInTheFieldCountsTowardsTheLimit() {
		StringBuilder held = new StringBuilder();
		for (int i = 0; i < 62; i++) held.append('a');
		CharSequence kept = filter.filter("\u00e9\u00e9", 0, 2,
				new SpannableStringBuilder(held), 62, 62);
		assertEquals(1, kept.length());
	}
}
