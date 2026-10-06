package com.professor.zerion.android.vault.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public final class VaultScreenProtection {

	static final String PREF_HIDE_CONTENT = "hide_content_enabled";

	private VaultScreenProtection() {
	}

	public static boolean isVaultScreen(@Nullable Fragment f) {
		return f != null && f.getClass().getName().contains(".vault.");
	}

	static boolean isWalletScreen(@Nullable Fragment f) {
		return f instanceof VaultWalletFragment
				|| f instanceof XmrWalletFragment
				|| f instanceof XmrWalletDetailFragment
				|| f instanceof XmrRecoveryPhraseFragment;
	}

	public static boolean required(FragmentManager fm,
			@Nullable SharedPreferences profilePrefs) {
		boolean hideContent = hidesContent(profilePrefs);
		for (Fragment f : fm.getFragments()) {
			if (needsSecureWindow(f, hideContent)) return true;
		}
		return false;
	}

	private static boolean needsSecureWindow(@Nullable Fragment f,
			boolean hideContent) {
		return isWalletScreen(f) || (hideContent && isVaultScreen(f));
	}

	private static boolean hidesContent(
			@Nullable SharedPreferences profilePrefs) {
		return profilePrefs == null
				|| profilePrefs.getBoolean(PREF_HIDE_CONTENT, true);
	}

	public static void install(FragmentActivity host,
			@Nullable SharedPreferences profilePrefs, Runnable reapply) {
		host.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
				new FragmentManager.FragmentLifecycleCallbacks() {
					@Override
					public void onFragmentViewCreated(FragmentManager fm,
							Fragment f, View v, @Nullable Bundle state) {
						if (needsSecureWindow(f, hidesContent(profilePrefs))) {
							host.getWindow().addFlags(
									WindowManager.LayoutParams.FLAG_SECURE);
						}
					}

					@Override
					public void onFragmentViewDestroyed(FragmentManager fm,
							Fragment f) {
						reapply.run();
					}
				}, false);
	}
}
