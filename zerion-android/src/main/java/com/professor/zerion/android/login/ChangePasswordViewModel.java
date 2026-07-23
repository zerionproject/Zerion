package com.professor.zerion.android.login;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;
import org.zerionproject.core.api.crypto.PasswordStrengthEstimator;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import com.professor.zerion.android.viewmodel.LiveEvent;
import com.professor.zerion.android.viewmodel.MutableLiveEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.lifecycle.ViewModel;

import static org.zerionproject.core.api.crypto.DecryptionResult.SUCCESS;

@NotNullByDefault
public class ChangePasswordViewModel extends ViewModel {

	private final AccountManager accountManager;
	private final Executor ioExecutor;
	private final PasswordStrengthEstimator strengthEstimator;

	@Inject
	ChangePasswordViewModel(AccountManager accountManager,
			@IoExecutor Executor ioExecutor,
			PasswordStrengthEstimator strengthEstimator) {
		this.accountManager = accountManager;
		this.ioExecutor = ioExecutor;
		this.strengthEstimator = strengthEstimator;
	}

	float estimatePasswordStrength(char[] password) {
		return strengthEstimator.estimateStrength(password);
	}

	LiveEvent<DecryptionResult> changePassword(char[] oldPassword,
			char[] newPassword) {
		MutableLiveEvent<DecryptionResult> result = new MutableLiveEvent<>();
		ioExecutor.execute(() -> {
			try {
				accountManager.changePassword(oldPassword, newPassword);
				result.postEvent(SUCCESS);
			} catch (DecryptionException e) {
				result.postEvent(e.getDecryptionResult());
			} finally {
				java.util.Arrays.fill(oldPassword, '\0');
				java.util.Arrays.fill(newPassword, '\0');
			}
		});
		return result;
	}
}
