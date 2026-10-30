package org.zerionproject.core.db;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.SecureRandom;

@NotNullByDefault
final class SqlCipherRecoveryFiles {

	static final String SETUP_MARKER = "db.setup-incomplete";
	static final String QUARANTINE_TAG = ".incomplete-";
	private static final String[] SUFFIXES = {"", "-wal", "-shm", "-journal"};
	private static final int SUFFIX_BYTES = 8;
	private static final int SHRED_BUFFER = 64 * 1024;
	private static final long MAX_SHRED_BYTES = 256L * 1024 * 1024;

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

	static String randomSuffix() {
		byte[] b = new byte[SUFFIX_BYTES];
		new SecureRandom().nextBytes(b);
		StringBuilder sb = new StringBuilder(SUFFIX_BYTES * 2);
		for (byte v : b) sb.append(String.format("%02x", v));
		return sb.toString();
	}

	static boolean quarantine(File dbFile) {
		return quarantine(dbFile, randomSuffix());
	}

	static boolean quarantine(File dbFile, String suffixTag) {
		String tag = QUARANTINE_TAG + suffixTag;
		boolean ok = true;
		for (String suffix : SUFFIXES) {
			File f = new File(dbFile.getPath() + suffix);
			if (!f.exists()) continue;
			File target = new File(dbFile.getPath() + tag + suffix);
			if (!f.renameTo(target)) ok = false;
		}
		return ok && !dbFile.exists();
	}

	static void shredQuarantined(File dbFile) {
		File dir = dbFile.getAbsoluteFile().getParentFile();
		if (dir == null) return;
		File[] files = dir.listFiles();
		if (files == null) return;
		String prefix = dbFile.getName() + QUARANTINE_TAG;
		for (File f : files) {
			if (f.isFile() && f.getName().startsWith(prefix)) shred(f);
		}
	}

	static void shred(File f) {
		try {
			long len = f.length();
			if (len > 0 && len <= MAX_SHRED_BYTES) {
				try (RandomAccessFile raf = new RandomAccessFile(f, "rws")) {
					byte[] zeros =
							new byte[(int) Math.min(len, SHRED_BUFFER)];
					raf.seek(0);
					long written = 0;
					while (written < len) {
						int chunk = (int) Math.min(zeros.length, len - written);
						raf.write(zeros, 0, chunk);
						written += chunk;
					}
					raf.getFD().sync();
				}
			}
		} catch (IOException | RuntimeException ignored) {
		}
		if (!f.delete()) f.deleteOnExit();
	}
}
