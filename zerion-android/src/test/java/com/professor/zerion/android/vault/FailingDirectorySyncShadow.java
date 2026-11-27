package com.professor.zerion.android.vault;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.FileDescriptor;

@Implements(Os.class)
public class FailingDirectorySyncShadow {

	static volatile boolean fail = false;

	@Implementation
	protected static FileDescriptor open(String path, int flags, int mode) {
		return new FileDescriptor();
	}

	@Implementation
	protected static void fsync(FileDescriptor fd) throws ErrnoException {
		if (fail) throw new ErrnoException("fsync", OsConstants.EIO);
	}

	@Implementation
	protected static void close(FileDescriptor fd) {
	}
}
