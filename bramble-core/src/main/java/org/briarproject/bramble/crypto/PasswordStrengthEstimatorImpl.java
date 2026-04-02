package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.PasswordStrengthEstimator;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashSet;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
class PasswordStrengthEstimatorImpl implements PasswordStrengthEstimator {
	private static final int STRONG_UNIQUE_CHARS = 12;

	@Override
	public float estimateStrength(char[] password) {
		HashSet<Character> unique = new HashSet<>();
		for (char c : password) unique.add(c);
		return Math.min(1, (float) unique.size() / STRONG_UNIQUE_CHARS);
	}
}
