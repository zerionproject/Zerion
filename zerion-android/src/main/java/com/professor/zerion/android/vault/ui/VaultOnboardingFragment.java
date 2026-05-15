package com.professor.zerion.android.vault.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
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
public class VaultOnboardingFragment extends BaseFragment {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private VaultViewModel viewModel;

	private TextInputLayout passwordLayout;
	private SecurePasswordEditText passwordInput;
	private TextInputLayout confirmPasswordLayout;
	private SecurePasswordEditText confirmPasswordInput;
	private MaterialButton createVaultButton;

	public static VaultOnboardingFragment newInstance() {
		return new VaultOnboardingFragment();
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {

		View view = inflater.inflate(R.layout.fragment_vault_onboarding, container, false);

		passwordLayout = view.findViewById(R.id.password_layout);
		passwordInput = view.findViewById(R.id.password_input);
		confirmPasswordLayout = view.findViewById(R.id.confirm_password_layout);
		confirmPasswordInput = view.findViewById(R.id.confirm_password_input);
		createVaultButton = view.findViewById(R.id.create_vault_button);

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		try {
			viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
					.get(VaultViewModel.class);
		} catch (Exception e) {
			throw e;
		}

		setupListeners();

		try {
			observeViewModel();
		} catch (Exception e) {
			throw e;
		}

		applyMatrixAnimations(view);

	}

	private void applyMatrixAnimations(View rootView) {
		try {
			View vaultTitle = rootView.findViewById(R.id.vault_title);
			View vaultSubtitle = rootView.findViewById(R.id.vault_subtitle);
			View featuresCard = rootView.findViewById(R.id.features_card);

			Animation slideDownFadeIn = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_down_fade_in);
			Animation fadeInDelayed = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in_delayed);
			Animation slideUpFadeIn = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up_fade_in_delayed);
			Animation buttonFadeIn = AnimationUtils.loadAnimation(requireContext(), R.anim.button_fade_in_delayed);

			if (vaultTitle != null) {
				vaultTitle.startAnimation(slideDownFadeIn);
			}

			if (vaultSubtitle != null) {
				vaultSubtitle.startAnimation(fadeInDelayed);
			}

			if (featuresCard != null) {
				featuresCard.startAnimation(slideUpFadeIn);
			}

			passwordLayout.startAnimation(slideUpFadeIn);
			confirmPasswordLayout.startAnimation(slideUpFadeIn);

			createVaultButton.startAnimation(buttonFadeIn);

		} catch (Exception e) {
		}
	}

	private void setupListeners() {
		TextWatcher passwordWatcher = new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				validatePasswords();
			}

			@Override
			public void afterTextChanged(Editable s) {}
		};

		passwordInput.addTextChangedListener(passwordWatcher);
		confirmPasswordInput.addTextChangedListener(passwordWatcher);

		createVaultButton.setOnClickListener(v -> createVault());
	}

	private void validatePasswords() {
		int passwordLength = passwordInput.getPasswordLength();
		int confirmPasswordLength = confirmPasswordInput.getPasswordLength();

		passwordLayout.setError(null);
		confirmPasswordLayout.setError(null);

		if (passwordLength > 0 && passwordLength < 8) {
			passwordLayout.setError("Password must be at least 8 characters");
			createVaultButton.setEnabled(false);
			return;
		}

		if (confirmPasswordLength > 0) {
			char[] pwd = passwordInput.getPasswordChars();
			char[] confirmPwd = confirmPasswordInput.getPasswordChars();
			boolean matches = java.util.Arrays.equals(pwd, confirmPwd);

			java.util.Arrays.fill(pwd, '\0');
			java.util.Arrays.fill(confirmPwd, '\0');

			if (!matches) {
				confirmPasswordLayout.setError("Passwords don't match");
				createVaultButton.setEnabled(false);
				return;
			}
		}

		createVaultButton.setEnabled(passwordLength >= 8 && confirmPasswordLength >= 8);
	}

	private void createVault() {
		char[] password = passwordInput.getPasswordChars();
		char[] confirmPassword = confirmPasswordInput.getPasswordChars();

		try {
			if (password.length == 0) {
				passwordLayout.setError("Password cannot be empty");
				return;
			}

			if (password.length < 8) {
				passwordLayout.setError("Password must be at least 8 characters");
				return;
			}

			if (!java.util.Arrays.equals(password, confirmPassword)) {
				confirmPasswordLayout.setError("Passwords don't match");
				return;
			}

			setInputsEnabled(false);
			createVaultButton.setText("Creating Vault...");

			viewModel.createVault(password, confirmPassword);

		} finally {
			passwordInput.clearPassword();
			confirmPasswordInput.clearPassword();
		}
	}

	private void setInputsEnabled(boolean enabled) {
		passwordInput.setEnabled(enabled);
		confirmPasswordInput.setEnabled(enabled);
		createVaultButton.setEnabled(enabled);
	}

	private void observeViewModel() {

		viewModel.getVaultState().removeObservers(this);
		viewModel.getIsLoading().removeObservers(this);
		viewModel.getErrorMessage().removeObservers(this);
		viewModel.getSuccessMessage().removeObservers(this);

		try {
			viewModel.getVaultState().observe(getViewLifecycleOwner(), state -> {
				if (state == VaultViewModel.VaultState.UNLOCKED) {
					VaultDashboardFragment fragment = VaultDashboardFragment.newInstance();
					((BaseFragment.BaseFragmentListener) requireActivity()).showNextFragment(fragment);
				}
			});
		} catch (Exception e) {
			throw e;
		}

		try {
			viewModel.getIsLoading().observe(getViewLifecycleOwner(), isLoading -> {
				setInputsEnabled(!isLoading);
				createVaultButton.setText(isLoading ? "Creating Vault..." : "Create Secure Vault");
			});
		} catch (Exception e) {
			throw e;
		}

		try {
			viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
				if (error != null && !error.isEmpty()) {
					Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
					setInputsEnabled(true);
					createVaultButton.setText("Create Secure Vault");
				}
			});
		} catch (Exception e) {
			throw e;
		}

		try {
			viewModel.getSuccessMessage().observe(getViewLifecycleOwner(), success -> {
			});
		} catch (Exception e) {
			throw e;
		}

	}

	@Override
	public void onStart() {
		super.onStart();
	}

	@Override
	public void onResume() {
		super.onResume();
	}

	@Override
	public void onPause() {
		super.onPause();
	}

	@Override
	public void onStop() {
		super.onStop();
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
	}

	@Override
	public String getUniqueTag() {
		return "VaultOnboardingFragment";
	}
}