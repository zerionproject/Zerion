package com.professor.zerion.android.account;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.BaseActivity;
import com.professor.zerion.android.api.LockManager;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;


import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;

import static android.os.Build.VERSION.SDK_INT;
import static android.view.View.INVISIBLE;
import static com.professor.zerion.android.activity.RequestCodes.REQUEST_KEYGUARD_UNLOCK;
import static com.professor.zerion.android.util.UiUtils.hasKeyguardLock;
import static com.professor.zerion.android.util.UiUtils.hasUsableFingerprint;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class UnlockActivity extends BaseActivity {

	private static final String KEYGUARD_SHOWN = "keyguardShown";

	private static final int BRUTE_FORCE_DELAY_MS = 250;

	private static final String UNLOCK_SESSION_ID = "unlockSessionId";

	@Inject
	LockManager lockManager;

	private boolean keyguardShown = false;
	private BiometricPrompt biometricPrompt;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private long unlockSessionId = 0L;
	private boolean authenticationInProgress = false;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

		overridePendingTransition(0, 0);
		setContentView(R.layout.activity_unlock);

		if (!hasUsableFingerprint(this)) {
			getWindow().setBackgroundDrawable(null);
			findViewById(R.id.image).setVisibility(INVISIBLE);
		}
		keyguardShown = state != null && state.getBoolean(KEYGUARD_SHOWN);

		if (state != null) {
			unlockSessionId = state.getLong(UNLOCK_SESSION_ID, 0L);
		}

		if (SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
			finish();
			return;
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putBoolean(KEYGUARD_SHOWN, keyguardShown);

		outState.putLong(UNLOCK_SESSION_ID, unlockSessionId);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode,
			@Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQUEST_KEYGUARD_UNLOCK) {
			if (resultCode == RESULT_OK) unlock();
			else {
				finish();
				overridePendingTransition(0, 0);
			}
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		long currentTime = System.currentTimeMillis();
		if (unlockSessionId != 0L && (currentTime - unlockSessionId) > 1000) {
			authenticationInProgress = false;
		}
		unlockSessionId = currentTime;

		if (SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
			finish();
			return;
		}

		if (!keyguardShown && lockManager.isLocked() && !isFinishing() && !authenticationInProgress) {
			requestUnlock();
		} else if (!lockManager.isLocked()) {
			setResult(RESULT_OK);
			finish();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		cancelBiometricAuthentication();
		authenticationInProgress = false;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		cancelBiometricAuthentication();
		mainHandler.removeCallbacksAndMessages(null);
	}

	private void requestUnlock() {
		KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
		if (km != null && !km.isDeviceSecure()) {
			finish();
			return;
		}

		if (SDK_INT >= 28 && hasUsableFingerprint(this)) {
			requestFingerprintUnlock();
		} else {
			requestKeyguardUnlock();
		}
	}

	@Override
	@SuppressLint("MissingSuperCall")
	public void onBackPressed() {
		cancelBiometricAuthentication();
		moveTaskToBack(true);
	}

	private void cancelBiometricAuthentication() {
		if (biometricPrompt != null) {
			biometricPrompt.cancelAuthentication();
			biometricPrompt = null;
		}
	}

	@RequiresApi(api = 28)
	private void requestFingerprintUnlock() {
		BiometricPrompt.PromptInfo.Builder promptBuilder =
				new BiometricPrompt.PromptInfo.Builder()
					.setTitle(getString(R.string.lock_unlock))
					.setDescription(getString(R.string.lock_unlock_fingerprint_description));

		if (SDK_INT >= 30) {
			promptBuilder.setAllowedAuthenticators(
					BiometricManager.Authenticators.BIOMETRIC_STRONG |
					BiometricManager.Authenticators.DEVICE_CREDENTIAL);
		} else if (SDK_INT >= 29) {
			promptBuilder.setDeviceCredentialAllowed(true);
		} else {
			promptBuilder.setNegativeButtonText(getString(R.string.lock_unlock_password));
		}

		BiometricPrompt.PromptInfo promptInfo = promptBuilder.build();

		cancelBiometricAuthentication();
		authenticationInProgress = true;

		BiometricPrompt.AuthenticationCallback callback =
				new BiometricPrompt.AuthenticationCallback() {
			@Override
			public void onAuthenticationError(int errorCode, CharSequence errString) {
				authenticationInProgress = false;

				if (errorCode == BiometricPrompt.ERROR_CANCELED ||
						errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
						errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
					finish();
				}
				else {
					if (hasKeyguardLock(UnlockActivity.this)) {
						requestKeyguardUnlock();
					} else {
						finish();
					}
				}
			}

			@Override
			public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
				authenticationInProgress = false;
				unlock();
			}

			@Override
			public void onAuthenticationFailed() {
				mainHandler.postDelayed(() -> {
				}, BRUTE_FORCE_DELAY_MS);
			}
		};

		biometricPrompt = new BiometricPrompt(this,
				getMainExecutor(), callback);

		biometricPrompt.authenticate(promptInfo);
	}

	private void requestKeyguardUnlock() {
		KeyguardManager keyguardManager =
				(KeyguardManager) getSystemService(KEYGUARD_SERVICE);
		if (keyguardManager == null) throw new AssertionError();
		Intent intent = keyguardManager.createConfirmDeviceCredentialIntent(
				SDK_INT < 23 ? getString(R.string.lock_unlock_verbose) :
						getString(R.string.lock_unlock), null);
		if (intent == null) {
			unlock();
		} else {
			keyguardShown = true;
			authenticationInProgress = true;
			try {
				startActivityForResult(intent, REQUEST_KEYGUARD_UNLOCK);
			} catch (ActivityNotFoundException e) {
				authenticationInProgress = false;
				finish();
			}
			overridePendingTransition(0, 0);
		}
	}

	private void unlock() {
		lockManager.setLocked(false);
		setResult(RESULT_OK);
		finish();
		overridePendingTransition(0, 0);
	}

}
