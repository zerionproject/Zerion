package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.tor.TorStateStore.StoredState;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

import javax.annotation.Nullable;

@NotNullByDefault
final class TorStatePersistence {

	static final String STATE_FILE = "state";
	static final String LOCK_FILE = "lock";
	static final int MAX_STATE_BYTES = 256 * 1024;
	private static final int SHRED_BUFFER = 64 * 1024;
	private static final long MAX_SHRED_BYTES = 64L * 1024 * 1024;

	private final File dataDirectory;
	private final TorStateStore store;

	TorStatePersistence(File dataDirectory, TorStateStore store) {
		this.dataDirectory = dataDirectory;
		this.store = store;
	}

	void restore() {
		File state = new File(dataDirectory, STATE_FILE);
		StoredState stored = loadQuietly();
		if (state.isFile()) {
			long diskModified = state.lastModified();
			if (stored == null || diskModified > stored.modifiedMs) {
				byte[] bytes = readIfSmall(state);
				if (bytes != null) {
					StoredState imported = new StoredState(bytes, diskModified);
					if (saveQuietly(imported)) {
						stored = imported;
					} else {
						wipeContents(dataDirectory, true);
						return;
					}
				}
			}
		}
		wipeContents(dataDirectory);
		if (stored == null) return;
		if (!dataDirectory.isDirectory() && !dataDirectory.mkdirs()
				&& !dataDirectory.isDirectory()) {
			return;
		}
		ownerOnly(dataDirectory);
		try (FileOutputStream out = new FileOutputStream(state)) {
			out.write(stored.bytes);
			out.flush();
			out.getFD().sync();
		} catch (IOException e) {
			shred(state);
			return;
		}
		ownerOnly(state);
	}

	void persist() {
		File state = new File(dataDirectory, STATE_FILE);
		if (state.isFile()) {
			byte[] bytes = readIfSmall(state);
			if (bytes != null) {
				saveQuietly(new StoredState(bytes, state.lastModified()));
			}
		}
		wipeContents(dataDirectory);
	}

	@Nullable
	private StoredState loadQuietly() {
		try {
			StoredState s = store.load();
			if (s == null || s.bytes.length == 0
					|| s.bytes.length > MAX_STATE_BYTES) {
				return null;
			}
			return s;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private boolean saveQuietly(StoredState state) {
		try {
			return store.save(state);
		} catch (RuntimeException e) {
			return false;
		}
	}

	@Nullable
	private static byte[] readIfSmall(File f) {
		long len = f.length();
		if (len <= 0 || len > MAX_STATE_BYTES) return null;
		byte[] b = new byte[(int) len];
		try (FileInputStream in = new FileInputStream(f)) {
			int offset = 0;
			while (offset < b.length) {
				int read = in.read(b, offset, b.length - offset);
				if (read == -1) return null;
				offset += read;
			}
			return b;
		} catch (IOException e) {
			return null;
		}
	}

	static void wipeContents(File dir) {
		wipeContents(dir, false);
	}

	private static void wipeContents(File dir, boolean keepState) {
		File[] children = dir.listFiles();
		if (children == null) return;
		for (File child : children) {
			if (child.isDirectory()) {
				wipeContents(child, false);
				child.delete();
			} else if (keepState && child.getName().equals(STATE_FILE)) {
				continue;
			} else if (!isKeptAcrossRuns(child)) {
				shred(child);
			}
		}
	}

	static boolean isKeptAcrossRuns(File f) {
		String name = f.getName();
		return name.equals(LOCK_FILE) || name.startsWith("cached-certs")
				|| name.startsWith("cached-microdesc");
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

	private static void ownerOnly(File f) {
		f.setReadable(false, false);
		f.setWritable(false, false);
		f.setReadable(true, true);
		f.setWritable(true, true);
		if (f.isDirectory()) {
			f.setExecutable(false, false);
			f.setExecutable(true, true);
		}
	}
}
