package com.professor.zerion.android.util;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.professor.zerion.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Map;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.FragmentActivity;

import static android.content.Intent.CATEGORY_DEFAULT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static androidx.core.content.ContextCompat.checkSelfPermission;
import static java.lang.Boolean.TRUE;
import static com.professor.zerion.BuildConfig.APPLICATION_ID;
import static com.professor.zerion.android.util.UiUtils.tryToStartActivity;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class PermissionUtils {

	public static boolean gotPermission(Context ctx,
			@Nullable Map<String, Boolean> grantedMap, String permission) {
		if (grantedMap == null || !grantedMap.containsKey(permission)) {
			return isPermissionGranted(ctx, permission);
		} else {
			return TRUE.equals(grantedMap.get(permission));
		}
	}

	private static boolean isPermissionGranted(Context ctx, String permission) {
		return checkSelfPermission(ctx, permission) ==
				PERMISSION_GRANTED;
	}

	private static DialogInterface.OnClickListener getGoToSettingsListener(
			Context context) {
		return (dialog, which) -> {
			Intent i = new Intent();
			i.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
			i.addCategory(CATEGORY_DEFAULT);
			i.setData(Uri.parse("package:" + APPLICATION_ID));
			i.addFlags(FLAG_ACTIVITY_NEW_TASK);
			tryToStartActivity(context, i);
		};
	}

	public static void showDenialDialog(FragmentActivity ctx,
			@StringRes int title, @StringRes int body) {
		showDenialDialog(ctx, title, body, ctx::supportFinishAfterTransition);
	}

	public static void showDenialDialog(FragmentActivity ctx,
			@StringRes int title, @StringRes int body, Runnable onDenied) {
		MaterialAlertDialogBuilder builder =
				new MaterialAlertDialogBuilder(ctx, R.style.ZerionDialogTheme);
		builder.setTitle(title);
		builder.setMessage(body);
		builder.setPositiveButton(R.string.ok, getGoToSettingsListener(ctx));
		builder.setNegativeButton(R.string.cancel, (dialog, which) ->
				onDenied.run());
		builder.show();
	}

	public static void showRationale(FragmentActivity ctx, @StringRes int title,
			@StringRes int body, @Nullable Runnable onOk) {
		MaterialAlertDialogBuilder builder =
				new MaterialAlertDialogBuilder(ctx, R.style.ZerionDialogTheme);
		builder.setTitle(title);
		builder.setMessage(body);
		builder.setNeutralButton(R.string.continue_button, (dialog, which) -> {
			if (onOk != null) onOk.run();
			dialog.dismiss();
		});
		builder.show();
	}

}
