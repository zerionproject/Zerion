package com.professor.zerion.android.login;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.crypto.DecryptionException;
import org.briarproject.bramble.api.crypto.DecryptionResult;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState;
import org.briarproject.bramble.api.lifecycle.event.LifecycleEvent;
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

import static org.briarproject.bramble.api.crypto.DecryptionResult.SUCCESS;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.COMPACTING_DATABASE;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.MIGRATING_DATABASE;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.STARTING_SERVICES;
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

	void validatePassword(char[] password) {
		ioExecutor.execute(() -> {
			synchronized (bruteForceProtection) {
				LockStatus lockStatus = bruteForceProtection.checkLockStatus();
				if (lockStatus.isLocked) {
					lockoutStatus.postEvent(lockStatus);
					Arrays.fill(password, '\0');
					return;
				}
			}

			boolean cryptographicFailure = false;
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
					operationalFailure.postEvent(true);
				} else {
					cryptographicFailure = true;
				}
			} catch (Exception e) {
				operationalFailure.postEvent(true);
			}

			boolean duressMatch = false;
			if (cryptographicFailure) {
				try {
					WipePasswordManager wpm =
							WipePasswordManager.getInstance(getApplication());
					if (wpm != null && wpm.isWipePasswordEnabled()
							&& wpm.verifyWipePassword(password)) {
						duressMatch = true;
					}
				} catch (Exception ignored) {
				}
			}
			Arrays.fill(password, '\0');

			if (duressMatch) {
				try {
					AccountWipeCleanup.wipe(getApplication(), vaultManager);
					accountManager.deleteAccount();
				} catch (Exception ignored) {
				}
				synchronized (bruteForceProtection) {
					bruteForceProtection.clear();
				}
				triggerWipe.postEvent(true);
				return;
			}

			if (cryptographicFailure && decryptionResult != null) {
				handleCryptographicFailure(decryptionResult);
			}
		});
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
