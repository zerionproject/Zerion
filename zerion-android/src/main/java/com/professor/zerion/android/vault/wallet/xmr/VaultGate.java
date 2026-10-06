package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface VaultGate {
	boolean isUnlocked();

	long getLockGeneration();

	void addLockListener(Runnable listener);
}
