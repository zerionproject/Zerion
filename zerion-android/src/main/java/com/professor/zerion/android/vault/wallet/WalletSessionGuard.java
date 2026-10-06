package com.professor.zerion.android.vault.wallet;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class WalletSessionGuard {

	private WalletSessionGuard() {
	}

	public static boolean valid(boolean vaultUnlocked, boolean sectionUnlocked,
			long authGeneration, long currentGeneration) {
		return vaultUnlocked
				&& sectionUnlocked
				&& authGeneration >= 0
				&& authGeneration == currentGeneration;
	}
}
