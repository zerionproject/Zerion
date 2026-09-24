package com.professor.zerion.android.security;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.activity.ActivityComponent;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class HardenedBlockActivity
		extends com.professor.zerion.android.activity.BaseActivity {

	public static final String EXTRA_RESULT_CODE =
			"com.professor.zerion.android.security.RESULT_CODE";

	@Inject
	@AppModule.UiPrefs
	SharedPreferences uiPrefs;
	@Inject
	org.zerionproject.core.api.account.AccountManager accountManager;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
				WindowManager.LayoutParams.FLAG_SECURE);
		setContentView(R.layout.activity_hardened_block);

		int result = getIntent().getIntExtra(EXTRA_RESULT_CODE,
				SecureBootGuard.RESULT_VERIFIED_BOOT_NOT_GREEN);
		TextView title = findViewById(R.id.hardenedBlockTitle);
		TextView detail = findViewById(R.id.hardenedBlockDetail);
		MaterialButton disableButton =
				findViewById(R.id.hardenedBlockDisableButton);
		MaterialButton quitButton =
				findViewById(R.id.hardenedBlockQuitButton);

		title.setText(R.string.hardened_block_title);
		detail.setText(SecureBootGuard.describe(result, this));
		detail.setMovementMethod(LinkMovementMethod.getInstance());

		disableButton.setOnClickListener(v ->
				promptDisableHardenedMode(result));
		quitButton.setOnClickListener(v -> finishAndRemoveTask());
	}

	/**
	 * The mode protects the account, so only the account password may turn
	 * it off; a device with no account yet has nothing to protect.
	 */
	private void promptDisableHardenedMode(int result) {
		boolean needsPassword;
		try {
			needsPassword = accountManager.accountExists();
		} catch (RuntimeException e) {
			needsPassword = true;
		}
		com.google.android.material.textfield.TextInputEditText password =
				new com.google.android.material.textfield.TextInputEditText(this);
		password.setHint(R.string.hardened_block_password_hint);
		com.professor.zerion.android.vault.ui.IncognitoInputHelper
				.configurePasswordField(password);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		android.widget.FrameLayout box = new android.widget.FrameLayout(this);
		box.setPadding(pad, 0, pad, 0);
		box.addView(password);
		androidx.appcompat.app.AlertDialog.Builder b =
				new SecureAlertDialogBuilder(this)
				.setTitle(R.string.hardened_block_disable_title)
				.setMessage(R.string.hardened_block_disable_message);
		final boolean requirePassword = needsPassword;
		if (requirePassword) b.setView(box);
		b.setPositiveButton(R.string.hardened_block_disable_confirm,
						(d, w) -> {
							if (!requirePassword) {
								disableHardenedMode();
								return;
							}
							android.text.Editable e = password.getText();
							char[] pw = new char[e == null ? 0 : e.length()];
							if (e != null) e.getChars(0, e.length(), pw, 0);
							verifyThenDisable(pw);
						})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void verifyThenDisable(char[] password) {
		new Thread(() -> {
			boolean ok = false;
			long lockedMs = 0;
			try {
				lockedMs = accountManager.signInLockoutRemainingMs();
				if (lockedMs <= 0) {
					accountManager.signIn(password);
					ok = true;
				}
			} catch (org.zerionproject.core.api.crypto.DecryptionException e) {
				ok = false;
			} catch (RuntimeException e) {
				ok = false;
			} finally {
				java.util.Arrays.fill(password, '\0');
			}
			final boolean granted = ok;
			final long waitMs = lockedMs;
			runOnUiThread(() -> {
				if (isFinishing()) return;
				if (granted) {
					disableHardenedMode();
				} else if (waitMs > 0) {
					android.widget.Toast.makeText(this, getString(
							R.string.hardened_block_password_locked,
							(int) Math.ceil(waitMs / 60000.0)),
							android.widget.Toast.LENGTH_LONG).show();
				} else {
					android.widget.Toast.makeText(this,
							R.string.hardened_block_password_wrong,
							android.widget.Toast.LENGTH_LONG).show();
				}
			});
		}, "HardenedDisable").start();
	}

	private void disableHardenedMode() {
		uiPrefs.edit()
				.putBoolean(HardenedModeEvaluator.PREF_HARDENED_BOOT, false)
				.putBoolean(HardenedModeEvaluator.PREF_HARDENED_TAMPER, false)
				.apply();
		finishAndRemoveTask();
	}

	@Override
	public void onBackPressed() {
		finishAndRemoveTask();
	}
}
