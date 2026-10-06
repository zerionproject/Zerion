package com.professor.zerion.android.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class TestKeyStoreScreenLockModelTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static void generate(String alias, boolean unlockedRequired)
			throws Exception {
		KeyGenerator kg = KeyGenerator.getInstance(
				KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
		KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias,
				KeyProperties.PURPOSE_SIGN).setKeySize(256);
		if (unlockedRequired) b.setUnlockedDeviceRequired(true);
		kg.init(b.build());
		kg.generateKey();
	}

	@Test
	public void removingTheScreenLockDiscardsOnlyBoundKeys() throws Exception {
		generate("bound-model", true);
		generate("free-model", false);
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		boolean markedBound =
				TestAndroidKeyStore.requiresUnlockedDevice("bound-model");
		TestAndroidKeyStore.removeScreenLock();
		assertEquals("bound marked, bound gone, free kept",
				(markedBound ? "bound marked" : "bound not marked") + ", bound "
						+ (ks.containsAlias("bound-model") ? "kept" : "gone")
						+ ", free " + (ks.containsAlias("free-model") ? "kept"
						: "gone"));
		ks.deleteEntry("free-model");
	}
}
