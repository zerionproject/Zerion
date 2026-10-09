package org.zerionproject.core.account;

import android.content.Context;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import static org.zerionproject.core.util.IoUtils.deleteFileOrDir;

@NotNullByDefault
public class ProfileManager {

	public static final String DEFAULT_PROFILE_ID = "default";

	private static final String PROFILES_DIR = "profiles";
	private static final String DB_SUBDIR = "db";
	private static final String KEY_SUBDIR = "key";
	private static final String TOR_SUBDIR = "tor";
	private static final String DEVICE_TOR_DIR = "tor";
	private static final String MULTI_PROFILE_MARKER = ".multi";

	private static final String LEGACY_DB_DIR = "db";
	private static final String LEGACY_KEY_DIR = "key";
	private static final String LEGACY_TOR_DIR = "tor";

	public interface SessionListener {
		void onSessionStarted(String profileId);
	}

	private final Object lock = new Object();
	private final File filesDir;
	private final ProfileMetadataCrypto metadataCrypto =
			new ProfileMetadataCrypto();

	@GuardedBy("lock")
	private String activeProfileId = DEFAULT_PROFILE_ID;

	@GuardedBy("lock")
	@Nullable
	private String sessionProfileId = null;

	@Nullable
	private volatile SessionListener sessionListener = null;

