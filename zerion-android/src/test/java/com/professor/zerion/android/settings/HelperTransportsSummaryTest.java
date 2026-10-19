package com.professor.zerion.android.settings;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.transport.TorBridgeConfigurator;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.api.plugin.TorConstants.HELPER_TRANSPORTS_ASK;
import static org.zerionproject.core.api.plugin.TorConstants.HELPER_TRANSPORTS_OFF;
import static org.zerionproject.core.api.plugin.TorConstants.HELPER_TRANSPORTS_USE;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, qualifiers = "en")
public class HelperTransportsSummaryTest {

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@Test
	public void theSummarySaysWhereTheChoiceApplies() {
		assertEquals("Used where Tor is blocked", HelperTransportsSummary.of(
				ctx, HELPER_TRANSPORTS_USE, TorBridgeConfigurator.ANY_COUNTRY));
		assertEquals("Used where Tor is blocked", HelperTransportsSummary.of(
				ctx, HELPER_TRANSPORTS_USE, ""));
		assertEquals("Used in Iran", HelperTransportsSummary.of(
				ctx, HELPER_TRANSPORTS_USE, "IR"));
		assertEquals("Not used in China", HelperTransportsSummary.of(
				ctx, HELPER_TRANSPORTS_OFF, "CN"));
		assertEquals("Not used", HelperTransportsSummary.of(
				ctx, HELPER_TRANSPORTS_OFF, TorBridgeConfigurator.ANY_COUNTRY));
		assertEquals("Not used", HelperTransportsSummary.of(
				ctx, HELPER_TRANSPORTS_OFF, null));
		assertEquals("Ask before using them", HelperTransportsSummary.of(
				ctx, HELPER_TRANSPORTS_ASK, "RU"));
		assertEquals("Ask before using them", HelperTransportsSummary.of(
				ctx, null, null));
	}

	@Test
	public void anUnknownCodeIsShownAsIs() {
		assertEquals("Used in NOPE", HelperTransportsSummary.of(
				ctx, HELPER_TRANSPORTS_USE, "nope"));
	}
}
