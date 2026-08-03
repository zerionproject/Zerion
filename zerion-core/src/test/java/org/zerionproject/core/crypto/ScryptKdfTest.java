package org.zerionproject.core.crypto;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.ArrayClock;
import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static junit.framework.TestCase.assertTrue;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.util.StringUtils.getRandomString;
import static org.junit.Assert.assertEquals;

public class ScryptKdfTest extends BrambleTestCase {

	@Test
	public void testPasswordAffectsKey() throws Exception {
		PasswordBasedKdf kdf = new ScryptKdf(new SystemClock());
		byte[] salt = getRandomBytes(32);
		Set<Bytes> keys = new HashSet<>();
		for (int i = 0; i < 100; i++) {
			char[] password = getRandomString(16).toCharArray();
			SecretKey key = kdf.deriveKey(password, salt, 256);
			assertTrue(keys.add(new Bytes(key.getBytes())));
		}
	}

	@Test
	public void testSaltAffectsKey() throws Exception {
		PasswordBasedKdf kdf = new ScryptKdf(new SystemClock());
		char[] password = getRandomString(16).toCharArray();
		Set<Bytes> keys = new HashSet<>();
		for (int i = 0; i < 100; i++) {
			byte[] salt = getRandomBytes(32);
			SecretKey key = kdf.deriveKey(password, salt, 256);
			assertTrue(keys.add(new Bytes(key.getBytes())));
		}
	}

	@Test
	public void testCostParameterAffectsKey() throws Exception {
		PasswordBasedKdf kdf = new ScryptKdf(new SystemClock());
		char[] password = getRandomString(16).toCharArray();
		byte[] salt = getRandomBytes(32);
		Set<Bytes> keys = new HashSet<>();
		for (int cost = 2; cost <= 256; cost *= 2) {
			SecretKey key = kdf.deriveKey(password, salt, cost);
			assertTrue(keys.add(new Bytes(key.getBytes())));
		}
	}

	@Test
	public void testCalibration() throws Exception {
		Clock clock = new ArrayClock(
				0, 50,
				0, 100,
				0, 200,
				0, 400,
				0, 800
		);
		PasswordBasedKdf kdf = new ScryptKdf(clock);
		assertEquals(4096, kdf.chooseCostParameter());
	}

	@Test
	public void testCalibrationChoosesMinCost() throws Exception {
		Clock clock = new ArrayClock(
				0, 2000
		);
		PasswordBasedKdf kdf = new ScryptKdf(clock);
		assertEquals(256, kdf.chooseCostParameter());
	}
}
