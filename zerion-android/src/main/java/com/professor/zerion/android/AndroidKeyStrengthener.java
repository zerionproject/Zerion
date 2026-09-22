package com.professor.zerion.android;

import android.security.keystore.KeyGenParameterSpec;

import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStore.Entry;
import java.security.KeyStore.SecretKeyEntry;
import java.security.spec.AlgorithmParameterSpec;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;

import androidx.annotation.RequiresApi;

import static android.os.Build.VERSION.SDK_INT;
import static android.security.keystore.KeyProperties.KEY_ALGORITHM_HMAC_SHA256;
import static android.security.keystore.KeyProperties.PURPOSE_SIGN;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

@RequiresApi(23)
@NotNullByDefault
class AndroidKeyStrengthener implements KeyStrengthener {

	private static final String KEY_STORE_TYPE = "AndroidKeyStore";
	private static final String PROVIDER_NAME = "AndroidKeyStore";
	private static final String KEY_ALIAS = "db";
	private static final int KEY_BITS = 256;

	private final List<AlgorithmParameterSpec> specs;

	AndroidKeyStrengthener() {
		KeyGenParameterSpec noStrongBox =
				new KeyGenParameterSpec.Builder(KEY_ALIAS, PURPOSE_SIGN)
						.setKeySize(KEY_BITS)
						.build();
		if (SDK_INT >= 28) {
			KeyGenParameterSpec strongBoxUnlockedRequired =
					new KeyGenParameterSpec.Builder(KEY_ALIAS, PURPOSE_SIGN)
							.setIsStrongBoxBacked(true)
							.setUnlockedDeviceRequired(true)
							.setKeySize(KEY_BITS)
							.build();
			KeyGenParameterSpec strongBox =
					new KeyGenParameterSpec.Builder(KEY_ALIAS, PURPOSE_SIGN)
							.setIsStrongBoxBacked(true)
							.setKeySize(KEY_BITS)
							.build();
			specs = asList(strongBoxUnlockedRequired, strongBox, noStrongBox);
		} else {
			specs = singletonList(noStrongBox);
		}
	}

	@GuardedBy("this")
	@Nullable
	private javax.crypto.SecretKey storedKey = null;
	/** The last lookup threw: the key may exist but cannot be read now. */
	@GuardedBy("this")
	@Nullable
	private GeneralSecurityException lookupFailure = null;

	@Override
	public synchronized boolean isInitialised() {
		if (storedKey != null) return true;
		lookupFailure = null;
		try {
			KeyStore ks = KeyStore.getInstance(KEY_STORE_TYPE);
			ks.load(null);
			Entry entry = ks.getEntry(KEY_ALIAS, null);
			if (entry instanceof SecretKeyEntry) {
				storedKey = ((SecretKeyEntry) entry).getSecretKey();
				return true;
			}
			return false;
		} catch (GeneralSecurityException e) {
			lookupFailure = e;
			return false;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * A key is generated only when the alias is provably absent. A lookup
	 * that threw, or an alias that exists but cannot be read, is a
	 * temporary failure: generating a new key under the alias would make
	 * every profile's stored key undecryptable for good.
	 */
	@Override
	public synchronized SecretKey strengthenKey(SecretKey k) {
		try {
			if (!isInitialised()) {
				if (lookupFailure != null) {
					throw new org.zerionproject.core.api.crypto
							.KeyStrengthenerException(lookupFailure);
				}
				if (aliasMayExist()) {
					throw new org.zerionproject.core.api.crypto
							.KeyStrengthenerException(
							new GeneralSecurityException(
									"key entry present but unreadable"));
				}
				initialise();
			}
			Mac mac = Mac.getInstance(KEY_ALGORITHM_HMAC_SHA256);
			mac.init(storedKey);
			return new SecretKey(mac.doFinal(k.getBytes()));
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	private boolean aliasMayExist() {
		try {
			KeyStore ks = KeyStore.getInstance(KEY_STORE_TYPE);
			ks.load(null);
			return ks.containsAlias(KEY_ALIAS);
		} catch (GeneralSecurityException | IOException e) {
			return true;
		}
	}

	private synchronized void initialise() throws GeneralSecurityException {
		for (AlgorithmParameterSpec spec : specs) {
			try {
				KeyGenerator kg = KeyGenerator.getInstance(
						KEY_ALGORITHM_HMAC_SHA256, PROVIDER_NAME);
				kg.init(spec);
				javax.crypto.SecretKey candidate = kg.generateKey();
				Mac probe = Mac.getInstance(KEY_ALGORITHM_HMAC_SHA256);
				probe.init(candidate);
				probe.doFinal(new byte[1]);
				storedKey = candidate;
				return;
			} catch (Exception e) {
				deleteKey();
			}
		}
		throw new GeneralSecurityException("Could not generate key");
	}

	private void deleteKey() {
		try {
			KeyStore ks = KeyStore.getInstance(KEY_STORE_TYPE);
			ks.load(null);
			ks.deleteEntry(KEY_ALIAS);
		} catch (GeneralSecurityException | IOException ignored) {
		}
	}
}
