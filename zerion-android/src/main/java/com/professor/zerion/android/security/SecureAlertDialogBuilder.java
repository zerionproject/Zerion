package com.professor.zerion.android.security;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.android.vault.ui.IncognitoInputHelper;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public class SecureAlertDialogBuilder extends MaterialAlertDialogBuilder {

	@Nullable
	private View customView;
	private boolean secret;
	private boolean fitAboveKeyboard;

	public SecureAlertDialogBuilder(Context context) {
		super(context);
	}

	public SecureAlertDialogBuilder(Context context, int overrideThemeResId) {
		super(context, overrideThemeResId);
	}

	public SecureAlertDialogBuilder protectSecrets() {
		secret = true;
		return this;
	}

	public SecureAlertDialogBuilder fitAboveKeyboard() {
		fitAboveKeyboard = true;
		return this;
	}

	@Override
	public MaterialAlertDialogBuilder setView(@Nullable View view) {
		customView = view;
		return super.setView(view);
	}

	@Override
	public MaterialAlertDialogBuilder setView(int layoutResId) {
		View v = LayoutInflater.from(getContext()).inflate(layoutResId, null,
				false);
		return setView(v);
	}

	@Override
	public AlertDialog create() {
		AlertDialog dialog = super.create();
		if (customView != null) IncognitoInputHelper.install(customView);
		boolean secretContent = customView != null
				&& SecureDialogs.containsSecretInput(customView);
		if (secret || secretContent) SecureDialogs.protectSecret(dialog);
		else SecureDialogs.applyHostPolicy(dialog);
		Window w = dialog.getWindow();
		if (fitAboveKeyboard && w != null) {
			int mode = w.getAttributes().softInputMode
					& ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
			w.setSoftInputMode(mode
					| WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		}
		return dialog;
	}
}