	public ProfileManager(Context appContext) {
		this.filesDir = appContext.getFilesDir();
		migrateLegacyLayoutIfNeeded(appContext);
		moveTorStateOutOfProfiles();
		removeMultiProfileMarker();
		removeProfilesWithoutKeys();
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
			if (sessionProfileId != null
					&& !sessionProfileId.equals(profileId)) {
				throw new IllegalStateException("Profile session running");
			}
			activeProfileId = profileId;
		}
	}

	@Nullable
	public String getSessionProfileId() {
		synchronized (lock) {
			return sessionProfileId;
		}
	}

	public void startSession(String profileId) {
		SessionListener listener;
		synchronized (lock) {
			if (sessionProfileId != null) {
				if (!sessionProfileId.equals(profileId)) {
					throw new IllegalStateException(
							"Profile session running");
				}
				return;
			}
			sessionProfileId = profileId;
			activeProfileId = profileId;
			listener = sessionListener;
		}
		if (listener != null) listener.onSessionStarted(profileId);
	}

	public void setSessionListener(@Nullable SessionListener listener) {
		sessionListener = listener;
	}

	public File getProfilesRoot() {
		return new File(filesDir, PROFILES_DIR);
	}

	public File getProfileRoot(String profileId) {
		return new File(getProfilesRoot(), profileId);
	}

	public File getActiveDbDir() {
		return profileSubdir(getActiveProfileId(), DB_SUBDIR);
	}

	public File getActiveKeyDir() {
		return profileSubdir(getActiveProfileId(), KEY_SUBDIR);
	}

	public File getDbDir(String profileId) {
		return profileSubdir(profileId, DB_SUBDIR);
	}

	public File getKeyDir(String profileId) {
		return profileSubdir(profileId, KEY_SUBDIR);
	}

	public File getKeyDirWithoutCreating(String profileId) {
		return new File(getProfileRoot(profileId), KEY_SUBDIR);
	}

	public File getDeviceTorDir() {
		File dir = new File(filesDir, DEVICE_TOR_DIR);
		if (!dir.isDirectory()) dir.mkdirs();
		return dir;
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

	@Nullable
	public List<String> listProfileIdsOrNull() {
		File root = getProfilesRoot();
		if (!root.exists()) return Collections.emptyList();
		if (!root.isDirectory()) return null;
		String[] names = root.list();
		if (names == null) return null;
		List<String> out = new ArrayList<>(names.length);
		for (String n : names) {
			if (new File(root, n).isDirectory()) out.add(n);
		}
		Collections.sort(out);
		return out;
	}

	public boolean profileExists(String profileId) {
		return getProfileRoot(profileId).isDirectory();
	}

	public boolean hasKeyFiles(String profileId) {
		return getDbKeyFile(profileId).exists()
				|| getDbKeyBackupFile(profileId).exists();
	}

	public File getDbKeyFile(String profileId) {
		return new File(getKeyDirWithoutCreating(profileId), "db.key");
	}

	public File getDbKeyBackupFile(String profileId) {
		return new File(getKeyDirWithoutCreating(profileId), "db.key.bak");
	}

	public File getLockoutFile() {
		return new File(filesDir, "login.lockout");
	}

	public File getPasswordCheckLockoutFile() {
		return new File(filesDir, "password.check.lockout");
	}

	private File getLastActiveProfileFile() {
		return new File(filesDir, "last_active_profile");
	}

	@Nullable
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

	public void forgetLastActiveProfileId(String profileId) {
		if (!profileId.equals(readLastActiveProfileId())) return;
		File f = getLastActiveProfileFile();
		if (f.exists()) f.delete();
	}

	public File getDisplayNameFile(String profileId) {
		return new File(getKeyDirWithoutCreating(profileId), "display_name");
	}

	public boolean writeDisplayName(String profileId, String name) {
		if (!profileExists(profileId)) return false;
		getKeyDir(profileId);
		File f = getDisplayNameFile(profileId);
		try {
			metadataCrypto.writeEncrypted(f, name);
			return f.exists() && f.length() > 0;
		} catch (java.io.IOException e) {
			return false;
		}
	}

	@Nullable
	public String readDisplayName(String profileId) {
		File f = getDisplayNameFile(profileId);
		if (!f.exists()) return null;
		String decrypted = metadataCrypto.readEncrypted(f);
		if (decrypted != null) {
			return isReadableText(decrypted) ? decrypted : null;
		}
		String migrated = migratePlaintextDisplayName(f, profileId);
		return isReadableText(migrated) ? migrated : null;
	}

	static boolean isReadableText(@Nullable String s) {
		if (s == null || s.isEmpty()) return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == 0xFFFD) return false;
			if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') return false;
		}
		return true;
	}

	public void writeEncryptedMetaFile(String profileId, String fileName,
			String value) throws java.io.IOException {
		if (!profileExists(profileId)) {
			throw new java.io.IOException("No such profile");
		}
		File f = new File(getKeyDir(profileId), fileName);
		metadataCrypto.writeEncrypted(f, value);
	}

	@Nullable
	public String readEncryptedMetaFile(String profileId, String fileName) {
		File f = new File(getKeyDirWithoutCreating(profileId), fileName);
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
		File f = new File(getKeyDirWithoutCreating(profileId), fileName);
		if (f.exists()) f.delete();
	}

	void deleteProfileMetadataKey() {
		metadataCrypto.deleteKey();
	}

	@Nullable
	private String readLegacyPlaintext(File f) {
		try (java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader(new java.io.FileInputStream(f),
						java.nio.charset.StandardCharsets.UTF_8))) {
			String line = r.readLine();
			return isReadableText(line) ? line : null;
		} catch (java.io.IOException e) {
			return null;
		}
	}

	@Nullable
	private String migratePlaintextDisplayName(File f, String profileId) {
		String legacy;
		try (java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader(new java.io.FileInputStream(f),
						java.nio.charset.StandardCharsets.UTF_8))) {
			legacy = r.readLine();
		} catch (java.io.IOException e) {
			return null;
		}
		if (!isReadableText(legacy)) return null;
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
		return true;
	}

	public void secureWipeProfile(String profileId) {
		File root = getProfileRoot(profileId);
		if (!root.exists()) return;
		secureWipeRecursive(root);
	}

	public void shredProfileKeys(String profileId) {
		File keyDir = getKeyDirWithoutCreating(profileId);
		if (keyDir.exists()) secureWipeRecursive(keyDir);
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

	private File profileSubdir(String profileId, String name) {
		File root = getProfileRoot(profileId);
		File dir = new File(root, name);
		if (!dir.isDirectory() && root.isDirectory()) {
			dir.mkdirs();
		}
		return dir;
	}

	private void moveTorStateOutOfProfiles() {
		File legacy = new File(getProfileRoot(DEFAULT_PROFILE_ID), TOR_SUBDIR);
		if (!legacy.isDirectory()) return;
		File device = new File(filesDir, DEVICE_TOR_DIR);
		if (!device.exists() && legacy.renameTo(device)) return;
		deleteFileOrDir(legacy);
	}

	private void removeMultiProfileMarker() {
		File marker = new File(getProfilesRoot(), MULTI_PROFILE_MARKER);
		if (marker.exists()) marker.delete();
	}

	private boolean anyProfileHasKeyFiles() {
		File[] dirs = getProfilesRoot().listFiles();
		if (dirs == null) return false;
		for (File dir : dirs) {
			if (dir.isDirectory() && hasKeyFiles(dir.getName())) return true;
		}
		return false;
	}

	private void removeProfilesWithoutKeys() {
		File[] dirs = getProfilesRoot().listFiles();
		if (dirs == null) return;
		boolean anyProfileHasKeys = anyProfileHasKeyFiles();
		for (File dir : dirs) {
			if (!dir.isDirectory()) continue;
			if (!hasKeyFiles(dir.getName())) {
				if (anyProfileHasKeys || !containsAnyFile(dir)) {
					secureWipeRecursive(dir);
				}
				continue;
			}
			File tor = new File(dir, TOR_SUBDIR);
			if (tor.isDirectory() && !containsAnyFile(tor)) {
				deleteFileOrDir(tor);
			}
		}
	}

	private static boolean containsAnyFile(File dir) {
		File[] children = dir.listFiles();
		if (children == null) return true;
		for (File c : children) {
			if (!c.isDirectory() || containsAnyFile(c)) return true;
		}
		return false;
	}

	private void migrateLegacyLayoutIfNeeded(Context appContext) {
		File profilesRoot = getProfilesRoot();
		if (profilesRoot.exists() && anyProfileHasKeyFiles()) return;

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
