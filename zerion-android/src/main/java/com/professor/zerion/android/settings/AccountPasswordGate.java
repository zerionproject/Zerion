package com.professor.zerion.android.settings;

import android.content.Context;
import android.os.Handler;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.login.AccountPasswordCheck;

import org.zerionproject.core.api.account.AccountManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.concurrent.Executor;

@NotNullByDefault
final class AccountPasswordGate {

	enum Outcome { GRANTED, WRONG, LOCKED, ERASE }

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

	static boolean accountExists(AccountManager accountManager) {
		try {
			return accountManager.accountExists();
		} catch (RuntimeException e) {
			return true;
		}
	}

	static Result verify(AccountManager accountManager, char[] password) {
		return verify(accountManager, typed -> false, password);
	}

	static Result verify(AccountManager accountManager,
			AccountPasswordCheck.DuressCheck duress, char[] password) {
		try {
			AccountPasswordCheck.Result r =
					new AccountPasswordCheck(accountManager, duress)
							.check(password, accountManager::verifyPassword);
			switch (r.outcome) {
				case GRANTED:
					return new Result(Outcome.GRANTED, 0);
				case LOCKED:
					return new Result(Outcome.LOCKED, r.lockedMs);
				case ERASE:
					return new Result(Outcome.ERASE, 0);
				default:
					return new Result(Outcome.WRONG, 0);
			}
		} catch (RuntimeException e) {
			return new Result(Outcome.WRONG, 0);
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	static void eraseFromSession(Context context,
			AccountManager accountManager, Executor executor) {
		executor.execute(() -> {
			try {
				accountManager.shredDatabaseKey();
			} catch (RuntimeException ignored) {
			}
			new Handler(android.os.Looper.getMainLooper()).post(() -> {
				if (context instanceof ZerionActivity) {
					((ZerionActivity) context).eraseAccountsAndExit();
				} else {
					android.os.Process.killProcess(
							android.os.Process.myPid());
				}
			});
		});
	}

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
		androidx.appcompat.app.AlertDialog dialog = new SecureAlertDialogBuilder(context)
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
					AccountPasswordCheck.DuressCheck duress =
							AccountPasswordCheck.duressPasswordOf(context);
					executor.execute(() -> {
						Result r = verify(accountManager, duress, pw);
						main.post(() -> {
							if (r.outcome == Outcome.GRANTED) {
								onGranted.run();
								return;
							}
							if (r.outcome == Outcome.ERASE) {
								eraseFromSession(context, accountManager,
										executor);
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
		submitOnDone(password, dialog);
	}

	static void submitOnDone(android.widget.TextView field,
			androidx.appcompat.app.AlertDialog dialog) {
		field.setOnEditorActionListener((v, actionId, event) -> {
			boolean done = actionId == android.view.inputmethod.EditorInfo
					.IME_ACTION_DONE;
			boolean enter = event != null
					&& event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
					&& event.getAction() == android.view.KeyEvent.ACTION_DOWN;
			if (!done && !enter) return false;
			android.widget.Button ok = dialog.getButton(
					android.content.DialogInterface.BUTTON_POSITIVE);
			if (ok != null && ok.isEnabled()) ok.performClick();
			return true;
		});
	}
}
