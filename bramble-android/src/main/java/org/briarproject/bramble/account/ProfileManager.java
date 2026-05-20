package org.briarproject.bramble.account;

import android.content.Context;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.concurrent.GuardedBy;

@NotNullByDefault
public class ProfileManager {

	public static final String DEFAULT_PROFILE_ID = "default";

	private static final String PROFILES_DIR = "profiles";
	private static final String DB_SUBDIR = "db";
	private static final String KEY_SUBDIR = "key";
	private static final String TOR_SUBDIR = "tor";

	private static final String LEGACY_DB_DIR = "db";
	private static final String LEGACY_KEY_DIR = "key";
	private static final String LEGACY_TOR_DIR = "tor";

	private final Object lock = new Object();
	private final File filesDir;
	private final ProfileMetadataCrypto metadataCrypto =
			new ProfileMetadataCrypto();

	@GuardedBy("lock")
	private String activeProfileId = DEFAULT_PROFILE_ID;

	public ProfileManager(Context appContext) {
		this.filesDir = appContext.getFilesDir();
		migrateLegacyLayoutIfNeeded(appContext);
	}

	public String getActiveProfileId() {
		synchronized (lock) {
			return activeProfileId;
		}
	}

	public void setActiveProfileId(String profileId) {
		if (profileId.isEmpty()) {
			throw new IllegalArgumentException("Empty profile id");
		}
		synchronized (lock) {
			activeProfileId = profileId;
		}
	}

	public File getProfilesRoot() {
		return new File(filesDir, PROFILES_DIR);
	}

	public File getProfileRoot(String profileId) {
		return new File(getProfilesRoot(), profileId);
	}

	public File getActiveDbDir() {
		return ensureDir(new File(getProfileRoot(getActiveProfileId()),
				DB_SUBDIR));
	}

	public File getActiveKeyDir() {
		return ensureDir(new File(getProfileRoot(getActiveProfileId()),
				KEY_SUBDIR));
	}

	public File getActiveTorDir() {
		return ensureDir(new File(getProfileRoot(getActiveProfileId()),
				TOR_SUBDIR));
	}

	public File getDbDir(String profileId) {
		return ensureDir(new File(getProfileRoot(profileId), DB_SUBDIR));
	}

	public File getKeyDir(String profileId) {
		return ensureDir(new File(getProfileRoot(profileId), KEY_SUBDIR));
	}

	public File getTorDir(String profileId) {
		return ensureDir(new File(getProfileRoot(profileId), TOR_SUBDIR));
	}

	public File getAppFilesRoot() {
		return filesDir;
	}

	public List<String> listProfileIds() {
		File root = getProfilesRoot();
		if (!root.exists() || !root.isDirectory()) {
			return Collections.emptyList();
		}
		String[] names = root.list();
		if (names == null || names.length == 0) {
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>(names.length);
		for (String n : names) {
			File p = new File(root, n);
			if (p.isDirectory()) out.add(n);
		}
		Collections.sort(out);
		return out;
	}

	public boolean profileExists(String profileId) {
		return getProfileRoot(profileId).isDirectory();
	}

	public File getDbKeyFile(String profileId) {
		return new File(getKeyDir(profileId), "db.key");
	}

	public File getDbKeyBackupFile(String profileId) {
		return new File(getKeyDir(profileId), "db.key.bak");
	}

	public File getLockoutFile() {
		return new File(filesDir, "login.lockout");
	}

	private File getLastActiveProfileFile() {
		return new File(filesDir, "last_active_profile");
	}

	@javax.annotation.Nullable
	public String readLastActiveProfileId() {
		File f = getLastActiveProfileFile();
		if (!f.exists()) return null;
		return metadataCrypto.readEncrypted(f);
	}

	public void writeLastActiveProfileId(String profileId) {
		try {
			metadataCrypto.writeEncrypted(getLastActiveProfileFile(),
					profileId);
		} catch (java.io.IOException ignored) {
		}
	}

	public File getDisplayNameFile(String profileId) {
		return new File(getKeyDir(profileId), "display_name");
	}

	public boolean writeDisplayName(String profileId, String name) {
		File f = getDisplayNameFile(profileId);
		try {
			metadataCrypto.writeEncrypted(f, name);
			return f.exists() && f.length() > 0;
		} catch (java.io.IOException e) {
			return false;
		}
	}

	@javax.annotation.Nullable
	public String readDisplayName(String profileId) {
		File f = getDisplayNameFile(profileId);
		if (!f.exists()) return null;
		String decrypted = metadataCrypto.readEncrypted(f);
		if (decrypted != null) return decrypted.isEmpty() ? null : decrypted;
		String migrated = migratePlaintextDisplayName(f, profileId);
		return migrated == null || migrated.isEmpty() ? null : migrated;
	}

	public void writeEncryptedMetaFile(String profileId, String fileName,
			String value) throws java.io.IOException {
		File f = new File(getKeyDir(profileId), fileName);
		metadataCrypto.writeEncrypted(f, value);
	}

	@javax.annotation.Nullable
	public String readEncryptedMetaFile(String profileId, String fileName) {
		File f = new File(getKeyDir(profileId), fileName);
		if (!f.exists()) return null;
		String decrypted = metadataCrypto.readEncrypted(f);
		if (decrypted != null) return decrypted;
		String legacy = readLegacyPlaintext(f);
		if (legacy == null) return null;
		try {
			metadataCrypto.writeEncrypted(f, legacy);
		} catch (java.io.IOException ignored) {
		}
		return legacy;
	}

	public void deleteMetaFile(String profileId, String fileName) {
		File f = new File(getKeyDir(profileId), fileName);
		if (f.exists()) f.delete();
	}

	void deleteProfileMetadataKey() {
		metadataCrypto.deleteKey();
	}

	@javax.annotation.Nullable
	private String readLegacyPlaintext(File f) {
		try (java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader(new java.io.FileInputStream(f),
						java.nio.charset.StandardCharsets.UTF_8))) {
			String line = r.readLine();
			return line == null || line.isEmpty() ? null : line;
		} catch (java.io.IOException e) {
			return null;
		}
	}

