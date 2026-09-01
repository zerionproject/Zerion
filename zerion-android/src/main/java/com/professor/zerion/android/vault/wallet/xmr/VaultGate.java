package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * The minimal view of the vault the XMR layer needs to bind its session to the
 * vault lock generation. VaultManager implements this; tests provide a fake.
 */
@NotNullByDefault
public interface VaultGate {
	boolean isUnlocked();

	long getLockGeneration();

	void addLockListener(Runnable listener);
}
