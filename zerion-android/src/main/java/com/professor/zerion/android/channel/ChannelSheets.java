package com.professor.zerion.android.channel;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.professor.zerion.android.security.SecureDialogs;
import com.professor.zerion.android.vault.ui.IncognitoInputHelper;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
final class ChannelSheets {

	private ChannelSheets() {
	}

	static BottomSheetDialog create(Context ctx, View content,
			boolean secret) {
		BottomSheetDialog dialog = new BottomSheetDialog(ctx);
		dialog.setContentView(content);
		dialog.setOnShowListener(d -> {
			View container = dialog.findViewById(
					com.google.android.material.R.id.design_bottom_sheet);
			if (container == null) return;
			container.setBackgroundColor(Color.TRANSPARENT);
			BottomSheetBehavior<View> behavior =
					BottomSheetBehavior.from(container);
			behavior.setSkipCollapsed(true);
			behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
		});
		IncognitoInputHelper.install(content);
		if (secret) {
			SecureDialogs.protectSecret(dialog);
			Window w = dialog.getWindow();
			if (w != null) {
				w.setSoftInputMode(
						WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
			}
		} else {
			SecureDialogs.applyHostPolicy(dialog);
		}
		return dialog;
	}
}
