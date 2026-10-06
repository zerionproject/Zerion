package com.professor.zerion.android.profile;

import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.account.LoginThrottle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
final class ProfileSettingsFile implements SharedPreferences {

	private static final byte FORMAT_VERSION = 1;
	private static final byte TYPE_BOOLEAN = 1;
	private static final byte TYPE_INT = 2;
	private static final byte TYPE_LONG = 3;
	private static final byte TYPE_FLOAT = 4;
	private static final byte TYPE_STRING = 5;
	private static final byte TYPE_STRING_SET = 6;
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
	private static final byte[] AAD = "org.zerionproject/PROFILE_SETTINGS"
			.getBytes(StandardCharsets.UTF_8);

	private final File file;
	private final SecretKeySpec key;
	private final Executor writer;
	private final SecureRandom random = new SecureRandom();
	private final Object lock = new Object();
	private final Object persistLock = new Object();
	private final Set<OnSharedPreferenceChangeListener> listeners =
			Collections.newSetFromMap(new ConcurrentHashMap<>());

	@GuardedBy("lock")
	private final Map<String, Object> values;

	private ProfileSettingsFile(File file, byte[] keyBytes, Executor writer,
			Map<String, Object> values) {
		this.file = file;
		this.key = new SecretKeySpec(keyBytes, "AES");
		this.writer = writer;
		this.values = values;
	}

	static ProfileSettingsFile open(File file, byte[] keyBytes,
			Executor writer) {
		Map<String, Object> loaded = load(file, keyBytes);
		return new ProfileSettingsFile(file, keyBytes, writer,
				loaded == null ? new HashMap<>() : loaded);
	}

