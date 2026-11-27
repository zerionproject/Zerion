package com.professor.zerion.android.settings;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, qualifiers = "en")
public class CustomBridgesSummaryTest {

	private final Context ctx = ApplicationProvider.getApplicationContext();

	private String summary(String value) {
		return ConnectionsFragment.customBridgesSummary(ctx.getResources(),
				value);
	}

	@Test
	public void usableLinesAreCounted() {
		assertEquals("Not set", summary(null));
		assertEquals("Not set", summary("\n  \n"));
		assertEquals("2 custom bridges", summary(
				"obfs4 192.0.2.6:443 GGGG\nobfs4 192.0.2.7:443 HHHH"));
	}

	@Test
	public void storedLinesThatCannotBeUsedAreReported() {
		assertEquals("2 custom bridges\n1 of 3 lines cannot be used",
				summary("obfs4 192.0.2.6:443 GGGG\n"
						+ "SocksPort 9999\n"
						+ "obfs4 192.0.2.7:443 HHHH"));
		assertEquals("0 custom bridges\n1 of 1 lines cannot be used",
				summary("DisableNetwork 1"));
	}
}
