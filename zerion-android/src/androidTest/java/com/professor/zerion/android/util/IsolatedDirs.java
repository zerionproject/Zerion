package com.professor.zerion.android.util;

import android.content.Context;
import android.content.ContextWrapper;
import android.system.ErrnoException;
import android.system.Os;

import com.professor.zerion.android.vault.utils.SecureMemory;

import java.io.File;

import androidx.test.platform.app.InstrumentationRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

final class IsolatedDirs extends ContextWrapper {

	private final File root;

	IsolatedDirs(String name) {
		super(InstrumentationRegistry.getInstrumentation().getTargetContext());
		root = new File(getBaseContext().getCacheDir(), "device-test-" + name);
		assertTrue(root.isDirectory() || root.mkdirs());
	}

	File root() {
		return root;
	}

	private File dir(String name) {
		File d = new File(root, name);
		assertTrue(d.isDirectory() || d.mkdirs());
		return d;
	}

	@Override
	public Context getApplicationContext() {
		return this;
	}

	@Override
	public File getCacheDir() {
		return dir("cache");
	}

	@Override
	public File getNoBackupFilesDir() {
		return dir("no_backup");
	}

	@Override
	public File getFilesDir() {
		return dir("files");
	}

	@Override
	public File getExternalCacheDir() {
		return null;
	}

	void remove() {
		SecureMemory.secureDeleteDir(root, 0L);
	}

	static void assertPrivate(File f) throws ErrnoException {
		int mode = Os.stat(f.getAbsolutePath()).st_mode;
		assertEquals(f.getName() + " is readable by others: "
				+ Integer.toOctalString(mode & 0777), 0, mode & 077);
	}
}
