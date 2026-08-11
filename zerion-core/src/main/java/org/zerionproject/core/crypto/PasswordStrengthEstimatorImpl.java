package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.PasswordStrengthEstimator;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashSet;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
class PasswordStrengthEstimatorImpl implements PasswordStrengthEstimator {
	private static final int STRONG_UNIQUE_CHARS = 12;
	private static final int STRONG_LENGTH = 16;

	@Override
	public float estimateStrength(char[] password) {
		HashSet<Character> unique = new HashSet<>();
		for (char c : password) unique.add(c);
		float uniqueScore = (float) unique.size() / STRONG_UNIQUE_CHARS;
		float lengthScore = (float) password.length / STRONG_LENGTH;
		return Math.min(1, Math.max(uniqueScore, lengthScore));
	}
}
