package org.zerionproject.core.identity;

import org.zerionproject.core.api.identity.ReservedNames;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReservedNamesTest {

	@Test
	public void blocksExactReservedNamesCaseInsensitive() {
		assertTrue(ReservedNames.isReserved("zerion"));
		assertTrue(ReservedNames.isReserved("Zerion"));
		assertTrue(ReservedNames.isReserved("ZERION"));
		assertTrue(ReservedNames.isReserved("support"));
		assertTrue(ReservedNames.isReserved("Admin"));
		assertTrue(ReservedNames.isReserved("Moderator"));
		assertTrue(ReservedNames.isReserved("ROOT"));
	}

	@Test
	public void blocksZerionPrefixVariants() {
		assertTrue(ReservedNames.isReserved("zerion-admin"));
		assertTrue(ReservedNames.isReserved("zerion_support"));
		assertTrue(ReservedNames.isReserved("zerion support"));
		assertTrue(ReservedNames.isReserved("zerion.team"));
		assertTrue(ReservedNames.isReserved("Zerion-Help"));
		assertTrue(ReservedNames.isReserved("ZerionApp"));
		assertTrue(ReservedNames.isReserved("zerion123"));
		assertTrue(ReservedNames.isReserved("Zerion Foundation"));
	}

	@Test
	public void blocksSeparatorAndPunctuationTricks() {
		assertTrue(ReservedNames.isReserved("z-e-r-i-o-n"));
		assertTrue(ReservedNames.isReserved("z.e.r.i.o.n"));
		assertTrue(ReservedNames.isReserved("Z e r i o n"));
		assertTrue(ReservedNames.isReserved(" zerion "));
		assertTrue(ReservedNames.isReserved("!zerion!"));
		assertTrue(ReservedNames.isReserved("-zerion-admin-"));
	}

	@Test
	public void blocksUnicodeFullWidthAndCompatibilityForms() {
		assertTrue(ReservedNames.isReserved("ｚｅｒｉｏｎ"));
		assertTrue(ReservedNames.isReserved("ｚｅｒｉｏｎ-admin"));
	}

	@Test
	public void allowsOrdinaryNames() {
		assertFalse(ReservedNames.isReserved("Alice"));
		assertFalse(ReservedNames.isReserved("Bob"));
		assertFalse(ReservedNames.isReserved("alice-zerion-fan"));
		assertFalse(ReservedNames.isReserved("my zerion"));
		assertFalse(ReservedNames.isReserved("z"));
		assertFalse(ReservedNames.isReserved("zer"));
		assertFalse(ReservedNames.isReserved(""));
		assertFalse(ReservedNames.isReserved("   "));
		assertFalse(ReservedNames.isReserved("HelpAlice"));
		assertFalse(ReservedNames.isReserved("MyAdminAssistant"));
	}
}
