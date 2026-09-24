package org.zerionproject.core.db;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;

/**
 * File operations behind the open policy, kept free of platform classes so
 * that they can be exercised on the JVM with temporary directories.
 */
@NotNullByDefault
final class SqlCipherRecoveryFiles {

	static final String SETUP_MARKER = "db.setup-incomplete";
	private static final String[] SUFFIXES = {"", "-wal", "-shm", "-journal"};

	private SqlCipherRecoveryFiles() {
	}

	static File setupMarker(File dir) {
		return new File(dir, SETUP_MARKER);
	}

	static boolean markSetupIncomplete(File dir) {
		try {
			return setupMarker(dir).createNewFile()
					|| setupMarker(dir).exists();
		} catch (IOException e) {
			return false;
		}
	}

	static void markSetupComplete(File dir) {
		File marker = setupMarker(dir);
		if (marker.exists() && !marker.delete()) marker.deleteOnExit();
	}

	/**
	 * Removes a database that was opened and found to hold no identity. The
	 * caller has proven that the files contain no user data.
	 */
	static void deleteEmpty(File dbFile) {
		for (String suffix : SUFFIXES) {
			File f = new File(dbFile.getPath() + suffix);
			if (f.exists()) f.delete();
		}
	}

	/**
	 * Moves a database that could not be opened, together with its
	 * write-ahead log, shared memory and rollback journal, to a timestamped
	 * name next to it so that nothing is destroyed and recovery remains
	 * possible. Returns false if any file could not be moved, in which case
	 * the caller must not create a fresh database over it.
	 */
	static boolean quarantine(File dbFile, long timestamp) {
		String tag = ".incomplete-" + timestamp;
		boolean ok = true;
		for (String suffix : SUFFIXES) {
			File f = new File(dbFile.getPath() + suffix);
			if (!f.exists()) continue;
			File target = new File(dbFile.getPath() + tag + suffix);
			if (!f.renameTo(target)) ok = false;
		}
		return ok && !dbFile.exists();
	}
}
