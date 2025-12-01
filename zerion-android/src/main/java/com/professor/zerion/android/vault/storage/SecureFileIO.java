package com.professor.zerion.android.vault.storage;

import android.content.Context;
import android.os.Build;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.util.Arrays;

@NotNullByDefault
public class SecureFileIO {

	private static final int BUFFER_SIZE = 64 * 1024;
	private static final long FIXED_TIMESTAMP = 946684800000L;

	private final Context context;
	private final File vaultDir;
	private final SecureRandom random = new SecureRandom();
	private volatile boolean initialized = false;

	public SecureFileIO(Context context) {
		this.context = context;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			this.vaultDir = new File(context.getNoBackupFilesDir(), "vault");
		} else {
			this.vaultDir = new File(context.getFilesDir(), "vault");
		}
	}

	private void validateFilename(String filename) {
		if (filename == null || filename.trim().isEmpty()) {
			throw new SecurityException("Invalid filename: empty");
		}

		if (".".equals(filename)) {
			return;
		}

		if (filename.contains("..")) {
			throw new SecurityException("Invalid filename: path traversal detected");
		}

		if (filename.contains("\\")) {
			throw new SecurityException("Invalid filename: invalid path separator");
		}

		if (filename.contains("\0")) {
			throw new SecurityException("Invalid filename: null byte detected");
		}

		if (new File(filename).isAbsolute()) {
			throw new SecurityException("Invalid filename: absolute path not allowed");
		}

		String[] parts = filename.split("/");
		for (String part : parts) {
			if (part.isEmpty() || part.equals(".")) {
				continue;
			}
			if (part.startsWith(".")) {
				throw new SecurityException("Invalid filename: hidden file in path");
			}
		}

		File resolvedFile = new File(vaultDir, filename);
		try {
			String canonicalPath = resolvedFile.getCanonicalPath();
			String vaultCanonicalPath = vaultDir.getCanonicalPath();
			if (!canonicalPath.startsWith(vaultCanonicalPath)) {
				throw new SecurityException("Invalid filename: path escapes vault directory");
			}
		} catch (IOException e) {
			throw new SecurityException("Invalid filename: cannot resolve path");
		}
	}

	private void ensureInitialized() {
		if (!initialized) {
			synchronized (this) {
				if (!initialized) {
					initializeVaultDirectory();
					initialized = true;
				}
			}
		}
	}

	private void initializeVaultDirectory() {
		if (!vaultDir.exists()) {
			if (!vaultDir.mkdirs()) {
				throw new RuntimeException("Failed to create vault directory");
			}
		}

		vaultDir.setReadable(false, false);
		vaultDir.setReadable(true, true);
		vaultDir.setWritable(false, false);
		vaultDir.setWritable(true, true);
		vaultDir.setExecutable(false, false);
		vaultDir.setExecutable(true, true);
	}

	public void writeSecure(String filename, byte[] data) throws IOException {
		ensureInitialized();
		validateFilename(filename);
		File file = new File(vaultDir, filename);
		File tempFile = new File(vaultDir, filename + ".tmp" + random.nextInt());

		FileOutputStream fos = null;
		BufferedOutputStream bos = null;
		FileChannel channel = null;

		try {
			fos = new FileOutputStream(tempFile);
			channel = fos.getChannel();
			bos = new BufferedOutputStream(fos, BUFFER_SIZE);

			bos.write(data);
			bos.flush();

			channel.force(true);

			bos.close();
			bos = null;
			channel.close();
			channel = null;
			fos.close();
			fos = null;

			if (!tempFile.renameTo(file)) {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					Files.move(tempFile.toPath(), file.toPath(),
							StandardCopyOption.ATOMIC_MOVE,
							StandardCopyOption.REPLACE_EXISTING);
				} else {
					throw new IOException("Failed to rename temp file");
				}
			}

			normalizeFileTimestamp(file);

			syncDirectory();


		} finally {
			if (bos != null) try { bos.close(); } catch (IOException ignored) {}
			if (channel != null) try { channel.close(); } catch (IOException ignored) {}
			if (fos != null) try { fos.close(); } catch (IOException ignored) {}

			if (tempFile.exists()) {
				secureDelete(tempFile);
			}
		}
	}

	public byte[] readSecure(String filename) throws IOException {
		ensureInitialized();
		validateFilename(filename);
		File file = new File(vaultDir, filename);
		if (!file.exists()) {
			throw new IOException("File not found: " + filename);
		}

		FileInputStream fis = null;
		BufferedInputStream bis = null;

		try {
			fis = new FileInputStream(file);
			bis = new BufferedInputStream(fis, BUFFER_SIZE);

			byte[] data = new byte[(int) file.length()];
			int totalRead = 0;
			int read;

			while (totalRead < data.length) {
				read = bis.read(data, totalRead, data.length - totalRead);
				if (read == -1) {
					throw new IOException("Unexpected end of file");
				}
				totalRead += read;
			}

			return data;

		} finally {
			if (bis != null) try { bis.close(); } catch (IOException ignored) {}
			if (fis != null) try { fis.close(); } catch (IOException ignored) {}
		}
	}

	public void secureDelete(String filename) throws IOException {
		ensureInitialized();
		validateFilename(filename);
		File file = new File(vaultDir, filename);
		secureDelete(file);
	}

	private void secureDelete(File file) throws IOException {
		if (!file.exists()) return;

		RandomAccessFile raf = null;
		try {
			raf = new RandomAccessFile(file, "rw");
			FileChannel channel = raf.getChannel();
			long length = raf.length();
			byte[] randomBuffer = new byte[BUFFER_SIZE];

			boolean isLargeFile = length > 10 * 1024 * 1024;

			if (isLargeFile) {

				long bytesToOverwrite = Math.min(1024 * 1024, length);
				for (long pos = 0; pos < bytesToOverwrite; pos += BUFFER_SIZE) {
					this.random.nextBytes(randomBuffer);
					int toWrite = (int) Math.min(BUFFER_SIZE, bytesToOverwrite - pos);
					raf.write(randomBuffer, 0, toWrite);
				}

				if (length > 1024 * 1024) {
					raf.seek(length - 1024 * 1024);
					for (long pos = 0; pos < 1024 * 1024; pos += BUFFER_SIZE) {
						this.random.nextBytes(randomBuffer);
						int toWrite = (int) Math.min(BUFFER_SIZE, 1024 * 1024 - pos);
						raf.write(randomBuffer, 0, toWrite);
					}
				}

			} else {
				for (long pos = 0; pos < length; pos += BUFFER_SIZE) {
					this.random.nextBytes(randomBuffer);
					int toWrite = (int) Math.min((long)BUFFER_SIZE, length - pos);
					raf.write(randomBuffer, 0, toWrite);
				}
			}

			channel.force(true);

			Arrays.fill(randomBuffer, (byte) 0);

		} finally {
			if (raf != null) try { raf.close(); } catch (IOException ignored) {}
		}

		if (!file.delete()) {
		}

		syncDirectory();
	}

	public String[] listFiles(String subdirectory) {
		ensureInitialized();
		if (subdirectory != null) {
			validateFilename(subdirectory);
		}

		File dir = subdirectory != null ?
				new File(vaultDir, subdirectory) : vaultDir;

		if (!dir.exists() || !dir.isDirectory()) {
			return new String[0];
		}

		String[] files = dir.list();
		return files != null ? files : new String[0];
	}

	public void createDirectory(String path) throws IOException {
		ensureInitialized();
		validateFilename(path);
		File dir = new File(vaultDir, path);
		if (!dir.exists()) {
			if (!dir.mkdirs()) {
				throw new IOException("Failed to create directory: " + path);
			}
			normalizeFileTimestamp(dir);
		}
	}

	public boolean exists(String filename) {
		try {
			validateFilename(filename);
			return new File(vaultDir, filename).exists();
		} catch (SecurityException e) {
			return false;
		}
	}

	public File getVaultDir() {
		return vaultDir;
	}

	public long getFileSize(String filename) {
		try {
			validateFilename(filename);
			File file = new File(vaultDir, filename);
			return file.exists() ? file.length() : 0;
		} catch (SecurityException e) {
			return 0;
		}
	}

	private void normalizeFileTimestamp(File file) {
		file.setLastModified(FIXED_TIMESTAMP);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			try {
				BasicFileAttributeView view = Files.getFileAttributeView(
						file.toPath(), BasicFileAttributeView.class);
				FileTime fixedTime = FileTime.fromMillis(FIXED_TIMESTAMP);
				view.setTimes(fixedTime, fixedTime, fixedTime);
			} catch (IOException e) {
			}
		}
	}

	private void syncDirectory() throws IOException {
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(new File(vaultDir, "."));
			fos.getFD().sync();
		} catch (IOException e) {
		} finally {
			if (fos != null) try { fos.close(); } catch (IOException ignored) {}
		}
	}

	public void deleteDirectory(String path) throws IOException {
		ensureInitialized();
		validateFilename(path);
		File dir = new File(vaultDir, path);
		deleteDirectoryInternal(dir);
	}

	private void deleteDirectoryInternal(File dir) throws IOException {
		if (!dir.exists()) return;

		File[] files = dir.listFiles();
		if (files != null) {
			for (File f : files) {
				if (f.isDirectory()) {
					deleteDirectoryInternal(f);
				} else {
					secureDelete(f);
				}
			}
		}

		if (!dir.delete()) {
			throw new IOException("Failed to delete directory: " + dir.getAbsolutePath());
		}

		syncDirectory();
	}

	public void wipeVault() throws IOException {
		ensureInitialized();
		wipeDirectory(vaultDir);

		initializeVaultDirectory();
	}

	private void wipeDirectory(File dir) throws IOException {
		if (!dir.exists()) return;

		File[] files = dir.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isDirectory()) {
					wipeDirectory(file);
				} else {
					secureDelete(file);
				}
			}
		}

		if (!vaultDir.equals(dir)) {
			dir.delete();
		}
	}
}