package com.professor.zerion.android.security;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ZerionEncryptedPrefsKeyPlacementTest {

	@Test
	public void thePreferenceAndLockoutKeysNeverAskForStrongBox() {
		assertArrayEquals(new boolean[] {false},
				ZerionEncryptedPrefs.strongBoxFirst());
	}
}
