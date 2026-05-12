package com.professor.zerion.android.profile;

import android.content.Context;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;

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

	private File ensureDir(File f) {
		if (!f.exists()) {
			//noinspection ResultOfMethodCallIgnored
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
			//noinspection ResultOfMethodCallIgnored
			profilesRoot.mkdirs();
			return;
		}

		File targetRoot = new File(profilesRoot, DEFAULT_PROFILE_ID);
		//noinspection ResultOfMethodCallIgnored
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
			//noinspection ResultOfMethodCallIgnored
			dst.mkdirs();
			File[] children = src.listFiles();
			if (children == null) return;
			for (File child : children) {
				copyTreeBestEffort(child, new File(dst, child.getName()));
			}
			//noinspection ResultOfMethodCallIgnored
			src.delete();
		} else {
			try (java.io.FileInputStream in = new java.io.FileInputStream(src);
					java.io.FileOutputStream out =
							new java.io.FileOutputStream(dst)) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
				out.getFD().sync();
				//noinspection ResultOfMethodCallIgnored
				src.delete();
			} catch (java.io.IOException ignored) {
			}
		}
	}
}
