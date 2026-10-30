package com.professor.zerion.android.vault.ui;

import android.content.Context;

import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultKdfTier;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultSettingsKdfLineTest {

	private final Context ctx = RuntimeEnvironment.getApplication();

	private String line(Argon2.Argon2Params p) {
		return ctx.getString(VaultSettingsFragment.kdfLine(p));
	}

	@Test
	public void theSettingsNameTheParametersInUse() {
		assertEquals("Password key: Argon2id, 1 GiB, 1 pass, bound to this"
						+ " device | Password key: Argon2id, 512 MiB, 2 passes,"
						+ " bound to this device | Password key: Argon2id,"
						+ " 256 MiB, 3 passes, bound to this device | Password"
						+ " key: Argon2id, 128 MiB, 6 passes (this device"
						+ " cannot spare 256 MiB), bound to this device",
				line(VaultKdfTier.TIER_A) + " | " + line(VaultKdfTier.TIER_B)
						+ " | " + line(VaultKdfTier.TIER_C) + " | "
						+ line(VaultManager.REDUCED_KDF));
	}
}
