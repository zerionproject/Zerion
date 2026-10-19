package com.professor.zerion.android.util;

import android.content.Context;

import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@NotNullByDefault
public final class CacheSweeper {

	private static final String[] TEMP_FILE_PREFIXES = {
			"vault_pdf_",
			"vview",
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
			"pdf_clean_",
	};

	private static final String[] TEMP_DIRS = {
			"vault_share",
			"zenc_share",
			"media_docs",
			"camera_photos",
			"grouptr_view",
			"channel_attach_view",
			ExternalHandoff.DIR,
	};

	private static final String[] FILES_DIRS = {
			"camera",
			"camera_photos",
	};

	private static final String[] NO_BACKUP_DIRS = {
			"channel_attach_view",
	};

	private static final String[] PRIVATE_TEMP_DIRS = {
			"media_docs",
			"grouptr_view",
			"channel_attach_view",
	};

	static final String[] DATA_DIRS = {
			"app_webview",
			"app_textures",
	};

	private static final Set<String> IN_USE =
			Collections.newSetFromMap(new ConcurrentHashMap<>());

	private CacheSweeper() {
	}

	public static File claim(File f) {
		IN_USE.add(f.getAbsolutePath());
		return f;
	}

	public static void release(File f) {
		IN_USE.remove(f.getAbsolutePath());
	}

	public static void sweepAsync(Context ctx) {
		Context app = ctx.getApplicationContext();
		new Thread(() -> sweep(app), "CacheSweep").start();
	}

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
		com.professor.zerion.android.vault.share.VaultShareRegistry
				.releaseAll();
		com.professor.zerion.android.attachment.SaveCleanCopy.clearAll();
		com.professor.zerion.android.conversation.MediaViewer.clearAll();
		ExternalHandoff.end(ctx);
		sweepFilesDirs(ctx);
		sweepNoBackupDirs(ctx);
		sweepDataDirs(ctx);
		File cache = ctx.getCacheDir();
		if (cache == null || !cache.isDirectory()) return;
		try {
			File[] files = cache.listFiles();
			if (files != null) {
				for (File f : files) {
					if (f.isFile() && hasTempPrefix(f.getName())
							&& !inUse(f)) {
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

	public static void sweepLeftovers(Context ctx, long processStartedAt) {
		File cache = ctx.getCacheDir();
		if (cache != null && cache.isDirectory()) {
			try {
				File[] files = cache.listFiles();
				if (files != null) {
					for (File f : files) {
						if (f.isFile() && hasTempPrefix(f.getName())
								&& f.lastModified() < processStartedAt
								&& !inUse(f)) {
							SecureMemory.secureDeleteFile(f, 0L, false);
						}
					}
				}
			} catch (SecurityException ignored) {
			}
			for (String dir : PRIVATE_TEMP_DIRS) {
				SecureMemory.secureDeleteDir(new File(cache, dir), 0L);
			}
		}
		sweepNoBackupDirs(ctx);
	}

	public static void sweepFilesDirs(Context ctx) {
		File files = ctx.getFilesDir();
		if (files == null) return;
		for (String dir : FILES_DIRS) {
			SecureMemory.secureDeleteDir(new File(files, dir), 0L);
		}
	}

	public static void sweepNoBackupDirs(Context ctx) {
		File noBackup = ctx.getNoBackupFilesDir();
		if (noBackup == null) return;
		for (String dir : NO_BACKUP_DIRS) {
			SecureMemory.secureDeleteDir(new File(noBackup, dir), 0L);
		}
	}

	public static void sweepDataDirs(Context ctx) {
		String data = ctx.getApplicationInfo().dataDir;
		if (data == null) return;
		for (String dir : DATA_DIRS) {
			SecureMemory.secureDeleteDir(new File(data, dir), 0L);
		}
		SecureMemory.secureDeleteDir(new File(ctx.getCacheDir(), "WebView"),
				0L);
	}

	private static boolean inUse(File f) {
		return IN_USE.contains(f.getAbsolutePath());
	}

	private static boolean hasTempPrefix(String name) {
		for (String p : TEMP_FILE_PREFIXES) {
			if (name.startsWith(p)) return true;
		}
		return false;
	}
}