	@Nullable
	private static Map<String, Object> load(File file, byte[] keyBytes) {
		if (!file.isFile()) return null;
		long len = file.length();
		if (len < 1 + IV_BYTES || len > MAX_FILE_BYTES) return null;
		byte[] all = new byte[(int) len];
		try (FileInputStream in = new FileInputStream(file)) {
			int read = 0;
			while (read < all.length) {
				int n = in.read(all, read, all.length - read);
				if (n < 0) return null;
				read += n;
			}
		} catch (IOException e) {
			return null;
		}
		if (all[0] != FORMAT_VERSION) return null;
		byte[] plain = null;
		try {
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
					new GCMParameterSpec(TAG_BITS, all, 1, IV_BYTES));
			c.updateAAD(AAD);
			plain = c.doFinal(all, 1 + IV_BYTES, all.length - 1 - IV_BYTES);
			return decode(plain);
		} catch (GeneralSecurityException | IOException
				| RuntimeException e) {
			return null;
		} finally {
			if (plain != null) Arrays.fill(plain, (byte) 0);
		}
	}

	private static Map<String, Object> decode(byte[] plain)
			throws IOException {
		Map<String, Object> out = new HashMap<>();
		DataInputStream in =
				new DataInputStream(new ByteArrayInputStream(plain));
		int count = in.readInt();
		if (count < 0) throw new IOException("count");
		for (int i = 0; i < count; i++) {
			String k = readString(in);
			byte type = in.readByte();
			switch (type) {
				case TYPE_BOOLEAN:
					out.put(k, in.readBoolean());
					break;
				case TYPE_INT:
					out.put(k, in.readInt());
					break;
				case TYPE_LONG:
					out.put(k, in.readLong());
					break;
				case TYPE_FLOAT:
					out.put(k, in.readFloat());
					break;
				case TYPE_STRING:
					out.put(k, readString(in));
					break;
				case TYPE_STRING_SET:
					int n = in.readInt();
					if (n < 0) throw new IOException("set");
					Set<String> s = new HashSet<>();
					for (int j = 0; j < n; j++) s.add(readString(in));
					out.put(k, Collections.unmodifiableSet(s));
					break;
				default:
					throw new IOException("type");
			}
		}
		return out;
	}

	private static String readString(DataInputStream in) throws IOException {
		int n = in.readInt();
		if (n < 0 || n > MAX_FILE_BYTES) throw new IOException("length");
		byte[] b = new byte[n];
		in.readFully(b);
		return new String(b, StandardCharsets.UTF_8);
	}

	private static void writeString(DataOutputStream out, String s)
			throws IOException {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		out.writeInt(b.length);
		out.write(b);
	}

	private static byte[] encode(Map<String, Object> snapshot)
			throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(snapshot.size());
		for (Map.Entry<String, Object> e : snapshot.entrySet()) {
			writeString(out, e.getKey());
			Object v = e.getValue();
			if (v instanceof Boolean) {
				out.writeByte(TYPE_BOOLEAN);
				out.writeBoolean((Boolean) v);
			} else if (v instanceof Integer) {
				out.writeByte(TYPE_INT);
				out.writeInt((Integer) v);
			} else if (v instanceof Long) {
				out.writeByte(TYPE_LONG);
				out.writeLong((Long) v);
			} else if (v instanceof Float) {
				out.writeByte(TYPE_FLOAT);
				out.writeFloat((Float) v);
			} else if (v instanceof String) {
				out.writeByte(TYPE_STRING);
				writeString(out, (String) v);
			} else {
				@SuppressWarnings("unchecked")
				Set<String> s = (Set<String>) v;
				out.writeByte(TYPE_STRING_SET);
				out.writeInt(s.size());
				for (String item : s) writeString(out, item);
			}
		}
		out.flush();
		return bytes.toByteArray();
	}

	private boolean persist() {
		synchronized (persistLock) {
			Map<String, Object> snapshot;
			synchronized (lock) {
				snapshot = new HashMap<>(values);
			}
			byte[] plain = null;
			try {
				plain = encode(snapshot);
				byte[] iv = new byte[IV_BYTES];
				random.nextBytes(iv);
				Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
				c.init(Cipher.ENCRYPT_MODE, key,
						new GCMParameterSpec(TAG_BITS, iv));
				c.updateAAD(AAD);
				byte[] sealed = c.doFinal(plain);
				byte[] out = new byte[1 + IV_BYTES + sealed.length];
				out[0] = FORMAT_VERSION;
				System.arraycopy(iv, 0, out, 1, IV_BYTES);
				System.arraycopy(sealed, 0, out, 1 + IV_BYTES, sealed.length);
				LoginThrottle.writeDurably(file, out);
				return true;
			} catch (GeneralSecurityException | IOException
					| RuntimeException e) {
				return false;
			} finally {
				if (plain != null) Arrays.fill(plain, (byte) 0);
			}
		}
	}

	@Override
	public Map<String, ?> getAll() {
		synchronized (lock) {
			return new HashMap<>(values);
		}
	}

	@Nullable
	private Object get(String k) {
		synchronized (lock) {
			return values.get(k);
		}
	}

	@Override
	@Nullable
	public String getString(String k, @Nullable String defValue) {
		Object v = get(k);
		return v instanceof String ? (String) v : defValue;
	}

	@Override
	@Nullable
	public Set<String> getStringSet(String k,
			@Nullable Set<String> defValues) {
		Object v = get(k);
		if (!(v instanceof Set)) return defValues;
		@SuppressWarnings("unchecked")
		Set<String> s = (Set<String>) v;
		return new HashSet<>(s);
	}

	@Override
	public int getInt(String k, int defValue) {
		Object v = get(k);
		return v instanceof Integer ? (Integer) v : defValue;
	}

	@Override
	public long getLong(String k, long defValue) {
		Object v = get(k);
		return v instanceof Long ? (Long) v : defValue;
	}

	@Override
	public float getFloat(String k, float defValue) {
		Object v = get(k);
		return v instanceof Float ? (Float) v : defValue;
	}

	@Override
	public boolean getBoolean(String k, boolean defValue) {
		Object v = get(k);
		return v instanceof Boolean ? (Boolean) v : defValue;
	}

	@Override
	public boolean contains(String k) {
		synchronized (lock) {
			return values.containsKey(k);
		}
	}

	@Override
	public Editor edit() {
		return new FileEditor();
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

	private final class FileEditor implements Editor {

		private final Map<String, Object> puts = new HashMap<>();
		private final Set<String> removals = new HashSet<>();
		private boolean clear = false;

		@Override
		public Editor putString(String k, @Nullable String v) {
			return put(k, v);
		}

		@Override
		public Editor putStringSet(String k, @Nullable Set<String> v) {
			return put(k, v == null ? null
					: Collections.unmodifiableSet(new HashSet<>(v)));
		}

		@Override
		public Editor putInt(String k, int v) {
			return put(k, v);
		}

		@Override
		public Editor putLong(String k, long v) {
			return put(k, v);
		}

		@Override
		public Editor putFloat(String k, float v) {
			return put(k, v);
		}

		@Override
		public Editor putBoolean(String k, boolean v) {
			return put(k, v);
		}

		private Editor put(String k, @Nullable Object v) {
			synchronized (this) {
				if (v == null) {
					puts.remove(k);
					removals.add(k);
				} else {
					removals.remove(k);
					puts.put(k, v);
				}
			}
			return this;
		}

		@Override
		public Editor remove(String k) {
			synchronized (this) {
				puts.remove(k);
				removals.add(k);
			}
			return this;
		}

		@Override
		public Editor clear() {
			synchronized (this) {
				clear = true;
			}
			return this;
		}

		private Set<String> applyToMemory() {
			Set<String> changed = new HashSet<>();
			synchronized (this) {
				synchronized (lock) {
					if (clear) {
						changed.addAll(values.keySet());
						values.clear();
					}
					for (String k : removals) {
						if (values.remove(k) != null) changed.add(k);
					}
					for (Map.Entry<String, Object> e : puts.entrySet()) {
						Object old = values.put(e.getKey(), e.getValue());
						if (!e.getValue().equals(old)) changed.add(e.getKey());
					}
				}
			}
			for (String k : changed) {
				for (OnSharedPreferenceChangeListener l : listeners) {
					l.onSharedPreferenceChanged(ProfileSettingsFile.this, k);
				}
			}
			return changed;
		}

		@Override
		public boolean commit() {
			applyToMemory();
			return persist();
		}

		@Override
		public void apply() {
			if (applyToMemory().isEmpty()) return;
			writer.execute(ProfileSettingsFile.this::persist);
		}
	}
}
