package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Test;

import java.util.Arrays;

import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.util.StringUtils.getRandomString;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MacTest extends BrambleTestCase {

	private final CryptoComponent crypto =
			new CryptoComponentImpl(new TestSecureRandomProvider(), null);

	private final SecretKey key1 = getSecretKey(), key2 = getSecretKey();
	private final String label1 = getRandomString(123);
	private final String label2 = getRandomString(123);
	private final byte[] input1 = getRandomBytes(123);
	private final byte[] input2 = getRandomBytes(234);
	private final byte[] input3 = new byte[0];

	@Test
	public void testIdenticalKeysAndInputsProduceIdenticalMacs() {

		byte[] mac = crypto.mac(label1, key1, input1, input2, input3);
		byte[] mac1 = crypto.mac(label1, key1, input1, input2, input3);
		assertArrayEquals(mac, mac1);
		assertTrue(crypto.verifyMac(mac, label1, key1, input1, input2, input3));
	}

	@Test
	public void testDifferentLabelsProduceDifferentMacs() {

		byte[] mac = crypto.mac(label1, key1, input1, input2, input3);
		byte[] mac1 = crypto.mac(label2, key1, input1, input2, input3);
		assertFalse(Arrays.equals(mac, mac1));

		assertFalse(crypto.verifyMac(mac, label2, key1, input1, input2,
				input3));
		assertFalse(crypto.verifyMac(mac1, label1, key2, input1, input2,
				input3));
	}

	@Test
	public void testDifferentKeysProduceDifferentMacs() {

		byte[] mac = crypto.mac(label1, key1, input1, input2, input3);
		byte[] mac1 = crypto.mac(label1, key2, input1, input2, input3);
		assertFalse(Arrays.equals(mac, mac1));

		assertFalse(crypto.verifyMac(mac, label1, key2, input1, input2,
				input3));
		assertFalse(crypto.verifyMac(mac1, label2, key1, input1, input2,
				input3));
	}

	@Test
	public void testDifferentInputsProduceDifferentMacs() {

		byte[] mac = crypto.mac(label1, key1, input1, input2, input3);
		byte[] mac1 = crypto.mac(label1, key1, input3, input2, input1);
		assertFalse(Arrays.equals(mac, mac1));

		assertFalse(crypto.verifyMac(mac, label1, key2, input3, input2,
				input1));
		assertFalse(crypto.verifyMac(mac1, label1, key1, input1, input2,
				input3));
	}

}
