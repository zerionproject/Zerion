package com.professor.zerion.android.login;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState;
import org.zerionproject.core.api.lifecycle.event.LifecycleEvent;
import com.professor.zerion.android.viewmodel.LiveEvent;
import com.professor.zerion.android.viewmodel.MutableLiveEvent;
import com.professor.zerion.android.account.AccountWipeCleanup;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.login.BruteForceProtection.FailureResult;
import com.professor.zerion.android.login.BruteForceProtection.LockStatus;
import com.professor.zerion.android.panic.WipePasswordManager;
import com.professor.zerion.android.vault.VaultManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static org.zerionproject.core.api.crypto.DecryptionResult.SUCCESS;
import static org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState.COMPACTING_DATABASE;
import static org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState.MIGRATING_DATABASE;
import static org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState.STARTING_SERVICES;
import static com.professor.zerion.android.login.StartupViewModel.State.COMPACTING;
import static com.professor.zerion.android.login.StartupViewModel.State.MIGRATING;
import static com.professor.zerion.android.login.StartupViewModel.State.SIGNED_IN;
import static com.professor.zerion.android.login.StartupViewModel.State.SIGNED_OUT;
import static com.professor.zerion.android.login.StartupViewModel.State.STARTED;
import static com.professor.zerion.android.login.StartupViewModel.State.STARTING;

