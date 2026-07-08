package com.professor.zerion.android.vault.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.ProgressBar;

import com.google.android.material.textfield.TextInputLayout;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultUnlockFragment extends BaseFragment {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private VaultViewModel viewModel;

	private SecurePasswordEditText passwordInput;
	private TextInputLayout passwordLayout;
	private Button unlockButton;
	private ProgressBar progressBar;

	public static VaultUnlockFragment newInstance() {
		return new VaultUnlockFragment();
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_vault_unlock, container, false);

		passwordInput = view.findViewById(R.id.vault_password_input);
		passwordLayout = view.findViewById(R.id.vault_password_layout);
		unlockButton = view.findViewById(R.id.vault_unlock_button);
		progressBar = view.findViewById(R.id.progress_bar);

		IncognitoInputHelper.configurePasswordField(passwordInput);

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		setupUnlockButton();
		setupPasswordInput();
		observeViewModel();
	}

	private void setupPasswordInput() {
		passwordInput.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE ||
					actionId == EditorInfo.IME_ACTION_GO) {
				unlockVault();
				return true;
			}
			return false;
		});

		passwordInput.addTextChangedListener(new SimpleTextWatcher() {
			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				passwordLayout.setError(null);
			}
		});
	}

	private void setupUnlockButton() {
		unlockButton.setOnClickListener(v -> unlockVault());
	}

	private void unlockVault() {
		if (passwordInput.isPasswordEmpty()) {
			passwordLayout.setError("Please enter password");
			return;
		}

		char[] password = passwordInput.getPasswordChars();

		setInputsEnabled(false);

		viewModel.unlockVault(password);

		passwordInput.clearPassword();
	}

	private void observeViewModel() {
		viewModel.getIsLoading().observe(getViewLifecycleOwner(), isLoading -> {
			progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
			setInputsEnabled(!isLoading);
		});

		viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
			if (error != null && !error.isEmpty()) {
				passwordInput.clearPassword();
				passwordInput.requestFocus();
				passwordLayout.animate()
						.translationX(-10f)
						.setDuration(50)
						.withEndAction(() -> {
							passwordLayout.animate()
									.translationX(10f)
									.setDuration(50)
									.withEndAction(() -> {
										passwordLayout.animate()
												.translationX(0f)
												.setDuration(50)
												.start();
									})
									.start();
						})
						.start();
			}
		});

		viewModel.getVaultState().observe(getViewLifecycleOwner(), state -> {
			if (state == VaultViewModel.VaultState.UNLOCKED) {
				VaultDashboardFragment fragment = VaultDashboardFragment.newInstance();
				((BaseFragment.BaseFragmentListener) requireActivity()).showNextFragment(fragment);
			}
		});
	}

	private void setInputsEnabled(boolean enabled) {
		passwordInput.setEnabled(enabled);
		unlockButton.setEnabled(enabled);
	}

	@Override
	public String getUniqueTag() {
		return "VaultUnlockFragment";
	}

	private abstract static class SimpleTextWatcher implements android.text.TextWatcher {
		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

		@Override
		public void afterTextChanged(android.text.Editable s) {}
	}
}