	@javax.annotation.Nullable
	private String migratePlaintextDisplayName(File f, String profileId) {
		String legacy;
		try (java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader(new java.io.FileInputStream(f),
						java.nio.charset.StandardCharsets.UTF_8))) {
			legacy = r.readLine();
		} catch (java.io.IOException e) {
			return null;
		}
		if (legacy == null || legacy.isEmpty()) return null;
		writeDisplayName(profileId, legacy);
		return legacy;
	}

	public String generateProfileId() {
		return java.util.UUID.randomUUID().toString();
	}

	public boolean createProfileDir(String profileId) {
		File root = getProfileRoot(profileId);
		if (root.exists()) return false;
		if (!root.mkdirs()) return false;
		getDbDir(profileId);
		getKeyDir(profileId);
		getTorDir(profileId);
		return true;
	}

	public void secureWipeProfile(String profileId) {
		File root = getProfileRoot(profileId);
		if (!root.exists()) return;
		secureWipeRecursive(root);
	}

	private void secureWipeRecursive(File f) {
		if (f.isDirectory()) {
			File[] children = f.listFiles();
			if (children != null) {
				for (File c : children) secureWipeRecursive(c);
			}

			f.delete();
			return;
		}
		try {
			long len = f.length();
			if (len > 0 && len < 200L * 1024 * 1024) {
				try (java.io.RandomAccessFile raf =
						new java.io.RandomAccessFile(f, "rw")) {
					byte[] zeroes = new byte[8192];
					long written = 0;
					while (written < len) {
						int chunk = (int) Math.min(zeroes.length,
								len - written);
						raf.write(zeroes, 0, chunk);
						written += chunk;
					}
					raf.getFD().sync();
				}
			}
		} catch (java.io.IOException ignored) {
		}

		f.delete();
	}

	private File ensureDir(File f) {
		if (!f.exists()) {

			f.mkdirs();
		}
		return f;
	}

	private void migrateLegacyLayoutIfNeeded(Context appContext) {
		File profilesRoot = getProfilesRoot();
		if (profilesRoot.exists()) return;

		File legacyDb = appContext.getDir(LEGACY_DB_DIR, Context.MODE_PRIVATE);
		File legacyKey = appContext.getDir(LEGACY_KEY_DIR,
				Context.MODE_PRIVATE);
		File legacyTor = appContext.getDir(LEGACY_TOR_DIR,
				Context.MODE_PRIVATE);

		boolean haveLegacyData = legacyDbHasContents(legacyDb)
				|| legacyDbHasContents(legacyKey)
				|| legacyDbHasContents(legacyTor);

		if (!haveLegacyData) {

			profilesRoot.mkdirs();
			return;
		}

		File targetRoot = new File(profilesRoot, DEFAULT_PROFILE_ID);

		targetRoot.mkdirs();

		moveIfPresent(legacyDb, new File(targetRoot, DB_SUBDIR));
		moveIfPresent(legacyKey, new File(targetRoot, KEY_SUBDIR));
		moveIfPresent(legacyTor, new File(targetRoot, TOR_SUBDIR));
	}

	private boolean legacyDbHasContents(File dir) {
		if (!dir.exists() || !dir.isDirectory()) return false;
		String[] entries = dir.list();
		return entries != null && entries.length > 0;
	}

	private void moveIfPresent(File src, File dst) {
		if (!src.exists()) return;
		if (dst.exists()) return;
		if (src.renameTo(dst)) return;
		copyTreeBestEffort(src, dst);
	}

	private void copyTreeBestEffort(File src, File dst) {
		if (src.isDirectory()) {

			dst.mkdirs();
			File[] children = src.listFiles();
			if (children == null) return;
			for (File child : children) {
				copyTreeBestEffort(child, new File(dst, child.getName()));
			}

			src.delete();
		} else {
			try (java.io.FileInputStream in = new java.io.FileInputStream(src);
					java.io.FileOutputStream out =
							new java.io.FileOutputStream(dst)) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
				out.getFD().sync();

				src.delete();
			} catch (java.io.IOException ignored) {
			}
		}
	}
}
