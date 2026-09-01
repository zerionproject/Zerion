package com.professor.zerion.android.vault.wallet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WalletSessionGuardTest {

	@Test
	public void validOnlyWhenUnlockedAuthenticatedAndSameGeneration() {
		assertTrue(WalletSessionGuard.valid(true, true, 5, 5));
	}

	@Test
	public void lockedVaultIsNeverValid() {
		assertFalse("vault locked ends wallet session",
				WalletSessionGuard.valid(false, true, 5, 5));
	}

	@Test
	public void unauthenticatedSectionIsNeverValid() {
		assertFalse(WalletSessionGuard.valid(true, false, 5, 5));
	}

	@Test
	public void staleGenerationIsInvalid() {
		assertFalse("a vault lock/unlock between auth and access invalidates",
				WalletSessionGuard.valid(true, true, 5, 6));
	}

	@Test
	public void unlockingVaultAgainDoesNotRestoreWalletAuth() {
		long authGen = 3;
		long afterLock = authGen + 1;
		assertFalse("re-unlocking the vault must require fresh wallet auth",
				WalletSessionGuard.valid(true, true, authGen, afterLock));
	}

	@Test
	public void neverAuthenticatedSentinelIsInvalid() {
		assertFalse(WalletSessionGuard.valid(true, true, -1, -1));
	}
}
