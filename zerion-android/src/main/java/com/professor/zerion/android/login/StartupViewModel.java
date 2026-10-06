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
import com.professor.zerion.android.vault.VaultManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
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

	enum StartupDecision {
		SIGN_IN,
		ERASED,
		NO_ACCOUNT,
		DATA_WITHOUT_ACCOUNT
	}

	void decideStartAsync(
			java.util.function.Consumer<StartupDecision> callback) {
		ioExecutor.execute(() -> {
			StartupDecision d;
			if (accountManager.isEraseRequested()) {
				completeErase();
				d = StartupDecision.ERASED;
			} else if (accountManager.accountExists()) {
				d = StartupDecision.SIGN_IN;
			} else if (LeftoverAccountData.present(getApplication())) {
				d = StartupDecision.DATA_WITHOUT_ACCOUNT;
			} else {
				completeErase();
				d = StartupDecision.NO_ACCOUNT;
			}
			StartupDecision decision = d;
			mainHandler.post(() -> callback.accept(decision));
		});
	}

	private void completeErase() {
		try {
			AccountWipeCleanup.wipe(getApplication(), vaultManager);
		} catch (RuntimeException ignored) {
		}
		try {
			accountManager.deleteAccount();
		} catch (RuntimeException ignored) {
		}
		synchronized (bruteForceProtection) {
			bruteForceProtection.clear();
		}
	}

	void clearSignInNotification() {
		notificationManager.blockSignInNotification();
		notificationManager.clearSignInNotification();
	}

	@Nullable
	private volatile AccountPasswordCheck.DuressCheck duressCheck = null;

	void setDuressCheck(AccountPasswordCheck.DuressCheck check) {
		duressCheck = check;
	}

	private AccountPasswordCheck passwordCheck() {
		AccountPasswordCheck.DuressCheck d = duressCheck;
		return new AccountPasswordCheck(accountManager, d != null ? d
				: AccountPasswordCheck.duressPasswordOf(getApplication()));
	}

	void validatePassword(char[] password) {
		ioExecutor.execute(() -> {
			AccountPasswordCheck.Result r = passwordCheck().check(password,
					accountManager::signIn);
			switch (r.outcome) {
				case GRANTED:
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
					break;
				case ERASE:
					erase(true);
					break;
				case LOCKED:
					lockoutStatus.postEvent(LockStatus.locked(r.lockedMs));
					break;
				case DAMAGED:
					passwordValidated.postEvent(
							DecryptionResult.KEY_FILES_DAMAGED);
					break;
				case WRONG:
					handleCryptographicFailure(r.result == null
							? DecryptionResult.INVALID_PASSWORD : r.result);
					break;
				default:
					operationalFailure.postEvent(true);
					break;
			}
		});
	}

	private void erase(boolean notifyScreen) {
		try {
			accountManager.shredDatabaseKey();
		} catch (RuntimeException ignored) {
		}
		try {
			AccountWipeCleanup.wipe(getApplication(), vaultManager);
			accountManager.deleteAccount();
		} catch (Exception ignored) {
		}
		synchronized (bruteForceProtection) {
			bruteForceProtection.clear();
		}
		if (notifyScreen) triggerWipe.postEvent(true);
	}

	private void handleCryptographicFailure(DecryptionResult result) {
		FailureResult failureResult;
		synchronized (bruteForceProtection) {
			failureResult = bruteForceProtection.recordFailedAttempt();
		}

		passwordValidated.postEvent(result);

		if (failureResult.type == FailureResult.Type.WIPE_DATA) {
			erase(true);
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
				accountManager.shredDatabaseKey();
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
