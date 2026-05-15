package org.briarproject.bramble.util;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Looper;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.util.Collection;

import javax.annotation.Nullable;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.content.Context.MODE_PRIVATE;
import static android.content.Context.RECEIVER_NOT_EXPORTED;
import static android.os.Build.VERSION.SDK_INT;
import static java.util.Arrays.asList;

@NotNullByDefault
public class AndroidUtils {

	private static final String STORED_REPORTS = "dev-reports";
	private static final String STORED_LOGCAT = "dev-logcat";

	public static Collection<String> getSupportedArchitectures() {
		return asList(Build.SUPPORTED_ABIS);
	}

	public static File getReportDir(Context ctx) {
		return ctx.getDir(STORED_REPORTS, MODE_PRIVATE);
	}

	public static File getLogcatFile(Context ctx) {
		return new File(ctx.getFilesDir(), STORED_LOGCAT);
	}

	public static String[] getSupportedImageContentTypes() {
		return new String[] {"image/jpeg", "image/png", "image/gif", "image/webp"};
	}

	public static boolean isUiThread() {
		return Looper.myLooper() == Looper.getMainLooper();
	}

	public static int getImmutableFlags(int flags) {
		if (SDK_INT >= 23) {
			return FLAG_IMMUTABLE | flags;
		}
		return flags;
	}

	@Nullable
	@SuppressLint("UnspecifiedRegisterReceiverFlag")
	public static Intent registerReceiver(Context ctx,
			@Nullable BroadcastReceiver receiver, IntentFilter filter) {
		if (SDK_INT >= 33) {
			return ctx.registerReceiver(receiver, filter,
					RECEIVER_NOT_EXPORTED);
		} else {
			return ctx.registerReceiver(receiver, filter);
		}
	}
}
