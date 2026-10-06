package org.zerionproject.core.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Set;

@NotNullByDefault
public interface KeyStrengthener {

	int LEGACY_GENERATION = 0;

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean isInitialised();

	SecretKey strengthenKey(SecretKey k);

	void discardKeyBeforeFirstAccount();

	default int currentGeneration() {
		return LEGACY_GENERATION;
	}

	default boolean isInitialised(int generation) {
		return generation == LEGACY_GENERATION && isInitialised();
	}

	default SecretKey strengthenKey(SecretKey k, int generation) {
		if (generation != LEGACY_GENERATION) {
			throw new KeyStrengthenerException(
					new IllegalArgumentException("Unknown generation"));
		}
		return strengthenKey(k);
	}

	default boolean startNewGeneration() {
		return false;
	}

	default void retainGenerations(Set<Integer> inUse) {
	}
}
