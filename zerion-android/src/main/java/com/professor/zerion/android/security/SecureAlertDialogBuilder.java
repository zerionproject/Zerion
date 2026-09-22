package com.professor.zerion.android.security;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * The one alert-dialog builder used across the app. It applies
 * {@link SecureDialogs}' policy when the dialog is created: the dialog
 * inherits its host activity's screenshot protection, and it is forced
 * protected when its content holds a password-type input or when the author
 * calls {@link #protectSecrets()}. A layout set by resource id is inflated
 * here so its inputs are inspected before the window is shown.
 */
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

	/** Mark the dialog as showing a secret: it is always protected. */
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
		boolean secretContent = customView != null
				&& SecureDialogs.containsSecretInput(customView);
		if (secret || secretContent) SecureDialogs.protectSecret(dialog);
		else SecureDialogs.applyHostPolicy(dialog);
		return dialog;
	}
}
