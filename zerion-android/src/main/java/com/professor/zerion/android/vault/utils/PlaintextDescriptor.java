package com.professor.zerion.android.vault.utils;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

@NotNullByDefault
public final class PlaintextDescriptor {

	private PlaintextDescriptor() {
	}

	interface MemoryFiles {
		ParcelFileDescriptor holding(byte[] plaintext)
				throws IOException, ErrnoException;
	}

	public static ParcelFileDescriptor open(byte[] plaintext, File cacheDir)
			throws IOException {
		return open(plaintext, cacheDir, Build.VERSION.SDK_INT,
				PlaintextDescriptor::inMemory);
	}

	static ParcelFileDescriptor open(byte[] plaintext, File cacheDir,
			int sdkInt, MemoryFiles memory) throws IOException {
		if (sdkInt >= 30) {
			try {
				return memory.holding(plaintext);
			} catch (IOException | ErrnoException | RuntimeException
					| LinkageError e) {
				return unlinked(plaintext, cacheDir);
			}
		}
		return unlinked(plaintext, cacheDir);
	}

	@androidx.annotation.RequiresApi(30)
	private static ParcelFileDescriptor inMemory(byte[] plaintext)
			throws IOException, ErrnoException {
		FileDescriptor fd = Os.memfd_create("vault_view", 0);
		try {
			int off = 0;
			while (off < plaintext.length) {
				off += Os.write(fd, plaintext, off, plaintext.length - off);
			}
			Os.lseek(fd, 0, OsConstants.SEEK_SET);
			return ParcelFileDescriptor.dup(fd);
		} finally {
			Os.close(fd);
		}
	}

	private static ParcelFileDescriptor unlinked(byte[] plaintext,
			File cacheDir) throws IOException {
		File f = File.createTempFile("vview", null, cacheDir);
		try {
			try (java.io.FileOutputStream out =
					new java.io.FileOutputStream(f)) {
				out.write(plaintext);
			}
			return ParcelFileDescriptor.open(f,
					ParcelFileDescriptor.MODE_READ_ONLY);
		} finally {
			if (!f.delete() && f.exists()) f.deleteOnExit();
		}
	}
}
