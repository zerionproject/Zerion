package com.professor.zerion.android.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStoreException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public final class ZerionEncryptedPrefs implements SharedPreferences {

	private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
	private static final String KEY_ALIAS = "zerion_prefs_master_v2";
	private static final String BOOT_KEY_ALIAS = "zerion_boot_prefs_master_v1";
	private static final String KEYNAME_HMAC_ALIAS =
			"zerion_prefs_keyname_hmac_v1";
	private static final String TRANSFORM = "AES/GCM/NoPadding";
	private static final String HMAC_ALGO = "HmacSHA256";
	private static final int GCM_IV_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;
	private static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;

	private static final byte TYPE_STRING = 's';
	private static final byte TYPE_BOOLEAN = 'b';
	private static final byte TYPE_INT = 'i';
	private static final byte TYPE_LONG = 'l';
	private static final byte TYPE_FLOAT = 'f';
	private static final byte TYPE_STRING_SET = 'q';

	private static final Map<String, ZerionEncryptedPrefs> INSTANCES =
			new ConcurrentHashMap<>();

	private final SharedPreferences delegate;
	private volatile SecretKey key;
	private volatile SecretKey hmacKey;
	private final boolean bootReadable;
	private final Object keyLock = new Object();
	private final Set<OnSharedPreferenceChangeListener> listeners =
			Collections.newSetFromMap(new ConcurrentHashMap<>());
	private final SharedPreferences.OnSharedPreferenceChangeListener
			delegateListener;

	private ZerionEncryptedPrefs(SharedPreferences delegate, SecretKey key,
			SecretKey hmacKey, boolean bootReadable) {
		this.delegate = delegate;
		this.key = key;
		this.hmacKey = hmacKey;
		this.bootReadable = bootReadable;
		this.delegateListener = (prefs, changedKey) -> {
			for (OnSharedPreferenceChangeListener l : listeners) {
				l.onSharedPreferenceChanged(ZerionEncryptedPrefs.this,
						changedKey);
			}
		};
		delegate.registerOnSharedPreferenceChangeListener(delegateListener);
	}

	public static synchronized ZerionEncryptedPrefs create(Context ctx,
			String fileName) {
		return create(ctx, fileName, false);
	}

	public static synchronized ZerionEncryptedPrefs createBootReadable(
			Context ctx, String fileName) {
		return create(ctx, fileName, true);
	}

	private static synchronized ZerionEncryptedPrefs create(Context ctx,
			String fileName, boolean bootReadable) {
		ZerionEncryptedPrefs cached = INSTANCES.get(fileName);
		if (cached != null) return cached;
		try {
			SecretKey k = getOrCreateKey(bootReadable);
			SecretKey hk = getOrCreateHmacKey();
			Context app = ctx.getApplicationContext();
			if (app == null) app = ctx;
			SharedPreferences backing = app.getSharedPreferences(
					fileName + "_v2", Context.MODE_PRIVATE);
			ZerionEncryptedPrefs prefs =
					new ZerionEncryptedPrefs(backing, k, hk, bootReadable);
			INSTANCES.put(fileName, prefs);
			deleteLegacyAndroidXFile(app, fileName);
			return prefs;
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(
					"ZerionEncryptedPrefs.create failed", e);
		}
	}

	private static void deleteLegacyAndroidXFile(Context app, String name) {
		try {
			SharedPreferences legacy = app.getSharedPreferences(name,
					Context.MODE_PRIVATE);
			if (!legacy.getAll().isEmpty()) {
				legacy.edit().clear().commit();
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				app.deleteSharedPreferences(name);
			}
		} catch (Throwable ignored) {
		}
		try {
			KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
			ks.load(null);
			if (ks.containsAlias("_androidx_security_master_key_")) {
				ks.deleteEntry("_androidx_security_master_key_");
			}
		} catch (Throwable ignored) {
		}
	}

	private static SecretKey getOrCreateKey(boolean bootReadable)
			throws GeneralSecurityException {
		String alias = bootReadable ? BOOT_KEY_ALIAS : KEY_ALIAS;
		KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
		try {
			ks.load(null);
		} catch (java.io.IOException e) {
			throw new GeneralSecurityException(e);
		}
		if (ks.containsAlias(alias)) {
			Key existing = ks.getKey(alias, null);
			if (existing instanceof SecretKey) {
				try {
					Cipher probe = Cipher.getInstance(TRANSFORM);
					probe.init(Cipher.ENCRYPT_MODE, (SecretKey) existing);
					probe.doFinal(new byte[] {0});
					return (SecretKey) existing;
				} catch (GeneralSecurityException invalidated) {
					try {
						ks.deleteEntry(alias);
					} catch (KeyStoreException ignored) {
					}
				}
			}
		}
		KeyGenerator gen = KeyGenerator.getInstance(
				KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
		KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
				alias,
				KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256);
		gen.init(builder.build());
		return gen.generateKey();
	}

	private static SecretKey getOrCreateHmacKey()
			throws GeneralSecurityException {
		KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
		try {
			ks.load(null);
		} catch (java.io.IOException e) {
			throw new GeneralSecurityException(e);
		}
		if (ks.containsAlias(KEYNAME_HMAC_ALIAS)) {
			Key existing = ks.getKey(KEYNAME_HMAC_ALIAS, null);
			if (existing instanceof SecretKey) {
				try {
					Mac probe = Mac.getInstance(HMAC_ALGO);
					probe.init((SecretKey) existing);
					probe.doFinal(new byte[] {0});
					return (SecretKey) existing;
				} catch (GeneralSecurityException invalidated) {
					try {
						ks.deleteEntry(KEYNAME_HMAC_ALIAS);
					} catch (KeyStoreException ignored) {
					}
				}
			}
		}
		KeyGenerator gen = KeyGenerator.getInstance(
				KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE);
		gen.init(new KeyGenParameterSpec.Builder(KEYNAME_HMAC_ALIAS,
				KeyProperties.PURPOSE_SIGN).build());
		return gen.generateKey();
	}

	private String backingKey(String prefKey) {
		byte[] digest = hmacMac().doFinal(
				prefKey.getBytes(StandardCharsets.UTF_8));
		return Base64.encodeToString(digest,
				Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
	}

	private Mac hmacMac() {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGO);
			mac.init(hmacKey);
			return mac;
		} catch (GeneralSecurityException invalidated) {
			synchronized (keyLock) {
				try {
					hmacKey = getOrCreateHmacKey();
					Mac mac = Mac.getInstance(HMAC_ALGO);
					mac.init(hmacKey);
					return mac;
				} catch (GeneralSecurityException e) {
					throw new RuntimeException("keyname hmac failed", e);
				}
			}
		}
	}

	private Cipher encryptCipher() throws GeneralSecurityException {
		try {
			Cipher c = Cipher.getInstance(TRANSFORM);
			c.init(Cipher.ENCRYPT_MODE, key);
			return c;
		} catch (GeneralSecurityException invalidated) {
			synchronized (keyLock) {
				key = getOrCreateKey(bootReadable);
				Cipher c = Cipher.getInstance(TRANSFORM);
				c.init(Cipher.ENCRYPT_MODE, key);
				return c;
			}
		}
	}

	private String encrypt(String backing, String prefKey, byte type,
			byte[] payload) {
		try {
			Cipher c = encryptCipher();
			c.updateAAD(backing.getBytes(StandardCharsets.UTF_8));
			byte[] iv = c.getIV();
			byte[] keyBytes = prefKey.getBytes(StandardCharsets.UTF_8);
			byte[] plaintext = new byte[1 + 2 + keyBytes.length
					+ payload.length];
			plaintext[0] = type;
			plaintext[1] = (byte) (keyBytes.length >>> 8);
			plaintext[2] = (byte) keyBytes.length;
			System.arraycopy(keyBytes, 0, plaintext, 3, keyBytes.length);
			System.arraycopy(payload, 0, plaintext, 3 + keyBytes.length,
					payload.length);
			byte[] ct = c.doFinal(plaintext);
			byte[] combined = new byte[iv.length + ct.length];
			System.arraycopy(iv, 0, combined, 0, iv.length);
			System.arraycopy(ct, 0, combined, iv.length, ct.length);
			Arrays.fill(plaintext, (byte) 0);
			return Base64.encodeToString(combined, Base64.NO_WRAP);
		} catch (GeneralSecurityException e) {
			throw new RuntimeException("encrypt failed", e);
		}
	}

	@Nullable
	private byte[] decryptExpect(String prefKey, byte expectedType) {
		String backing = backingKey(prefKey);
		String b64 = delegate.getString(backing, null);
		if (b64 == null) return null;
		Decoded d = decodeBlob(backing, b64);
		if (d == null || d.type != expectedType) return null;
		return d.value;
	}

	@Nullable
	private Decoded decodeBlob(String backing, String b64) {
		byte[] combined;
		try {
			combined = Base64.decode(b64, Base64.NO_WRAP);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (combined.length < GCM_IV_BYTES + GCM_TAG_BYTES + 3) return null;
		try {
			byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_BYTES);
			byte[] ct = Arrays.copyOfRange(combined, GCM_IV_BYTES,
					combined.length);
			Cipher c = Cipher.getInstance(TRANSFORM);
			c.init(Cipher.DECRYPT_MODE, key,
					new GCMParameterSpec(GCM_TAG_BITS, iv));
			c.updateAAD(backing.getBytes(StandardCharsets.UTF_8));
			byte[] plain = c.doFinal(ct);
			if (plain.length < 3) {
				Arrays.fill(plain, (byte) 0);
				return null;
			}
			byte type = plain[0];
			int keyLen = ((plain[1] & 0xFF) << 8) | (plain[2] & 0xFF);
			if (3 + keyLen > plain.length) {
				Arrays.fill(plain, (byte) 0);
				return null;
			}
			String prefKey = new String(plain, 3, keyLen,
					StandardCharsets.UTF_8);
			byte[] value = Arrays.copyOfRange(plain, 3 + keyLen, plain.length);
			Arrays.fill(plain, (byte) 0);
			return new Decoded(type, prefKey, value);
		} catch (GeneralSecurityException e) {
			return null;
		}
	}

	private static final class Decoded {
		final byte type;
		final String prefKey;
		final byte[] value;

		Decoded(byte type, String prefKey, byte[] value) {
			this.type = type;
			this.prefKey = prefKey;
			this.value = value;
		}
	}

	@Override
	public Map<String, ?> getAll() {
		Map<String, ?> raw = delegate.getAll();
		Map<String, Object> out = new HashMap<>();
		for (Map.Entry<String, ?> e : raw.entrySet()) {
			if (!(e.getValue() instanceof String)) continue;
			Decoded d = decodeBlob(e.getKey(), (String) e.getValue());
			if (d == null) continue;
			Object v = decodeTyped(d.type, d.value);
			if (v != null) out.put(d.prefKey, v);
		}
		return out;
	}

	@Nullable
	private Object decodeTyped(byte type, byte[] body) {
		switch (type) {
			case TYPE_STRING:
				return new String(body, StandardCharsets.UTF_8);
			case TYPE_BOOLEAN:
				return body.length == 1 && body[0] != 0;
			case TYPE_INT:
				if (body.length != 4) return null;
				return readInt(body, 0);
			case TYPE_LONG:
				if (body.length != 8) return null;
				return readLong(body, 0);
			case TYPE_FLOAT:
				if (body.length != 4) return null;
				return Float.intBitsToFloat(readInt(body, 0));
			case TYPE_STRING_SET:
				return decodeStringSet(body);
			default:
				return null;
		}
	}

	@Nullable
	@Override
	public String getString(String key, @Nullable String defValue) {
		byte[] payload = decryptExpect(key, TYPE_STRING);
		if (payload == null) return defValue;
		return new String(payload, StandardCharsets.UTF_8);
	}

	@Nullable
	@Override
	public Set<String> getStringSet(String key, @Nullable Set<String> def) {
		byte[] payload = decryptExpect(key, TYPE_STRING_SET);
		if (payload == null) return def;
		return decodeStringSet(payload);
	}

	@Override
	public int getInt(String key, int defValue) {
		byte[] payload = decryptExpect(key, TYPE_INT);
		if (payload == null || payload.length != 4) return defValue;
		return readInt(payload, 0);
	}

	@Override
	public long getLong(String key, long defValue) {
		byte[] payload = decryptExpect(key, TYPE_LONG);
		if (payload == null || payload.length != 8) return defValue;
		return readLong(payload, 0);
	}

	@Override
	public float getFloat(String key, float defValue) {
		byte[] payload = decryptExpect(key, TYPE_FLOAT);
		if (payload == null || payload.length != 4) return defValue;
		return Float.intBitsToFloat(readInt(payload, 0));
	}

	@Override
	public boolean getBoolean(String key, boolean defValue) {
		byte[] payload = decryptExpect(key, TYPE_BOOLEAN);
		if (payload == null || payload.length != 1) return defValue;
		return payload[0] != 0;
	}

	@Override
	public boolean contains(String key) {
		return delegate.contains(backingKey(key));
	}

	@Override
	public Editor edit() {
		return new ZerionEditor();
	}

	@Override
	public void registerOnSharedPreferenceChangeListener(
			OnSharedPreferenceChangeListener listener) {
		listeners.add(listener);
	}

	@Override
	public void unregisterOnSharedPreferenceChangeListener(
			OnSharedPreferenceChangeListener listener) {
		listeners.remove(listener);
	}

	private final class ZerionEditor implements Editor {
		private final SharedPreferences.Editor inner = delegate.edit();

		@Override
		public Editor putString(String key, @Nullable String value) {
			String backing = backingKey(key);
			if (value == null) {
				inner.remove(backing);
			} else {
				inner.putString(backing, encrypt(backing, key, TYPE_STRING,
						value.getBytes(StandardCharsets.UTF_8)));
			}
			return this;
		}

		@Override
		public Editor putStringSet(String key, @Nullable Set<String> values) {
			String backing = backingKey(key);
			if (values == null) {
				inner.remove(backing);
			} else {
				inner.putString(backing, encrypt(backing, key,
						TYPE_STRING_SET, encodeStringSet(values)));
			}
			return this;
		}

		@Override
		public Editor putInt(String key, int value) {
			String backing = backingKey(key);
			byte[] buf = new byte[4];
			writeInt(value, buf, 0);
			inner.putString(backing, encrypt(backing, key, TYPE_INT, buf));
			return this;
		}

		@Override
		public Editor putLong(String key, long value) {
			String backing = backingKey(key);
			byte[] buf = new byte[8];
			writeLong(value, buf, 0);
			inner.putString(backing, encrypt(backing, key, TYPE_LONG, buf));
			return this;
		}

		@Override
		public Editor putFloat(String key, float value) {
			String backing = backingKey(key);
			byte[] buf = new byte[4];
			writeInt(Float.floatToRawIntBits(value), buf, 0);
			inner.putString(backing, encrypt(backing, key, TYPE_FLOAT, buf));
			return this;
		}

		@Override
		public Editor putBoolean(String key, boolean value) {
			String backing = backingKey(key);
			inner.putString(backing, encrypt(backing, key, TYPE_BOOLEAN,
					new byte[]{(byte) (value ? 1 : 0)}));
			return this;
		}

		@Override
		public Editor remove(String key) {
			inner.remove(backingKey(key));
			return this;
		}

		@Override
		public Editor clear() {
			inner.clear();
			return this;
		}

		@Override
		public boolean commit() {
			return inner.commit();
		}

		@Override
		public void apply() {
			inner.apply();
		}
	}

	private static void writeInt(int value, byte[] dest, int offset) {
		dest[offset] = (byte) (value >>> 24);
		dest[offset + 1] = (byte) (value >>> 16);
		dest[offset + 2] = (byte) (value >>> 8);
		dest[offset + 3] = (byte) value;
	}

	private static int readInt(byte[] src, int offset) {
		return ((src[offset] & 0xFF) << 24)
				| ((src[offset + 1] & 0xFF) << 16)
				| ((src[offset + 2] & 0xFF) << 8)
				| (src[offset + 3] & 0xFF);
	}

	private static void writeLong(long value, byte[] dest, int offset) {
		dest[offset] = (byte) (value >>> 56);
		dest[offset + 1] = (byte) (value >>> 48);
		dest[offset + 2] = (byte) (value >>> 40);
		dest[offset + 3] = (byte) (value >>> 32);
		dest[offset + 4] = (byte) (value >>> 24);
		dest[offset + 5] = (byte) (value >>> 16);
		dest[offset + 6] = (byte) (value >>> 8);
		dest[offset + 7] = (byte) value;
	}

	private static long readLong(byte[] src, int offset) {
		return ((long) (src[offset] & 0xFF) << 56)
				| ((long) (src[offset + 1] & 0xFF) << 48)
				| ((long) (src[offset + 2] & 0xFF) << 40)
				| ((long) (src[offset + 3] & 0xFF) << 32)
				| ((long) (src[offset + 4] & 0xFF) << 24)
				| ((long) (src[offset + 5] & 0xFF) << 16)
				| ((long) (src[offset + 6] & 0xFF) << 8)
				| (long) (src[offset + 7] & 0xFF);
	}

	private static byte[] encodeStringSet(Set<String> values) {
		int totalSize = 4;
		for (String s : values) {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			totalSize += 4 + b.length;
		}
		byte[] out = new byte[totalSize];
		writeInt(values.size(), out, 0);
		int off = 4;
		for (String s : values) {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			writeInt(b.length, out, off);
			off += 4;
			System.arraycopy(b, 0, out, off, b.length);
			off += b.length;
		}
		return out;
	}

	@Nullable
	private static Set<String> decodeStringSet(byte[] body) {
		if (body.length < 4) return null;
		int count = readInt(body, 0);
		if (count < 0 || count > 1000000) return null;
		int off = 4;
		Set<String> out = new LinkedHashSet<>();
		for (int i = 0; i < count; i++) {
			if (off + 4 > body.length) return null;
			int len = readInt(body, off);
			off += 4;
			if (len < 0 || off + len > body.length) return null;
			out.add(new String(body, off, len, StandardCharsets.UTF_8));
			off += len;
		}
		return out;
	}
}
