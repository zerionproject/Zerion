package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class ChannelBlobStore {

	private static final String DIR_NAME = "channel-blobs";
	private static final String SECRET_NS =
			"zerion-channels-blob-dir-secret";
	private static final String SECRET_KEY = "s";
	private static final String DIR_NAME_LABEL =
			"org.zerionproject/BLOB_DIR_NAME";
	private static final int DIR_NAME_HEX_BYTES = 16;

	private final File rootDir;
	private final SettingsManager settingsManager;
	private final CryptoComponent crypto;
	private final SecureRandom random = new SecureRandom();
	private volatile byte[] dirNameSecret;

	@Inject
	ChannelBlobStore(DatabaseConfig dbConfig,
			SettingsManager settingsManager, CryptoComponent crypto) {
		this.rootDir = new File(
				dbConfig.getDatabaseDirectory().getParentFile(),
				DIR_NAME);
		this.settingsManager = settingsManager;
		this.crypto = crypto;
	}

	private byte[] getOrCreateDirNameSecret() throws IOException {
		byte[] cached = dirNameSecret;
		if (cached != null) return cached;
		synchronized (this) {
			if (dirNameSecret != null) return dirNameSecret;
			try {
				Settings s = settingsManager.getSettings(SECRET_NS);
				String encoded = s.get(SECRET_KEY);
				byte[] secret;
				if (encoded == null || encoded.isEmpty()) {
					secret = new byte[32];
					random.nextBytes(secret);
					Settings out = new Settings();
					out.put(SECRET_KEY,
							java.util.Base64.getEncoder()
									.withoutPadding()
									.encodeToString(secret));
					settingsManager.mergeSettings(out, SECRET_NS);
				} else {
					secret = java.util.Base64.getDecoder().decode(encoded);
				}
				dirNameSecret = secret;
				return secret;
			} catch (DbException e) {
				throw new IOException(e);
			} catch (IllegalArgumentException e) {
				throw new IOException(e);
			}
		}
	}

	void put(byte[] channelId, byte[] blobHash, byte[] encryptedBlob)
			throws IOException {
		File channelDir = channelDir(channelId);
		if (!channelDir.exists() && !channelDir.mkdirs()) {
			throw new IOException("Could not create blob dir");
		}
		File out = new File(channelDir, hex(blobHash) + ".bin");
		try (FileOutputStream fos = new FileOutputStream(out)) {
			fos.write(encryptedBlob);
		}
	}

	@Nullable
	byte[] get(byte[] channelId, byte[] blobHash) throws IOException {
		File file = new File(channelDir(channelId),
				hex(blobHash) + ".bin");
		if (!file.exists()) return null;
		long size = file.length();
		if (size < 0 || size > 64L * 1024L * 1024L) {
			return null;
		}
		byte[] out = new byte[(int) size];
		try (FileInputStream fis = new FileInputStream(file)) {
			int read = 0;
			while (read < out.length) {
				int n = fis.read(out, read, out.length - read);
				if (n < 0) break;
				read += n;
			}
			if (read != out.length) return null;
		}
		return out;
	}

	boolean has(byte[] channelId, byte[] blobHash) {
		try {
			return new File(channelDir(channelId),
					hex(blobHash) + ".bin").exists();
		} catch (IOException e) {
			return false;
		}
	}

	void removeBlob(byte[] channelId, byte[] blobHash) {
		try {
			File f = new File(channelDir(channelId),
					hex(blobHash) + ".bin");
			if (f.exists()) {
				f.delete();
			}
		} catch (IOException ignored) {
		}
	}

	void removeAllForChannel(byte[] channelId) {
		File dir;
		try {
			dir = channelDir(channelId);
		} catch (IOException ignored) {
			return;
		}
		File[] children = dir.listFiles();
		if (children == null) return;
		for (File f : children) {
			f.delete();
		}
		dir.delete();
		File legacy = new File(rootDir, hex(channelId));
		if (legacy.exists() && !legacy.equals(dir)) {
			File[] legacyKids = legacy.listFiles();
			if (legacyKids != null) {
				for (File f : legacyKids) f.delete();
			}
			legacy.delete();
		}
	}

	private File channelDir(byte[] channelId) throws IOException {
		byte[] secret = getOrCreateDirNameSecret();
		byte[] derived = crypto.hash(DIR_NAME_LABEL, secret, channelId);
		StringBuilder sb = new StringBuilder(DIR_NAME_HEX_BYTES * 2);
		int n = Math.min(DIR_NAME_HEX_BYTES, derived.length);
		for (int i = 0; i < n; i++) {
			sb.append(String.format(Locale.US, "%02x", derived[i]));
		}
		File opaque = new File(rootDir, sb.toString());
		File legacy = new File(rootDir, hex(channelId));
		if (legacy.exists() && !legacy.equals(opaque) && !opaque.exists()) {
			legacy.renameTo(opaque);
		}
		return opaque;
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
		return sb.toString();
	}
}
