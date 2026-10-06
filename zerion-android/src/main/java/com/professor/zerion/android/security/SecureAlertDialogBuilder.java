package com.professor.zerion.android.security;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

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
		return dialog;
	}
}
