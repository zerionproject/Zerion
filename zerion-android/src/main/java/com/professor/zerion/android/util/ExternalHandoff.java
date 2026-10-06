package com.professor.zerion.android.util;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.MimeTypeMap;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import androidx.core.content.FileProvider;

@NotNullByDefault
public final class ExternalHandoff {

	public static final String DIR = "external_view";

	private static final Object LOCK = new Object();
	private static final SecureRandom RANDOM = new SecureRandom();

	@Nullable
	private static File staged;
	@Nullable
	private static Uri granted;
	private static boolean away;
	@Nullable
	private static WeakReference<Application> watched;

	static Executor wiper = Executors.newSingleThreadExecutor();

	static Revoker revoker = (ctx, uri) -> ctx.revokeUriPermission(uri,
			Intent.FLAG_GRANT_READ_URI_PERMISSION);

	interface Revoker {

		void revoke(Context ctx, Uri uri);
	}

	private ExternalHandoff() {
	}

	public static boolean asksFirst(String type) {
		if (ExternalViewerTypes.TEXT.equals(type)) return false;
		return !(type.startsWith("image/") || type.startsWith("video/")
				|| type.startsWith("audio/"));
	}

	public static void open(Activity activity, Executor io, byte[] content,
			String type, Runnable onFailure) {
		if (!asksFirst(type)) {
			stageAndLaunch(activity, io, content, type, onFailure);
			return;
		}
		new SecureAlertDialogBuilder(activity)
				.setTitle(R.string.external_open_title)
				.setMessage(R.string.external_open_message)
				.setPositiveButton(R.string.external_open_confirm, (d, w) ->
						stageAndLaunch(activity, io, content, type,
								onFailure))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static void stageAndLaunch(Activity activity, Executor io,
			byte[] content, String type, Runnable onFailure) {
		Context app = activity.getApplicationContext();
		io.execute(() -> {
			File file;
			try {
				file = stage(app, content, type);
			} catch (IOException e) {
				activity.runOnUiThread(onFailure);
				return;
			}
			activity.runOnUiThread(() -> {
				if (activity.isFinishing() || activity.isDestroyed()) {
					end(app);
				} else if (!launch(activity, file, type)) {
					end(app);
					onFailure.run();
				}
			});
		});
	}

	static File stage(Context ctx, byte[] content, String type)
			throws IOException {
		end(ctx);
		synchronized (LOCK) {
			File dir = new File(ctx.getCacheDir(), DIR);
			File[] stray = dir.listFiles();
			if (stray != null) {
				for (File f : stray) {
					if (f.isDirectory()) SecureMemory.secureDeleteDir(f, 0L);
					else SecureMemory.secureDeleteFile(f, 0L, false);
				}
			}
			if (!dir.isDirectory() && !dir.mkdirs()) {
				throw new IOException("no staging directory");
			}
			File f = new File(dir, randomName(type));
			try (FileOutputStream out = new FileOutputStream(f)) {
				out.write(content);
			} catch (IOException e) {
				SecureMemory.secureDeleteFile(f, 0L, false);
				throw e;
			}
			staged = f;
			return f;
		}
	}

	static boolean launch(Activity activity, File file, String type) {
		Uri uri;
		try {
			uri = FileProvider.getUriForFile(activity,
					activity.getPackageName() + ".fileprovider", file);
		} catch (IllegalArgumentException e) {
			return false;
		}
		Intent view = new Intent(Intent.ACTION_VIEW);
		view.setDataAndType(uri, type);
		view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		synchronized (LOCK) {
			if (!file.equals(staged)) return false;
			granted = uri;
			away = false;
		}
		watch(activity.getApplication());
		try {
			activity.startActivity(view);
			return true;
		} catch (ActivityNotFoundException | SecurityException e) {
			return false;
		}
	}

	public static void end(Context ctx) {
		File file;
		Uri uri;
		synchronized (LOCK) {
			file = staged;
			uri = granted;
			staged = null;
			granted = null;
			away = false;
		}
		if (uri != null) {
			try {
				revoker.revoke(ctx.getApplicationContext(), uri);
			} catch (RuntimeException ignored) {
			}
		}
		if (file != null) {
			File f = file;
			wiper.execute(() -> SecureMemory.secureDeleteFile(f, 0L, false));
		}
	}

	@Nullable
	static File current() {
		synchronized (LOCK) {
			return staged;
		}
	}

	private static String randomName(String type) {
		byte[] b = new byte[16];
		RANDOM.nextBytes(b);
		StringBuilder sb = new StringBuilder(40);
		for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
		String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
		if (ext == null || !ext.matches("[A-Za-z0-9]{1,8}")) ext = "bin";
		return sb.append('.').append(ext.toLowerCase(Locale.ROOT)).toString();
	}

	private static void watch(Application app) {
		synchronized (LOCK) {
			if (watched != null && watched.get() == app) return;
			watched = new WeakReference<>(app);
		}
		app.registerActivityLifecycleCallbacks(new Returns(app));
	}

	private static final class Returns
			implements Application.ActivityLifecycleCallbacks {

		private final Context app;

		private Returns(Context app) {
			this.app = app;
		}

		@Override
		public void onActivityPaused(Activity activity) {
			synchronized (LOCK) {
				if (granted != null) away = true;
			}
		}

		@Override
		public void onActivityResumed(Activity activity) {
			boolean back;
			synchronized (LOCK) {
				back = granted != null && away;
			}
			if (back) end(app);
		}

		@Override
		public void onActivityCreated(Activity activity,
				@Nullable Bundle savedInstanceState) {
		}

		@Override
		public void onActivityStarted(Activity activity) {
		}

		@Override
		public void onActivityStopped(Activity activity) {
		}

		@Override
		public void onActivitySaveInstanceState(Activity activity,
				Bundle outState) {
		}

		@Override
		public void onActivityDestroyed(Activity activity) {
		}
	}
}
