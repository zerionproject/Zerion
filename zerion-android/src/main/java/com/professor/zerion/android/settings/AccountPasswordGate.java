package com.professor.zerion.android.settings;

import android.content.Context;
import android.os.Handler;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * Settings that protect the account (the duress password, erase after
 * failed sign-ins, the decoy screen, deleting a profile) are changed only
 * after the account password is entered again, through the same throttle
 * as the sign-in screen, so a person holding the unlocked phone cannot
 * quietly disarm them.
 */
@NotNullByDefault
final class AccountPasswordGate {

	enum Outcome { GRANTED, WRONG, LOCKED }

	static final class Result {
		final Outcome outcome;
		final long lockedMs;

		private Result(Outcome outcome, long lockedMs) {
			this.outcome = outcome;
			this.lockedMs = lockedMs;
		}
	}

	private AccountPasswordGate() {
	}

	/** A device without an account has nothing to protect; unknown counts as present. */
	static boolean accountExists(AccountManager accountManager) {
		try {
			return accountManager.accountExists();
		} catch (RuntimeException e) {
			return true;
		}
	}

	/** Blocking check; the password is wiped whatever the outcome. */
	static Result verify(AccountManager accountManager, char[] password) {
		try {
			long locked = accountManager.signInLockoutRemainingMs();
			if (locked > 0) return new Result(Outcome.LOCKED, locked);
			accountManager.signIn(password);
			return new Result(Outcome.GRANTED, 0);
		} catch (DecryptionException | RuntimeException e) {
			return new Result(Outcome.WRONG, 0);
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	/**
	 * Asks for the account password and runs {@code onGranted} on the main
	 * thread when it is right; {@code onRefused} runs on cancel, a wrong
	 * password or a lockout, after the user has been told which.
	 */
	static void prompt(Context context, AccountManager accountManager,
			Executor executor, Handler main, int titleRes, int messageRes,
			Runnable onGranted, Runnable onRefused) {
		if (!accountExists(accountManager)) {
			onGranted.run();
			return;
		}
		com.google.android.material.textfield.TextInputEditText password =
				new com.google.android.material.textfield.TextInputEditText(
						context);
		password.setHint(R.string.hardened_block_password_hint);
		com.professor.zerion.android.vault.ui.IncognitoInputHelper
				.configurePasswordField(password);
		int pad = (int) (20 * context.getResources().getDisplayMetrics()
				.density);
		android.widget.FrameLayout box = new android.widget.FrameLayout(context);
		box.setPadding(pad, 0, pad, 0);
		box.addView(password);
		new SecureAlertDialogBuilder(context)
				.setTitle(titleRes)
				.setMessage(messageRes)
				.setView(box)
				.setCancelable(false)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					android.text.Editable e = password.getText();
					char[] pw = new char[e == null ? 0 : e.length()];
					if (e != null) {
						e.getChars(0, e.length(), pw, 0);
						e.clear();
					}
					executor.execute(() -> {
						Result r = verify(accountManager, pw);
						main.post(() -> {
							if (r.outcome == Outcome.GRANTED) {
								onGranted.run();
								return;
							}
							if (r.outcome == Outcome.LOCKED) {
								Toast.makeText(context, context.getString(
										R.string.hardened_block_password_locked,
										(int) Math.ceil(r.lockedMs / 60000.0)),
										Toast.LENGTH_LONG).show();
							} else {
								Toast.makeText(context,
										R.string.settings_password_wrong,
										Toast.LENGTH_LONG).show();
							}
							onRefused.run();
						});
					});
				})
				.setNegativeButton(android.R.string.cancel,
						(d, w) -> onRefused.run())
				.show();
	}
}
