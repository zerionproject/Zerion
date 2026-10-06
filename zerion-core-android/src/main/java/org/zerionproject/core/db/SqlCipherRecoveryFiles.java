package org.zerionproject.core.db;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;

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

	static void deleteEmpty(File dbFile) {
		for (String suffix : SUFFIXES) {
			File f = new File(dbFile.getPath() + suffix);
			if (f.exists()) f.delete();
		}
	}

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
