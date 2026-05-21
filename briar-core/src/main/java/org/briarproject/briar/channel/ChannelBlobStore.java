package org.briarproject.briar.channel;

import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class ChannelBlobStore {

	private static final String DIR_NAME = "channel-blobs";

	private final File rootDir;

	@Inject
	ChannelBlobStore(DatabaseConfig dbConfig) {
		this.rootDir = new File(
				dbConfig.getDatabaseDirectory().getParentFile(),
				DIR_NAME);
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
		return new File(channelDir(channelId),
				hex(blobHash) + ".bin").exists();
	}

	void removeBlob(byte[] channelId, byte[] blobHash) {
		File f = new File(channelDir(channelId),
				hex(blobHash) + ".bin");
		if (f.exists()) {
			f.delete();
		}
	}

	void removeAllForChannel(byte[] channelId) {
		File dir = channelDir(channelId);
		File[] children = dir.listFiles();
		if (children == null) return;
		for (File f : children) {
			f.delete();
		}
		dir.delete();
	}

	private File channelDir(byte[] channelId) {
		return new File(rootDir, hex(channelId));
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
		return sb.toString();
	}
}
