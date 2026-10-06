package com.professor.zerion.android;

import android.security.keystore.KeyGenParameterSpec;

import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.KeyStrengthenerException;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStore.Entry;
import java.security.KeyStore.SecretKeyEntry;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	private static final String GENERATION_ALIAS_PREFIX = "db_g";
	private static final int KEY_BITS = 256;

	@GuardedBy("this")
	@Nullable
	private javax.crypto.SecretKey storedKey = null;
	@GuardedBy("this")
	@Nullable
	private GeneralSecurityException lookupFailure = null;
	@GuardedBy("this")
	private boolean freshInstall = false;
	@GuardedBy("this")
	private final Map<Integer, javax.crypto.SecretKey> generationKeys =
			new HashMap<>();
	@GuardedBy("this")
	private int current = 0;

	static String generationAlias(int generation) {
		return generation == LEGACY_GENERATION ? KEY_ALIAS
				: GENERATION_ALIAS_PREFIX + generation;
	}

	static int generationOf(String alias) {
		if (alias.equals(KEY_ALIAS)) return LEGACY_GENERATION;
		if (!alias.startsWith(GENERATION_ALIAS_PREFIX)) return -1;
		String n = alias.substring(GENERATION_ALIAS_PREFIX.length());
		if (n.isEmpty() || n.length() > 9) return -1;
		for (int i = 0; i < n.length(); i++) {
			if (n.charAt(i) < '0' || n.charAt(i) > '9') return -1;
		}
		int g = Integer.parseInt(n);
		return g >= 1 ? g : -1;
	}

	private static List<AlgorithmParameterSpec> specs(String alias) {
		KeyGenParameterSpec noStrongBox =
				new KeyGenParameterSpec.Builder(alias, PURPOSE_SIGN)
						.setKeySize(KEY_BITS)
						.build();
		if (SDK_INT >= 28) {
			KeyGenParameterSpec strongBox =
					new KeyGenParameterSpec.Builder(alias, PURPOSE_SIGN)
							.setIsStrongBoxBacked(true)
							.setKeySize(KEY_BITS)
							.build();
			return asList(strongBox, noStrongBox);
		}
		return singletonList(noStrongBox);
	}

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

	@Override
	public synchronized void discardKeyBeforeFirstAccount() {
		for (String alias : strengthenerAliases()) deleteKey(alias);
		deleteKey(KEY_ALIAS);
		deleteKey(generationAlias(1));
		storedKey = null;
		lookupFailure = null;
		generationKeys.clear();
		current = 0;
		freshInstall = true;
	}

	@Override
	public synchronized SecretKey strengthenKey(SecretKey k) {
		try {
			if (freshInstall) {
				storedKey = generate(KEY_ALIAS);
				freshInstall = false;
			} else if (!isInitialised()) {
				if (lookupFailure != null) {
					throw new KeyStrengthenerException(lookupFailure);
				}
				if (aliasMayExist(KEY_ALIAS)) {
					throw new KeyStrengthenerException(
							new GeneralSecurityException(
									"key entry present but unreadable"));
				}
				storedKey = generate(KEY_ALIAS);
			}
			return mac(storedKey, k);
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public synchronized int currentGeneration() {
		if (current == 0) {
			int newest = newestGeneration();
			current = newest >= 1 ? newest : 1;
		}
		return current;
	}

	@Override
	public synchronized boolean isInitialised(int generation) {
		if (generation == LEGACY_GENERATION) return isInitialised();
		try {
			return loadGeneration(generation) != null;
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	@Override
	public synchronized SecretKey strengthenKey(SecretKey k, int generation) {
		if (generation == LEGACY_GENERATION) return strengthenKey(k);
		try {
			javax.crypto.SecretKey key;
			try {
				key = loadGeneration(generation);
			} catch (GeneralSecurityException e) {
				if (!freshInstall) throw new KeyStrengthenerException(e);
				key = null;
			}
			if (key == null) {
				String alias = generationAlias(generation);
				if (generation != currentGeneration()
						|| (!freshInstall && aliasMayExist(alias))) {
					throw new KeyStrengthenerException(
							new GeneralSecurityException(
									"key generation unavailable"));
				}
				key = generate(alias);
				generationKeys.put(generation, key);
				freshInstall = false;
			}
			return mac(key, k);
		} catch (GeneralSecurityException e) {
			throw new KeyStrengthenerException(e);
		}
	}

	@Override
	public synchronized boolean startNewGeneration() {
		int next = Math.max(newestGeneration(), currentGeneration()) + 1;
		String alias = generationAlias(next);
		try {
			deleteKey(alias);
			javax.crypto.SecretKey key = generate(alias);
			generationKeys.put(next, key);
			current = next;
			freshInstall = false;
			return true;
		} catch (GeneralSecurityException e) {
			deleteKey(alias);
			return false;
		}
	}

	@Override
	public synchronized void retainGenerations(Set<Integer> inUse) {
		int keep = currentGeneration();
		for (String alias : strengthenerAliases()) {
			int g = generationOf(alias);
			if (g < 0 || g == keep || inUse.contains(g)) continue;
			deleteKey(alias);
			if (g == LEGACY_GENERATION) {
				storedKey = null;
			} else {
				generationKeys.remove(g);
			}
		}
	}

	@GuardedBy("this")
	@Nullable
	private javax.crypto.SecretKey loadGeneration(int generation)
			throws GeneralSecurityException {
		javax.crypto.SecretKey cached = generationKeys.get(generation);
		if (cached != null) return cached;
		try {
			KeyStore ks = KeyStore.getInstance(KEY_STORE_TYPE);
			ks.load(null);
			Entry entry = ks.getEntry(generationAlias(generation), null);
			if (entry instanceof SecretKeyEntry) {
				javax.crypto.SecretKey key =
						((SecretKeyEntry) entry).getSecretKey();
				generationKeys.put(generation, key);
				return key;
			}
			return null;
		} catch (IOException e) {
			throw new GeneralSecurityException(e);
		}
	}

	@GuardedBy("this")
	private int newestGeneration() {
		int newest = 0;
		for (String alias : strengthenerAliases()) {
			newest = Math.max(newest, generationOf(alias));
		}
		return newest;
	}

	private static List<String> strengthenerAliases() {
		List<String> out = new ArrayList<>();
		try {
			KeyStore ks = KeyStore.getInstance(KEY_STORE_TYPE);
			ks.load(null);
			Enumeration<String> aliases = ks.aliases();
			while (aliases.hasMoreElements()) {
				String a = aliases.nextElement();
				if (generationOf(a) >= 0) out.add(a);
			}
		} catch (GeneralSecurityException | IOException ignored) {
		}
		return out;
	}

	private static SecretKey mac(javax.crypto.SecretKey key, SecretKey k)
			throws GeneralSecurityException {
		Mac mac = Mac.getInstance(KEY_ALGORITHM_HMAC_SHA256);
		mac.init(key);
		return new SecretKey(mac.doFinal(k.getBytes()));
	}

	private static boolean aliasMayExist(String alias) {
		try {
			KeyStore ks = KeyStore.getInstance(KEY_STORE_TYPE);
			ks.load(null);
			return ks.containsAlias(alias);
		} catch (GeneralSecurityException | IOException e) {
			return true;
		}
	}

	private static javax.crypto.SecretKey generate(String alias)
			throws GeneralSecurityException {
		for (AlgorithmParameterSpec spec : specs(alias)) {
			try {
				KeyGenerator kg = KeyGenerator.getInstance(
						KEY_ALGORITHM_HMAC_SHA256, PROVIDER_NAME);
				kg.init(spec);
				javax.crypto.SecretKey candidate = kg.generateKey();
				Mac probe = Mac.getInstance(KEY_ALGORITHM_HMAC_SHA256);
				probe.init(candidate);
				probe.doFinal(new byte[1]);
				return candidate;
			} catch (Exception e) {
				deleteKey(alias);
			}
		}
		throw new GeneralSecurityException("Could not generate key");
	}

	private static void deleteKey(String alias) {
		try {
			KeyStore ks = KeyStore.getInstance(KEY_STORE_TYPE);
			ks.load(null);
			ks.deleteEntry(alias);
		} catch (GeneralSecurityException | IOException ignored) {
		}
	}
}
