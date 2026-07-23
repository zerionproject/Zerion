package com.professor.zerion.android.util;

import android.content.Context;

import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;

@NotNullByDefault
public final class CacheSweeper {

	private static final String[] TEMP_FILE_PREFIXES = {
			"vault_pdf_",
			"video_thumb_",
			"zerion_video_",
			"voice",
			"grouptr_vid_thumb_",
			"grouptr_voice_",
	};

	private static final String[] TEMP_DIRS = {
			"vault_share",
			"grouptr_view",
	};

	private CacheSweeper() {
	}

	public static void sweep(Context ctx) {
		File cache = ctx.getCacheDir();
		if (cache == null || !cache.isDirectory()) return;
		try {
			File[] files = cache.listFiles();
			if (files != null) {
				for (File f : files) {
					if (f.isFile() && hasTempPrefix(f.getName())) {
						SecureMemory.secureDeleteFile(f, 0L, false);
					}
				}
			}
		} catch (SecurityException ignored) {
		}
		for (String dir : TEMP_DIRS) {
			SecureMemory.secureDeleteDir(new File(cache, dir), 0L);
		}
	}

	private static boolean hasTempPrefix(String name) {
		for (String p : TEMP_FILE_PREFIXES) {
			if (name.startsWith(p)) return true;
		}
		return false;
	}
}
