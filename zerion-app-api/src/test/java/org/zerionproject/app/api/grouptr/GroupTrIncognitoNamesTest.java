package org.zerionproject.app.api.grouptr;

import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupTrIncognitoNamesTest extends BrambleTestCase {

	@Test
	public void everyGeneratedNameIsRecognised() {
		Random random = new SecureRandom();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			String name = GroupTrIncognitoNames.generate(random);
			assertTrue(name, GroupTrIncognitoNames.isIncognitoName(name));
			assertTrue(name, name.length() <= 50);
			seen.add(name);
		}
		assertTrue("names repeat too often: " + seen.size(),
				seen.size() > 1900);
	}

	@Test
	public void theWordListsHoldDistinctReadableWords() {
		assertEquals(64, GroupTrIncognitoNames.ADJECTIVES.size());
		assertEquals(64, GroupTrIncognitoNames.NOUNS.size());
		Set<String> all = new HashSet<>(GroupTrIncognitoNames.ADJECTIVES);
		all.addAll(GroupTrIncognitoNames.NOUNS);
		assertEquals(128, all.size());
		for (String w : all) {
			assertTrue(w, w.matches("[A-Z][a-z]{1,9}"));
		}
	}

	@Test
	public void otherNamesAreNotIncognitoNames() {
		assertTrue(GroupTrIncognitoNames.isIncognitoName("Amber Falcon 27"));
		assertTrue(GroupTrIncognitoNames.isIncognitoName("Wise Lagoon 10"));
		assertTrue(GroupTrIncognitoNames.isIncognitoName("Wise Lagoon 99"));
		assertFalse(GroupTrIncognitoNames.isIncognitoName(null));
		assertFalse(GroupTrIncognitoNames.isIncognitoName(""));
		assertFalse(GroupTrIncognitoNames.isIncognitoName("Alice"));
		assertFalse(GroupTrIncognitoNames.isIncognitoName("amber Falcon 27"));
		assertFalse(GroupTrIncognitoNames.isIncognitoName("Falcon Amber 27"));
		assertFalse(GroupTrIncognitoNames.isIncognitoName("Amber Falcon 7"));
		assertFalse(GroupTrIncognitoNames.isIncognitoName("Amber Falcon 07"));
		assertFalse(GroupTrIncognitoNames.isIncognitoName("Amber Falcon 100"));
		assertFalse(GroupTrIncognitoNames.isIncognitoName("Amber  Falcon 27"));
		assertFalse(GroupTrIncognitoNames.isIncognitoName("Amber Falcon 27 "));
		assertFalse(GroupTrIncognitoNames.isIncognitoName("Amber Falcon ２７"));
		assertFalse(GroupTrIncognitoNames.isIncognitoName("Amber Falcon"));
	}
}
