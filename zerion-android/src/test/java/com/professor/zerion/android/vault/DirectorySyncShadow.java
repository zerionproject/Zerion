package com.professor.zerion.android.vault;

import android.system.Os;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.FileDescriptor;

@Implements(Os.class)
public class DirectorySyncShadow {

	@Implementation
	protected static FileDescriptor open(String path, int flags, int mode) {
		return new FileDescriptor();
	}

	@Implementation
	protected static void fsync(FileDescriptor fd) {
	}

	@Implementation
	protected static void close(FileDescriptor fd) {
	}
}
