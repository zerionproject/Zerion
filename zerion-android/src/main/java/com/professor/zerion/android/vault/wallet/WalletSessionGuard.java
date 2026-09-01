package com.professor.zerion.android.vault.wallet;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Pure decision for whether a previously authenticated wallet-section session
 * is still valid. The session is bound to a specific unlocked vault session via
 * a lock generation: any vault lock (manual, timeout, or background) increments
 * the generation, so authorization from an earlier session can never carry over
 * into a new one. Unlocking the vault therefore never implies wallet access.
 */
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
