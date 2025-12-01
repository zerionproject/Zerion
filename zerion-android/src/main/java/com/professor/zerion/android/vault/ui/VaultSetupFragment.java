package com.professor.zerion.android.vault.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.professor.zerion.R;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;

import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultSetupFragment extends BaseFragment {

	private VaultViewModel viewModel;

	private SecurePasswordEditText passwordInput;
	private SecurePasswordEditText confirmPasswordInput;
	private TextInputLayout passwordLayout;
	private TextInputLayout confirmPasswordLayout;
	private Button createButton;
	private ProgressBar progressBar;

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_vault_setup, container, false);

		passwordInput = view.findViewById(R.id.vault_password_input);
		confirmPasswordInput = view.findViewById(R.id.vault_confirm_password_input);
		passwordLayout = view.findViewById(R.id.vault_password_layout);
		confirmPasswordLayout = view.findViewById(R.id.vault_confirm_password_layout);
		createButton = view.findViewById(R.id.vault_create_button);
		progressBar = view.findViewById(R.id.progress_bar);

		IncognitoInputHelper.configurePasswordField(passwordInput);
		IncognitoInputHelper.configurePasswordField(confirmPasswordInput);

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity())
				.get(VaultViewModel.class);

		setupPasswordValidation();
		setupCreateButton();
		observeViewModel();
	}

	private void setupPasswordValidation() {
		TextWatcher passwordWatcher = new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {}

			@Override
			public void afterTextChanged(Editable s) {
				validatePasswords();
			}
		};

		passwordInput.addTextChangedListener(passwordWatcher);
		confirmPasswordInput.addTextChangedListener(passwordWatcher);
	}

	private void validatePasswords() {
		int passwordLength = passwordInput.getPasswordLength();
		int confirmPasswordLength = confirmPasswordInput.getPasswordLength();

		if (passwordLength > 0 && passwordLength < 8) {
			passwordLayout.setError("Password must be at least 8 characters");
		} else {
			passwordLayout.setError(null);
		}

		if (confirmPasswordLength > 0) {
			char[] pwd = passwordInput.getPasswordChars();
			char[] confirmPwd = confirmPasswordInput.getPasswordChars();
			boolean matches = java.util.Arrays.equals(pwd, confirmPwd);

			java.util.Arrays.fill(pwd, '\0');
			java.util.Arrays.fill(confirmPwd, '\0');

			if (!matches) {
				confirmPasswordLayout.setError(getString(R.string.vault_password_mismatch));
			} else {
				confirmPasswordLayout.setError(null);
			}
		} else {
			confirmPasswordLayout.setError(null);
		}

		boolean valid = passwordLength >= 8 && confirmPasswordLength >= 8;
		if (valid) {
			char[] pwd = passwordInput.getPasswordChars();
			char[] confirmPwd = confirmPasswordInput.getPasswordChars();
			valid = java.util.Arrays.equals(pwd, confirmPwd);
			java.util.Arrays.fill(pwd, '\0');
			java.util.Arrays.fill(confirmPwd, '\0');
		}
		createButton.setEnabled(valid);
	}

	private void setupCreateButton() {
		createButton.setOnClickListener(v -> createVault());
		createButton.setEnabled(false);
	}

	private void createVault() {
		char[] password = passwordInput.getPasswordChars();
		char[] confirmPassword = confirmPasswordInput.getPasswordChars();

		try {
			if (password.length < 8) {
				passwordLayout.setError("Password must be at least 8 characters");
				return;
			}

			if (!java.util.Arrays.equals(password, confirmPassword)) {
				confirmPasswordLayout.setError(getString(R.string.vault_password_mismatch));
				return;
			}

			setInputsEnabled(false);

			viewModel.createVault(password, confirmPassword);

		} finally {
			passwordInput.clearPassword();
			confirmPasswordInput.clearPassword();
		}
	}

	private void observeViewModel() {
		viewModel.getIsLoading().observe(getViewLifecycleOwner(), isLoading -> {
			progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
			setInputsEnabled(!isLoading);
		});

		viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
			if (error != null && !error.isEmpty()) {
				Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
			}
		});

		viewModel.getSuccessMessage().observe(getViewLifecycleOwner(), success -> {
			if (success != null && !success.isEmpty()) {
				Toast.makeText(requireContext(), success, Toast.LENGTH_SHORT).show();
			}
		});

		viewModel.getVaultState().observe(getViewLifecycleOwner(), state -> {
			if (state == VaultViewModel.VaultState.UNLOCKED && getActivity() instanceof VaultActivity) {
				((VaultActivity) getActivity()).onVaultCreated();
			}
		});
	}

	private void setInputsEnabled(boolean enabled) {
		passwordInput.setEnabled(enabled);
		confirmPasswordInput.setEnabled(enabled);
		createButton.setEnabled(enabled && validatePasswordsQuietly());
	}

	private boolean validatePasswordsQuietly() {
		int passwordLength = passwordInput.getPasswordLength();
		int confirmPasswordLength = confirmPasswordInput.getPasswordLength();

		if (passwordLength < 8 || confirmPasswordLength < 8) {
			return false;
		}

		char[] pwd = passwordInput.getPasswordChars();
		char[] confirmPwd = confirmPasswordInput.getPasswordChars();
		boolean matches = java.util.Arrays.equals(pwd, confirmPwd);

		java.util.Arrays.fill(pwd, '\0');
		java.util.Arrays.fill(confirmPwd, '\0');

		return matches;
	}

	@Override
	public String getUniqueTag() {
		return "VaultSetupFragment";
	}
}