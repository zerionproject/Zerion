package com.professor.zerion.android.login;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ProgressBar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.briarproject.bramble.api.crypto.DecryptionResult;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
// import com.professor.zerion.android.security.SecurityManager;
// import com.professor.zerion.android.security.AntiForensics;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.inputmethod.EditorInfo.IME_ACTION_DONE;
import static androidx.core.content.ContextCompat.checkSelfPermission;
import static org.briarproject.bramble.api.crypto.DecryptionResult.KEY_STRENGTHENER_ERROR;
import static org.briarproject.bramble.api.crypto.DecryptionResult.SUCCESS;
import static com.professor.zerion.android.login.LoginUtils.createKeyStrengthenerErrorDialog;
import static com.professor.zerion.android.util.UiUtils.enterPressed;
import static com.professor.zerion.android.util.UiUtils.hideSoftKeyboard;
import static com.professor.zerion.android.util.UiUtils.hideViewOnSmallScreen;
import static com.professor.zerion.android.util.UiUtils.setError;
import static com.professor.zerion.android.util.UiUtils.showSoftKeyboard;

import java.util.logging.Logger;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class PasswordFragment extends BaseFragment implements TextWatcher {

	private static final Logger LOG = Logger.getLogger(PasswordFragment.class.getName());
	final static String TAG = PasswordFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	// Security modules - will be integrated later
	// @Inject SecurityManager securityManager;
	// @Inject AntiForensics antiForensics;

	private StartupViewModel viewModel;
	private Button signInButton;
	private ProgressBar progress;
	private TextInputLayout input;
	private TextInputEditText password;

	private final ActivityResultLauncher<String> requestPermissionLauncher =
			registerForActivityResult(new RequestPermission(), isGranted ->
					validatePassword());

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(StartupViewModel.class);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_password, container,
				false);

		// Perform security checks - temporarily disabled until Dagger integration is complete
		// performSecurityChecks();

		LifecycleOwner owner = getViewLifecycleOwner();
		viewModel.getPasswordValidated().observeEvent(owner, result -> {
			if (result == SUCCESS) {
				// Clear failed login attempts on success
				// securityManager.clearFailedLogins();
			} else {
				onPasswordInvalid(result);
			}
		});

		signInButton = v.findViewById(R.id.btn_sign_in);
		signInButton.setOnClickListener(view -> onSignInButtonClicked());
		progress = v.findViewById(R.id.progress_wheel);
		input = v.findViewById(R.id.password_layout);
		password = v.findViewById(R.id.edit_password);
		password.setOnEditorActionListener((view, actionId, event) -> {
			if (actionId == IME_ACTION_DONE || enterPressed(actionId, event)) {
				onSignInButtonClicked();
				return true;
			}
			return false;
		});
		password.addTextChangedListener(this);
		v.findViewById(R.id.btn_forgotten)
				.setOnClickListener(view -> onForgottenPasswordClick());

		// Apply Matrix-style glide-in animations
		applyMatrixAnimations(v);

		return v;
	}

	/**
	 * Applies Matrix-style glide-in animations to sign-in screen elements.
	 */
	private void applyMatrixAnimations(View rootView) {
		try {
			// Find views
			View logo = rootView.findViewById(R.id.logo);
			View passwordLayout = rootView.findViewById(R.id.password_layout);
			View forgottenButton = rootView.findViewById(R.id.btn_forgotten);
			View signInButton = rootView.findViewById(R.id.btn_sign_in);

			// Load animations
			Animation slideDownFadeIn = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_down_fade_in);
			Animation fadeInDelayed = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in_delayed);
			Animation slideUpFadeIn = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up_fade_in_delayed);
			Animation buttonFadeIn = AnimationUtils.loadAnimation(requireContext(), R.anim.button_fade_in_delayed);

			// Apply animations
			if (logo != null) {
				logo.startAnimation(slideDownFadeIn);
			}

			if (passwordLayout != null) {
				passwordLayout.startAnimation(slideUpFadeIn);
			}

			if (forgottenButton != null) {
				forgottenButton.startAnimation(slideUpFadeIn);
			}

			if (signInButton != null) {
				signInButton.startAnimation(buttonFadeIn);
			}

		} catch (Exception e) {
			// Don't crash if animations fail - just skip them
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		hideViewOnSmallScreen(requireView().findViewById(R.id.logo));
	}

	@Override
	public void beforeTextChanged(CharSequence s, int start, int count,
			int after) {
	}

	@Override
	public void onTextChanged(CharSequence s, int start, int before,
			int count) {
		if (count > 0) setError(input, null, false);
	}

	@Override
	public void afterTextChanged(Editable s) {
	}

	private void onSignInButtonClicked() {
		// Check if account is locked - temporarily disabled
		/*if (securityManager.isAccountLocked()) {
			long remainingTime = securityManager.getRemainingLockoutTime();
			int minutes = (int) (remainingTime / 60000);
			int seconds = (int) ((remainingTime % 60000) / 1000);

			String message = String.format("Account locked. Try again in %d:%02d",
					minutes, seconds);
			setError(input, message, true);
			return;
		}

		// Check for forensic attacks
		if (antiForensics.detectForensicTools()) {
			LOG.warning("Forensic tools detected - blocking login attempt");
			setError(input, getString(R.string.security_threat_detected), true);
			return;
		}*/

		hideSoftKeyboard(password);
		signInButton.setVisibility(INVISIBLE);
		progress.setVisibility(VISIBLE);
		if (SDK_INT >= 33 &&
				checkSelfPermission(requireContext(), POST_NOTIFICATIONS) !=
						PERMISSION_GRANTED) {
			// this calls validatePassword() when it returns
			requestPermissionLauncher.launch(POST_NOTIFICATIONS);
		} else {
			validatePassword();
		}
	}

	private void validatePassword() {
		viewModel.validatePassword(password.getText().toString());
	}

	private void onPasswordInvalid(DecryptionResult result) {
		// Record failed login attempt - temporarily disabled
		// securityManager.recordFailedLogin();

		signInButton.setVisibility(VISIBLE);
		progress.setVisibility(INVISIBLE);
		if (result == KEY_STRENGTHENER_ERROR) {
			createKeyStrengthenerErrorDialog(requireContext()).show();
		} else {
			// int failedAttempts = securityManager.getFailedLoginCount();
			String errorMsg = getString(R.string.try_again);

			/*if (failedAttempts > 0) {
				int remainingAttempts = 5 - failedAttempts;
				if (remainingAttempts > 0) {
					errorMsg = String.format("%s (%d attempts remaining)",
							errorMsg, remainingAttempts);
				}
			}*/

			setError(input, errorMsg, true);
			password.setText(null);
			// show the keyboard again
			showSoftKeyboard(password);
		}
	}

	private void onForgottenPasswordClick() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				requireContext(), R.style.BriarDialogTheme);
		builder.setTitle(R.string.dialog_title_lost_password);
		builder.setBackgroundInsetStart(25);
		builder.setBackgroundInsetEnd(25);
		builder.setMessage(R.string.dialog_message_lost_password);
		builder.setPositiveButton(R.string.cancel, null);
		builder.setNegativeButton(R.string.delete,
				(dialog, which) -> viewModel.deleteAccount());
		AlertDialog dialog = builder.create();
		dialog.show();
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	/**
	 * Perform security checks on startup
	 */
	private void performSecurityChecks() {
		// Temporarily disabled until Dagger integration is complete
		/*// Check security status
		SecurityManager.SecurityStatus status = securityManager.getSecurityStatus();

		if (!status.isSecure()) {
			StringBuilder warnings = new StringBuilder();

			if (status.isRooted) {
				warnings.append("\n• Device is rooted");
			}
			if (status.hasDebugger) {
				warnings.append("\n• Debugger attached");
			}
			if (status.hasDangerousPackages) {
				warnings.append("\n• Analysis tools detected");
			}
			if (status.hasFridaServer) {
				warnings.append("\n• Frida server detected");
			}
			if (status.hasHooks) {
				warnings.append("\n• Runtime hooks detected");
			}

			// Log security warnings
			LOG.warning("Security issues detected: " + warnings.toString());

			// Show warning dialog for critical threats
			if (status.getThreatLevel() >= 2) {
				showSecurityWarning(status);
			}
		}

		// Check for forensic tools
		int forensicThreatLevel = antiForensics.getForensicThreatLevel();
		if (forensicThreatLevel > 5) {
			LOG.severe("High forensic threat level detected: " + forensicThreatLevel);
		}*/
	}

	/**
	 * Show security warning dialog
	 */
	private void showSecurityWarning(Object status) { // SecurityManager.SecurityStatus status) {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				requireContext(), R.style.BriarDialogTheme);
		builder.setTitle("Security Warning");
		builder.setIcon(R.drawable.ic_warning);

		String message = "Security threats detected on this device:\n";
		// if (status.isRooted) message += "\n• Device is rooted";
		// if (status.hasDebugger) message += "\n• Debugger attached";
		// if (status.hasDangerousPackages) message += "\n• Analysis tools installed";

		message += "\n\nContinuing may compromise your security.";

		builder.setMessage(message);
		builder.setPositiveButton("Continue Anyway", null);
		builder.setNegativeButton("Exit", (dialog, which) -> {
			requireActivity().finish();
		});

		AlertDialog dialog = builder.create();
		dialog.show();
	}

}