@NotNullByDefault
public class StartupViewModel extends AndroidViewModel
		implements EventListener {

	enum State {SIGNED_OUT, SIGNED_IN, STARTING, MIGRATING, COMPACTING, STARTED}

	private final AccountManager accountManager;
	private final AndroidNotificationManager notificationManager;
	private final BruteForceProtection bruteForceProtection;
	private final EventBus eventBus;
	private final VaultManager vaultManager;
	@IoExecutor
	private final Executor ioExecutor;
	private final Handler mainHandler;
	private final Object stateLock = new Object();
	private final AtomicBoolean listenerRegistered = new AtomicBoolean(false);
	private final AtomicBoolean isCleared = new AtomicBoolean(false);

	private final MutableLiveEvent<DecryptionResult> passwordValidated =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> accountDeleted =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<LockStatus> lockoutStatus =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<FailureResult> bruteForceFailure =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> triggerWipe =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> operationalFailure =
			new MutableLiveEvent<>();
	private final MutableLiveData<State> _state = new MutableLiveData<>();
	private final LiveData<State> state = _state;

	@Inject
	StartupViewModel(Application app,
			AccountManager accountManager,
			LifecycleManager lifecycleManager,
			AndroidNotificationManager notificationManager,
			BruteForceProtection bruteForceProtection,
			EventBus eventBus,
			VaultManager vaultManager,
			@IoExecutor Executor ioExecutor) {
		super(app);
		this.accountManager = accountManager;
		this.notificationManager = notificationManager;
		this.bruteForceProtection = bruteForceProtection;
		this.eventBus = eventBus;
		this.vaultManager = vaultManager;
		this.ioExecutor = ioExecutor;
		this.mainHandler = new Handler(Looper.getMainLooper());

		if (listenerRegistered.compareAndSet(false, true)) {
			eventBus.addListener(this);
		}
		updateState(lifecycleManager.getLifecycleState());
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		isCleared.set(true);
		if (listenerRegistered.compareAndSet(true, false)) {
			eventBus.removeListener(this);
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (isCleared.get()) return;

		if (e instanceof LifecycleEvent) {
			LifecycleState s = ((LifecycleEvent) e).getLifecycleState();
			if (!isCleared.get()) {
				mainHandler.post(() -> {
					if (!isCleared.get()) {
						updateState(s);
					}
				});
			}
		}
	}

	@UiThread
	private void updateState(LifecycleState s) {
		synchronized (stateLock) {
			State currentState = _state.getValue();
			State newState;
			boolean hasKey;

			synchronized (accountManager) {
				hasKey = accountManager.hasDatabaseKey();
			}

			if (hasKey) {
				if (s.isAfter(STARTING_SERVICES)) {
					newState = STARTED;
				} else if (s == MIGRATING_DATABASE) {
					newState = MIGRATING;
				} else if (s == COMPACTING_DATABASE) {
					newState = COMPACTING;
				} else {
					newState = STARTING;
				}
			} else {
				newState = SIGNED_OUT;
			}

			if (currentState != newState) {
				_state.setValue(newState);
			}
		}
	}

	boolean accountExists() {
		return accountManager.accountExists();
	}

	void checkAccountExistsAsync(java.util.function.Consumer<Boolean> callback) {
		ioExecutor.execute(() -> {
			boolean exists = accountManager.accountExists();
			mainHandler.post(() -> callback.accept(exists));
		});
	}

	void clearSignInNotification() {
		notificationManager.blockSignInNotification();
		notificationManager.clearSignInNotification();
	}

	/** Whether a password is the duress password, when one is set. */
	interface DuressCheck {
		boolean matches(char[] password);
	}

	private volatile DuressCheck duressCheck = password -> {
		try {
			WipePasswordManager wpm =
					WipePasswordManager.getInstance(getApplication());
			return wpm != null && wpm.isWipePasswordEnabled()
					&& wpm.verifyWipePassword(password);
		} catch (Exception e) {
			return false;
		}
	};

	void setDuressCheck(DuressCheck check) {
		duressCheck = check;
	}

	/**
	 * The duress password is honoured on every path that does not sign in:
	 * a wrong password, an unavailable key strengthener and, above all, an
	 * active lockout, which is exactly when a coerced user needs it after a
	 * coercer's own guesses.
	 */
	void validatePassword(char[] password) {
		ioExecutor.execute(() -> {
			synchronized (bruteForceProtection) {
				LockStatus lockStatus = bruteForceProtection.checkLockStatus();
				if (lockStatus.isLocked) {
					boolean duress = duressCheck.matches(password);
					Arrays.fill(password, '\0');
					if (duress) {
						wipeForDuress();
					} else {
						lockoutStatus.postEvent(lockStatus);
					}
					return;
				}
			}

			boolean cryptographicFailure = false;
			boolean strengthenerFailure = false;
			DecryptionResult decryptionResult = null;

			try {
					accountManager.signIn(password);

				synchronized (bruteForceProtection) {
					bruteForceProtection.recordSuccessfulLogin();
				}

				passwordValidated.postEvent(SUCCESS);

				mainHandler.post(() -> {
					synchronized (stateLock) {
						boolean hasKey;
						synchronized (accountManager) {
							hasKey = accountManager.hasDatabaseKey();
						}
						if (hasKey) {
							_state.setValue(SIGNED_IN);
						}
					}
				});
			} catch (DecryptionException e) {
				decryptionResult = e.getDecryptionResult();
				if (decryptionResult == DecryptionResult.KEY_STRENGTHENER_ERROR) {
					strengthenerFailure = true;
				} else {
					cryptographicFailure = true;
				}
			} catch (Exception e) {
				operationalFailure.postEvent(true);
			}

			boolean duressMatch = (cryptographicFailure || strengthenerFailure)
					&& duressCheck.matches(password);
			Arrays.fill(password, '\0');

			if (duressMatch) {
				wipeForDuress();
				return;
			}
			if (strengthenerFailure) {
				operationalFailure.postEvent(true);
				return;
			}
			if (cryptographicFailure && decryptionResult != null) {
				handleCryptographicFailure(decryptionResult);
			}
		});
	}

	private void wipeForDuress() {
		try {
			AccountWipeCleanup.wipe(getApplication(), vaultManager);
			accountManager.deleteAccount();
		} catch (Exception ignored) {
		}
		synchronized (bruteForceProtection) {
			bruteForceProtection.clear();
		}
		triggerWipe.postEvent(true);
	}

	private void handleCryptographicFailure(DecryptionResult result) {
		FailureResult failureResult;
		synchronized (bruteForceProtection) {
			failureResult = bruteForceProtection.recordFailedAttempt();
		}

		passwordValidated.postEvent(result);

		if (failureResult.type == FailureResult.Type.WIPE_DATA) {
			triggerWipe.postEvent(true);
		} else {
			bruteForceFailure.postEvent(failureResult);
		}
	}

	LiveEvent<DecryptionResult> getPasswordValidated() {
		return passwordValidated;
	}

	LiveEvent<Boolean> getAccountDeleted() {
		return accountDeleted;
	}

	LiveEvent<LockStatus> getLockoutStatus() {
		return lockoutStatus;
	}

	LiveEvent<FailureResult> getBruteForceFailure() {
		return bruteForceFailure;
	}

	LiveEvent<Boolean> getTriggerWipe() {
		return triggerWipe;
	}

	LiveEvent<Boolean> getOperationalFailure() {
		return operationalFailure;
	}

	LiveData<State> getState() {
		return state;
	}

	void deleteAccount() {
		ioExecutor.execute(() -> {
			try {
				AccountWipeCleanup.wipe(getApplication(), vaultManager);
				accountManager.deleteAccount();
				synchronized (bruteForceProtection) {
					bruteForceProtection.clear();
				}
				accountDeleted.postEvent(true);
			} catch (Exception e) {
				accountDeleted.postEvent(false);
			}
		});
	}

}
