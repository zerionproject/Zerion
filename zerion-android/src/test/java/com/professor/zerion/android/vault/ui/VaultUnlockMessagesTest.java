package com.professor.zerion.android.vault.ui;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.crypto.Argon2;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultUnlockMessagesTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	@Test
	public void aMissingNativeLibraryHasItsOwnMessage() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		when(rig.vault.manager.unlockVault(any(), anyLong()))
				.thenThrow(new Argon2.NativeRequiredException());
		VaultViewModel vm = rig.newModel();
		vm.unlockVault("vault password 4&Rt".toCharArray());
		assertEquals(rig.app.getString(R.string.vault_msg_unlock_needs_native),
				vm.getErrorMessage().getValue());
		verify(rig.vault.manager).markUnlockPending();
	}

	@Test
	public void aLockedVaultAfterASuccessfulUnlockStaysLocked()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		rig.vault.unlocked = false;
		when(rig.vault.manager.unlockVault(any(), anyLong())).thenReturn(true);
		VaultViewModel vm = rig.newModel();
		vm.unlockVault("vault password 4&Rt".toCharArray());
		assertEquals(VaultViewModel.VaultState.LOCKED,
				vm.getVaultState().getValue());
	}
}
