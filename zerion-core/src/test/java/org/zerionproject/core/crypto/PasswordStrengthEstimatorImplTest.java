package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.PasswordStrengthEstimator;
import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import static org.zerionproject.core.api.crypto.PasswordStrengthEstimator.NONE;
import static org.zerionproject.core.api.crypto.PasswordStrengthEstimator.QUITE_STRONG;
import static org.junit.Assert.assertTrue;

public class PasswordStrengthEstimatorImplTest extends BrambleTestCase {

	@Test
	public void testWeakPasswords() {
		PasswordStrengthEstimator e = new PasswordStrengthEstimatorImpl();
		assertTrue(e.estimateStrength(new char[0]) == NONE);
		assertTrue(e.estimateStrength("password".toCharArray()) < QUITE_STRONG);
		assertTrue(e.estimateStrength("letmein".toCharArray()) < QUITE_STRONG);
		assertTrue(e.estimateStrength("123456".toCharArray()) < QUITE_STRONG);
	}

	@Test
	public void testStrongPasswords() {
		PasswordStrengthEstimator e = new PasswordStrengthEstimatorImpl();
		assertTrue(e.estimateStrength("Tr0ub4dor&3".toCharArray())
				> QUITE_STRONG);
		assertTrue(e.estimateStrength("correcthorsebatterystaple".toCharArray())
				> QUITE_STRONG);
	}
}
