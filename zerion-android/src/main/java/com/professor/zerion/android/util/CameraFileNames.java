package com.professor.zerion.android.util;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.UUID;

@NotNullByDefault
public final class CameraFileNames {

	private CameraFileNames() {
	}

	public static String random(String prefix, String extension) {
		return prefix + UUID.randomUUID() + extension;
	}
}
