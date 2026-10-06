package com.professor.zerion.android.login;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.account.PasswordNormalizer;
import org.zerionproject.core.api.crypto.PasswordStrengthEstimator;

import java.util.Arrays;

import static org.zerionproject.core.api.crypto.PasswordStrengthEstimator.QUITE_WEAK;

@NotNullByDefault
public final class AccountPasswordPolicy {

	public static final int MIN_LENGTH = 8;

	private AccountPasswordPolicy() {
	}

	public static float strength(PasswordStrengthEstimator estimator,
			char[] typed) {
		char[] normal = PasswordNormalizer.normalize(typed);
		try {
			return estimator.estimateStrength(normal);
		} finally {
			Arrays.fill(normal, '\0');
		}
	}

	public static boolean acceptable(PasswordStrengthEstimator estimator,
			char[] typed) {
		char[] normal = PasswordNormalizer.normalize(typed);
		try {
			return normal.length >= MIN_LENGTH
					&& estimator.estimateStrength(normal) >= QUITE_WEAK;
		} finally {
			Arrays.fill(normal, '\0');
		}
	}

	public static boolean sameNormalForm(char[] a, char[] b) {
		char[] na = PasswordNormalizer.normalize(a);
		char[] nb = PasswordNormalizer.normalize(b);
		try {
			return Arrays.equals(na, nb);
		} finally {
			Arrays.fill(na, '\0');
			Arrays.fill(nb, '\0');
		}
	}
}
