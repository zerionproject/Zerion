package com.professor.zerion.android.mesh;

import android.bluetooth.le.AdvertisingSetParameters;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BleAdvertisingTest {

	@Test
	public void intervalsStayInTheBalancedBand() {
		Random random = new SecureRandom();
		Set<Integer> seen = new HashSet<>();
		for (int i = 0; i < 500; i++) {
			int interval = BleAdvertising.parameters(
					AdvertisingSetParameters.TX_POWER_MEDIUM, random)
					.getInterval();
			assertTrue(interval >= 160 && interval <= 400);
			seen.add(interval);
		}
		assertTrue(seen.size() > 50);
	}

	@Test
	public void theEdgesOfTheBandAreReachable() {
		assertEquals(160, BleAdvertising.parameters(
				AdvertisingSetParameters.TX_POWER_LOW, fixed(0)).getInterval());
		assertEquals(400, BleAdvertising.parameters(
				AdvertisingSetParameters.TX_POWER_LOW, fixed(240))
				.getInterval());
	}

	@Test
	public void replacementPausesAreShortAndVary() {
		Random random = new SecureRandom();
		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			long d = BleAdvertising.replacementDelayMs(random);
			assertTrue(d >= 250 && d <= 2_500);
			seen.add(d);
		}
		assertTrue(seen.size() > 20);
	}

	private static Random fixed(int value) {
		return new Random() {
			@Override
			public int nextInt(int bound) {
				return Math.min(value, bound - 1);
			}
		};
	}
}
