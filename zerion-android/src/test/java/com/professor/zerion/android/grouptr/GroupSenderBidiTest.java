package com.professor.zerion.android.grouptr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GroupSenderBidiTest {

	private static final String CHOSEN = "%1$s (calls themselves %2$s)";
	private static final String UNVERIFIED = "%1$s (not verified)";

	@Test
	public void aMembersChosenNameLosesItsDirectionControls() {
		assertEquals("Eve (not verified)", GroupTrSenderLabel.label(null,
				"\u202EEve", CHOSEN, UNVERIFIED));
		assertEquals("Ann (calls themselves Bob)", GroupTrSenderLabel.label(
				"Ann", "\u2066Bob\u2069", CHOSEN, UNVERIFIED));
	}

	@Test
	public void aKnownNameLosesItsDirectionControls() {
		assertEquals("Ann", GroupTrSenderLabel.label("\u202EAnn", "Ann",
				CHOSEN, UNVERIFIED));
	}
}
