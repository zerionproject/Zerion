package org.briarproject.bramble.plugin.modem;

import org.briarproject.bramble.test.BrambleTestCase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CountryCodesTest extends BrambleTestCase {

	@Test
	public void testTranslation() {

		assertNull(CountryCodes.translate("02012345678", "ZZ", "GB"));

		assertNull(CountryCodes.translate("02012345678", "GB", "ZZ"));

		assertEquals("02012345678",
				CountryCodes.translate("2012345678", "GB", "GB"));

		assertEquals("02012345678",
				CountryCodes.translate("02012345678", "GB", "GB"));

		assertEquals("02012345678",
				CountryCodes.translate("+442012345678", "GB", "GB"));

		assertEquals("02012345678",
				CountryCodes.translate("00442012345678", "GB", "GB"));

		assertEquals("8**10442012345678",
				CountryCodes.translate("2012345678", "RU", "GB"));

		assertEquals("8**10442012345678",
				CountryCodes.translate("02012345678", "RU", "GB"));

		assertEquals("8**10442012345678",
				CountryCodes.translate("+442012345678", "RU", "GB"));

		assertEquals("8**10442012345678",
				CountryCodes.translate("00442012345678", "RU", "GB"));

		assertEquals("765432", CountryCodes.translate("765432", "AD", "AD"));

		assertEquals("765432",
				CountryCodes.translate("+376765432", "AD", "AD"));

		assertEquals("765432",
				CountryCodes.translate("00376765432", "AD", "AD"));

		assertEquals("00376765432",
				CountryCodes.translate("765432", "GB", "AD"));

		assertEquals("00376765432",
				CountryCodes.translate("+376765432", "GB", "AD"));

		assertEquals("00376765432",
				CountryCodes.translate("00376765432", "GB", "AD"));
	}
}
