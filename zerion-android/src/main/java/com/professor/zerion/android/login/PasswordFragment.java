package com.professor.zerion.android.login;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;

import java.util.Arrays;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.briarproject.bramble.api.crypto.DecryptionResult;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.login.BruteForceProtection.FailureResult;
import com.professor.zerion.android.login.BruteForceProtection.LockStatus;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class PasswordFragment extends BaseFragment implements TextWatcher {

	final static String TAG = PasswordFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

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

		LifecycleOwner owner = getViewLifecycleOwner();
		viewModel.getPasswordValidated().observeEvent(owner, result -> {
			if (result == SUCCESS) {
			} else {
				onPasswordInvalid(result);
			}
		});

		viewModel.getLockoutStatus().observeEvent(owner, this::onAccountLocked);
		viewModel.getBruteForceFailure().observeEvent(owner, this::onBruteForceFailure);
		viewModel.getTriggerWipe().observeEvent(owner, wipe -> {
			if (wipe) {
				onWipeTriggered();
			}
		});
		viewModel.getOperationalFailure().observeEvent(owner, failed -> {
			if (failed) {
				onOperationalFailure();
			}
		});

		signInButton = v.findViewById(R.id.btn_sign_in);
		signInButton.setOnClickListener(view -> onSignInButtonClicked());
		progress = v.findViewById(R.id.progress_wheel);
		input = v.findViewById(R.id.password_layout);
		password = v.findViewById(R.id.edit_password);

		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
			password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
		}
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
		setupKeyboardInsetsHandling(v);

		return v;
	}

	
	private void setupKeyboardInsetsHandling(View rootView) {
		View loginContent = rootView.findViewById(R.id.login_content);
		View particleFieldView = rootView.findViewById(R.id.particle_field_view);

		if (loginContent == null) return;
		ViewCompat.setOnApplyWindowInsetsListener(loginContent, (v, windowInsets) -> {
			Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
			Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
			int bottomPadding = Math.max(ime.bottom, systemBars.bottom);
			v.setPadding(
					v.getPaddingLeft(),
					systemBars.top,
					v.getPaddingRight(),
					bottomPadding
			);
			return windowInsets;
		});
		if (particleFieldView != null) {
			ViewCompat.setOnApplyWindowInsetsListener(particleFieldView, (v, windowInsets) -> {
				v.setPadding(0, 0, 0, 0);
				return windowInsets;
			});
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
		String passwordStr = password.getText() != null ? password.getText().toString() : "";

		if (passwordStr.isEmpty()) {
			setError(input, getString(R.string.password_missing), true);
			return;
		}

		hideSoftKeyboard(password);
		signInButton.setVisibility(INVISIBLE);
		progress.setVisibility(VISIBLE);

		if (SDK_INT >= 33 &&
				checkSelfPermission(requireContext(), POST_NOTIFICATIONS) !=
						PERMISSION_GRANTED) {
			requestPermissionLauncher.launch(POST_NOTIFICATIONS);
		} else {
			validatePassword();
		}
	}

	private void validatePassword() {
		String passwordStr = password.getText() != null ? password.getText().toString() : "";
		if (passwordStr.isEmpty()) {
			signInButton.setVisibility(VISIBLE);
			progress.setVisibility(INVISIBLE);
			return;
		}

		char[] passwordChars = passwordStr.toCharArray();
		password.setText(null);
		viewModel.validatePassword(passwordChars);
	}

	private void onPasswordInvalid(DecryptionResult result) {
		signInButton.setVisibility(VISIBLE);
		progress.setVisibility(INVISIBLE);
		if (result == KEY_STRENGTHENER_ERROR) {
			createKeyStrengthenerErrorDialog(requireContext()).show();
		} else {
			String errorMsg = getString(R.string.try_again);
			setError(input, errorMsg, true);
			password.setText(null);
			showSoftKeyboard(password);
		}
	}

	private void onForgottenPasswordClick() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				requireContext(), R.style.ZerionDialogTheme);
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

	private void onAccountLocked(LockStatus lockStatus) {
		signInButton.setVisibility(VISIBLE);
		progress.setVisibility(INVISIBLE);

		int minutes = lockStatus.getRemainingMinutes();

		String timeMsg;
		if (minutes >= 1) {
			timeMsg = (minutes + 1) + " minute(s)";
		} else {
			timeMsg = "1 minute";
		}

		String errorMsg = getString(R.string.account_locked_try_again, timeMsg);
		setError(input, errorMsg, true);
		password.setText(null);
		hideSoftKeyboard(password);
	}

	private void onBruteForceFailure(FailureResult result) {
		signInButton.setVisibility(VISIBLE);
		progress.setVisibility(INVISIBLE);

		switch (result.type) {
			case NORMAL_FAILURE:
				String msg = getString(R.string.login_failed_normal, result.attemptsRemaining);
				setError(input, msg, true);
				password.setText(null);
				showSoftKeyboard(password);
				break;

			case LOCKOUT:
				String lockoutMsg = getString(R.string.login_failed_lockout);
				setError(input, lockoutMsg, true);
				password.setText(null);
				hideSoftKeyboard(password);
				break;

			case FINAL_WARNING:
				String warningMsg = getString(R.string.login_failed_final_warning, result.attemptsRemaining);
				setError(input, warningMsg, true);

				MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
						requireContext(), R.style.ZerionDialogTheme);
				builder.setTitle(R.string.dialog_title_critical_warning);
				builder.setMessage(getString(R.string.dialog_message_critical_warning, result.attemptsRemaining));
				builder.setPositiveButton(R.string.dialog_button_understand, null);
				builder.show();

				password.setText(null);
				hideSoftKeyboard(password);
				break;
		}
	}

	private void onWipeTriggered() {
		viewModel.deleteAccount();
		requireActivity().finishAffinity();
	}

	private void onOperationalFailure() {
		signInButton.setVisibility(VISIBLE);
		progress.setVisibility(INVISIBLE);

		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				requireContext(), R.style.ZerionDialogTheme);
		builder.setTitle(R.string.dialog_title_cannot_check_password);
		builder.setMessage(R.string.dialog_message_cannot_check_password);
		builder.setPositiveButton(android.R.string.ok, null);
		builder.show();

		password.setText(null);
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

}
