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
			"jpg_meta",
			"vid_in_",
			"vid_clean_",
			"vid_remux_",
			"zbk-",
	};

	private static final String[] TEMP_DIRS = {
			"vault_share",
			"zenc_share",
			"media_docs",
			"camera_photos",
			"grouptr_view",
	};

	private static final String[] FILES_DIRS = {
			"camera",
			"camera_photos",
	};

	private CacheSweeper() {
	}

	/** Sweeps on a background thread; used at lock and sign-out. */
	public static void sweepAsync(Context ctx) {
		Context app = ctx.getApplicationContext();
		new Thread(() -> sweep(app), "CacheSweep").start();
	}

	/**
	 * Removes one temporary directory under the cache on a background
	 * thread; used when the content it held has been consumed or the
	 * store it came from has locked.
	 */
	public static void sweepDirAsync(Context ctx, String name) {
		Context app = ctx.getApplicationContext();
		new Thread(() -> sweepDir(app, name), "CacheSweep-" + name).start();
	}

	public static void sweepDir(Context ctx, String name) {
		File cache = ctx.getCacheDir();
		if (cache == null) return;
		SecureMemory.secureDeleteDir(new File(cache, name), 0L);
	}

	public static void sweep(Context ctx) {
		sweepFilesDirs(ctx);
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

	public static void sweepFilesDirs(Context ctx) {
		File files = ctx.getFilesDir();
		if (files == null) return;
		for (String dir : FILES_DIRS) {
			SecureMemory.secureDeleteDir(new File(files, dir), 0L);
		}
	}

	private static boolean hasTempPrefix(String name) {
		for (String p : TEMP_FILE_PREFIXES) {
			if (name.startsWith(p)) return true;
		}
		return false;
	}
}
