package com.professor.zerion.android.backup;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.account.PasswordNormalizer;
import org.zerionproject.core.api.crypto.PasswordStrengthEstimator;

import java.util.BitSet;

import static org.zerionproject.core.api.crypto.PasswordStrengthEstimator.QUITE_STRONG;

@NotNullByDefault
public final class BackupPassphrase {

	public static final int MIN_LENGTH = 12;
	public static final int MIN_UNIQUE = 8;
	public static final int MIN_CLASSES = 2;
	public static final float MIN_STRENGTH = QUITE_STRONG;

	private BackupPassphrase() {
	}

	public static boolean longEnough(char[] passphrase) {
		char[] normal = PasswordNormalizer.normalize(passphrase);
		try {
			return normal.length >= MIN_LENGTH;
		} finally {
			java.util.Arrays.fill(normal, '\0');
		}
	}

	public static boolean varied(char[] passphrase) {
		char[] normal = PasswordNormalizer.normalize(passphrase);
		try {
			BitSet unique = new BitSet();
			boolean lower = false;
			boolean upper = false;
			boolean digit = false;
			boolean other = false;
			for (char c : normal) {
				unique.set(c);
				if (Character.isLowerCase(c)) lower = true;
				else if (Character.isUpperCase(c)) upper = true;
				else if (Character.isDigit(c)) digit = true;
				else other = true;
			}
			int classes = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0)
					+ (other ? 1 : 0);
			boolean ok = unique.cardinality() >= MIN_UNIQUE
					&& classes >= MIN_CLASSES;
			unique.clear();
			return ok;
		} finally {
			java.util.Arrays.fill(normal, '\0');
		}
	}

	public static boolean wellFormed(char[] passphrase) {
		return longEnough(passphrase) && varied(passphrase);
	}

	public static float strength(PasswordStrengthEstimator estimator,
			char[] passphrase) {
		char[] normal = PasswordNormalizer.normalize(passphrase);
		try {
			return estimator.estimateStrength(normal);
		} finally {
			java.util.Arrays.fill(normal, '\0');
		}
	}

	public static boolean acceptable(PasswordStrengthEstimator estimator,
			char[] passphrase) {
		return wellFormed(passphrase)
				&& strength(estimator, passphrase) >= MIN_STRENGTH;
	}
}
