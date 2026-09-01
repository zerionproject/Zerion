package com.professor.zerion.android.security;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.KeyGenerator;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;

public final class TestAndroidKeyStore {

	private static final String NAME = "AndroidKeyStore";
	private static final Map<String, Key> STORE = new ConcurrentHashMap<>();

	private TestAndroidKeyStore() {
	}

	public static void register() {
		if (Security.getProvider(NAME) == null) {
			Provider provider = new KeyStoreProvider();
			Security.addProvider(provider);
			markJceVerified(provider);
		}
	}

	@SuppressWarnings("unchecked")
	private static void markJceVerified(Provider provider) {
		try {
			Class<?> jceSecurity = Class.forName("javax.crypto.JceSecurity");
			Field resultsField =
					jceSecurity.getDeclaredField("verificationResults");
			resultsField.setAccessible(true);
			Map<Object, Object> results =
					(Map<Object, Object>) resultsField.get(null);

			Object verified = Boolean.TRUE;
			try {
				Field sentinel =
						jceSecurity.getDeclaredField("PROVIDER_VERIFIED");
				sentinel.setAccessible(true);
				verified = sentinel.get(null);
			} catch (NoSuchFieldException ignored) {
			}

			Object key = provider;
			try {
				Field queueField = jceSecurity.getDeclaredField("queue");
				queueField.setAccessible(true);
				Object queue = queueField.get(null);
				Class<?> wrapper = Class.forName(
						"javax.crypto.JceSecurity$WeakIdentityWrapper");
				Constructor<?> ctor = wrapper.getDeclaredConstructor(
						Provider.class, java.lang.ref.ReferenceQueue.class);
				ctor.setAccessible(true);
				key = ctor.newInstance(provider, queue);
			} catch (ReflectiveOperationException ignored) {
			}

			results.put(key, verified);
		} catch (ReflectiveOperationException ignored) {
		}
	}

	private static String aliasOf(AlgorithmParameterSpec spec) {
		if (spec == null) return "default";
		try {
			Method m = spec.getClass().getMethod("getKeystoreAlias");
			Object alias = m.invoke(spec);
			if (alias instanceof String) return (String) alias;
		} catch (ReflectiveOperationException ignored) {
		}
		return "default";
	}

	public static final class KeyStoreProvider extends Provider {
		public KeyStoreProvider() {
			super(NAME, 1.0, "Test AndroidKeyStore");
			put("KeyStore." + NAME, InMemoryKeyStoreSpi.class.getName());
			put("KeyGenerator.AES", AesKeyGeneratorSpi.class.getName());
			put("KeyGenerator.HmacSHA256",
					HmacKeyGeneratorSpi.class.getName());
		}
	}

	public static final class InMemoryKeyStoreSpi extends KeyStoreSpi {
		@Override
		public Key engineGetKey(String alias, char[] password) {
			return STORE.get(alias);
		}

		@Override
		public Certificate[] engineGetCertificateChain(String alias) {
			return null;
		}

		@Override
		public Certificate engineGetCertificate(String alias) {
			return null;
		}

		@Override
		public Date engineGetCreationDate(String alias) {
			return new Date();
		}

		@Override
		public void engineSetKeyEntry(String alias, Key key, char[] password,
				Certificate[] chain) {
			STORE.put(alias, key);
		}

		@Override
		public void engineSetKeyEntry(String alias, byte[] key,
				Certificate[] chain) {
		}

		@Override
		public void engineSetCertificateEntry(String alias, Certificate cert) {
		}

		@Override
		public void engineDeleteEntry(String alias) {
			STORE.remove(alias);
		}

		@Override
		public Enumeration<String> engineAliases() {
			return Collections.enumeration(STORE.keySet());
		}

		@Override
		public boolean engineContainsAlias(String alias) {
			return STORE.containsKey(alias);
		}

		@Override
		public int engineSize() {
			return STORE.size();
		}

		@Override
		public boolean engineIsKeyEntry(String alias) {
			return STORE.containsKey(alias);
		}

		@Override
		public boolean engineIsCertificateEntry(String alias) {
			return false;
		}

		@Override
		public String engineGetCertificateAlias(Certificate cert) {
			return null;
		}

		@Override
		public void engineStore(OutputStream stream, char[] password) {
		}

		@Override
		public void engineLoad(InputStream stream, char[] password) {
		}
	}

	private abstract static class BaseKeyGeneratorSpi extends KeyGeneratorSpi {
		private final String algorithm;
		private String alias = "default";
		private int keySize;

		BaseKeyGeneratorSpi(String algorithm, int defaultKeySize) {
			this.algorithm = algorithm;
			this.keySize = defaultKeySize;
		}

		@Override
		protected void engineInit(SecureRandom random) {
		}

		@Override
		protected void engineInit(AlgorithmParameterSpec params,
				SecureRandom random) {
			alias = aliasOf(params);
			try {
				Method m = params.getClass().getMethod("getKeySize");
				Object size = m.invoke(params);
				if (size instanceof Integer && (Integer) size > 0) {
					keySize = (Integer) size;
				}
			} catch (ReflectiveOperationException ignored) {
			}
		}

		@Override
		protected void engineInit(int size, SecureRandom random) {
			keySize = size;
		}

		@Override
		protected SecretKey engineGenerateKey() {
			try {
				KeyGenerator gen = KeyGenerator.getInstance(algorithm);
				gen.init(keySize, new SecureRandom());
				SecretKey key = gen.generateKey();
				STORE.put(alias, key);
				return key;
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static final class AesKeyGeneratorSpi extends BaseKeyGeneratorSpi {
		public AesKeyGeneratorSpi() {
			super("AES", 256);
		}
	}

	public static final class HmacKeyGeneratorSpi extends BaseKeyGeneratorSpi {
		public HmacKeyGeneratorSpi() {
			super("HmacSHA256", 256);
		}
	}
}
