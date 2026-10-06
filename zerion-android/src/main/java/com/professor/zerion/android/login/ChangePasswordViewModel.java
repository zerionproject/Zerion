package com.professor.zerion.android.login;

import android.app.Application;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;
import org.zerionproject.core.api.crypto.PasswordStrengthEstimator;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import com.professor.zerion.android.viewmodel.LiveEvent;
import com.professor.zerion.android.viewmodel.MutableLiveEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.ViewModel;

import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_REPLACEMENT_FAILED;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_REPLACEMENT_UNCERTAIN;
import static org.zerionproject.core.api.crypto.DecryptionResult.SUCCESS;

@NotNullByDefault
public class ChangePasswordViewModel extends ViewModel {

	private final Application app;
	private final AccountManager accountManager;
	private final Executor ioExecutor;
	private final PasswordStrengthEstimator strengthEstimator;

	@Nullable
	private volatile AccountPasswordCheck.DuressCheck duressCheck = null;

	@Inject
	ChangePasswordViewModel(Application app, AccountManager accountManager,
			@IoExecutor Executor ioExecutor,
			PasswordStrengthEstimator strengthEstimator) {
		this.app = app;
		this.accountManager = accountManager;
		this.ioExecutor = ioExecutor;
		this.strengthEstimator = strengthEstimator;
	}

	void setDuressCheck(AccountPasswordCheck.DuressCheck check) {
		duressCheck = check;
	}

	float estimatePasswordStrength(char[] typed) {
		return AccountPasswordPolicy.strength(strengthEstimator, typed);
	}

	boolean acceptable(char[] typed) {
		return AccountPasswordPolicy.acceptable(strengthEstimator, typed);
	}

	long lockoutRemainingMs() {
		try {
			return accountManager.signInLockoutRemainingMs();
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private final MutableLiveEvent<DecryptionResult> latestResult =
			new MutableLiveEvent<>();

	private volatile boolean newPasswordRefused = false;

	private final MutableLiveEvent<Boolean> eraseRequested =
			new MutableLiveEvent<>();

	LiveEvent<Boolean> getEraseRequested() {
		return eraseRequested;
	}

	boolean takeNewPasswordRefused() {
		boolean refused = newPasswordRefused;
		newPasswordRefused = false;
		return refused;
	}

	LiveEvent<DecryptionResult> getLatestResult() {
		return latestResult;
	}

	LiveEvent<DecryptionResult> changePassword(char[] oldPassword,
			char[] newPassword) {
		ioExecutor.execute(() -> {
			DecryptionResult outcome;
			newPasswordRefused = false;
			AccountPasswordCheck.DuressCheck d = duressCheck;
			AccountPasswordCheck check = new AccountPasswordCheck(
					accountManager, d != null ? d
					: AccountPasswordCheck.duressPasswordOf(app));
			try {
				AccountPasswordCheck.Result before =
						check.beforeAttempt(oldPassword);
				if (before != null) {
					if (before.outcome
							== AccountPasswordCheck.Outcome.ERASE) {
						eraseRequested.postEvent(true);
						return;
					}
					latestResult.postEvent(DecryptionResult.INVALID_PASSWORD);
					return;
				}
				char[] oldCopy = oldPassword.clone();
				try {
					accountManager.changePassword(oldCopy, newPassword);
				} finally {
					java.util.Arrays.fill(oldCopy, '\0');
				}
				outcome = SUCCESS;
			} catch (DecryptionException e) {
				AccountPasswordCheck.Result r =
						check.afterRefusal(oldPassword, e.getDecryptionResult());
				if (r.outcome == AccountPasswordCheck.Outcome.ERASE) {
					eraseRequested.postEvent(true);
					return;
				}
				outcome = e.getDecryptionResult();
			} catch (org.zerionproject.core.account
					.PasswordUnavailableException e) {
				newPasswordRefused = true;
				outcome = KEY_REPLACEMENT_FAILED;
			} catch (IllegalArgumentException e) {
				outcome = KEY_REPLACEMENT_FAILED;
			} catch (RuntimeException | Error e) {
				outcome = KEY_REPLACEMENT_UNCERTAIN;
			} finally {
				java.util.Arrays.fill(oldPassword, '\0');
				java.util.Arrays.fill(newPassword, '\0');
			}
			latestResult.postEvent(outcome);
		});
		return latestResult;
	}
}
