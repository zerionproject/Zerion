package com.professor.zerion.android;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.zerionproject.core.api.crypto.KeyStrengthenerException;
import org.zerionproject.core.api.crypto.SecretKey;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.security.KeyStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * A2-AND-06: a keystore lookup that fails must not be mistaken for "no key
 * yet"; generating a fresh key under the alias would crypto-shred every
 * profile's stored database key. The strengthener refuses instead and works
 * again with the same key once the keystore answers.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AndroidKeyStrengthenerTest {

	static {
		TestAndroidKeyStore.register();
	}

	@After
	public void tearDown() throws Exception {
		TestAndroidKeyStore.failKeyLookups = 0;
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		if (ks.containsAlias("db")) ks.deleteEntry("db");
	}

	@Test
	public void aTransientLookupFailureNeverReplacesTheKey() throws Exception {
		SecretKey k = new SecretKey(new byte[32]);
		AndroidKeyStrengthener first = new AndroidKeyStrengthener();
		byte[] expected = first.strengthenKey(k).getBytes();
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		byte[] material = ks.getKey("db", null).getEncoded();
		assertNotNull(material);

		AndroidKeyStrengthener fresh = new AndroidKeyStrengthener();
		TestAndroidKeyStore.failKeyLookups = 1;
		try {
			fresh.strengthenKey(k);
			fail("an unreadable keystore must not generate a key");
		} catch (KeyStrengthenerException expectedFailure) {
		}
		assertArrayEquals("the stored key is untouched", material,
				ks.getKey("db", null).getEncoded());
		assertArrayEquals("the same key is used once the keystore answers",
				expected, fresh.strengthenKey(k).getBytes());
	}

	/**
	 * Before the first account exists nothing depends on the alias, so a key
	 * store that cannot even look the alias up must not stop the first
	 * account from being created: the strengthener discards the alias and
	 * generates. Google Play's review device failed 3.0.13 here.
	 */
	@Test
	public void aFreshInstallGeneratesAKeyEvenWhenLookupsFail()
			throws Exception {
		SecretKey k = new SecretKey(new byte[32]);
		TestAndroidKeyStore.failKeyLookups = 5;
		AndroidKeyStrengthener s = new AndroidKeyStrengthener();
		s.discardKeyBeforeFirstAccount();
		byte[] out = s.strengthenKey(k).getBytes();
		assertNotNull(out);
		TestAndroidKeyStore.failKeyLookups = 0;
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		assertNotNull(ks.getKey("db", null));
		assertArrayEquals("the generated key is the one in the store", out,
				new AndroidKeyStrengthener().strengthenKey(k).getBytes());
	}

	@Test
	public void discardingBeforeTheFirstAccountReplacesAStaleAlias()
			throws Exception {
		SecretKey k = new SecretKey(new byte[32]);
		byte[] stale = new AndroidKeyStrengthener().strengthenKey(k).getBytes();
		AndroidKeyStrengthener fresh = new AndroidKeyStrengthener();
		fresh.discardKeyBeforeFirstAccount();
		byte[] replaced = fresh.strengthenKey(k).getBytes();
		assertFalse("a new key was generated",
				java.util.Arrays.equals(stale, replaced));
	}

	@Test
	public void theGuardAppliesAgainOnceTheFirstKeyExists() throws Exception {
		SecretKey k = new SecretKey(new byte[32]);
		AndroidKeyStrengthener s = new AndroidKeyStrengthener();
		s.discardKeyBeforeFirstAccount();
		byte[] expected = s.strengthenKey(k).getBytes();
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		byte[] material = ks.getKey("db", null).getEncoded();
		AndroidKeyStrengthener later = new AndroidKeyStrengthener();
		TestAndroidKeyStore.failKeyLookups = 1;
		try {
			later.strengthenKey(k);
			fail("after the first account a lookup failure must not generate");
		} catch (KeyStrengthenerException expectedFailure) {
		}
		assertArrayEquals(material, ks.getKey("db", null).getEncoded());
		assertArrayEquals(expected, later.strengthenKey(k).getBytes());
	}
}
