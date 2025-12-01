package com.professor.zerion.android.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.login.ChangePasswordActivity;
import com.professor.zerion.android.panic.WipePasswordManager;
import com.professor.zerion.android.AppModule;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static com.professor.zerion.android.util.UiUtils.hasScreenLock;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SecurityFragment extends Fragment {

	public static final String PREF_SCREEN_LOCK = "pref_key_lock";
	public static final String PREF_SCREEN_LOCK_TIMEOUT = "pref_key_lock_timeout";
	public static final String PREF_SCREENSHOT_PROTECTION = "pref_screenshot_protection";

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	@AppModule.UiPrefs
	SharedPreferences uiPrefs;

	@Inject
	com.professor.zerion.android.security.SecurityManager securityManager;

	private SettingsViewModel viewModel;
	private WipePasswordManager wipePasswordManager;


	private SwitchMaterial lockSwitch;
	private SwitchMaterial screenshotProtectionSwitch;
	private MaterialCardView lockTimeoutCard;
	private TextView lockTimeoutValue;
	private MaterialCardView changePasswordCard;
	private MaterialCardView wipePasswordCard;
	private TextView wipePasswordSummary;

	private String[] timeoutEntries;
	private String[] timeoutValues;

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(SettingsViewModel.class);
		wipePasswordManager = WipePasswordManager.getInstance(context);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_settings_security, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);


		lockSwitch = view.findViewById(R.id.lock_switch);
		screenshotProtectionSwitch = view.findViewById(R.id.screenshot_protection_switch);
		lockTimeoutCard = view.findViewById(R.id.lock_timeout_card);
		lockTimeoutValue = view.findViewById(R.id.lock_timeout_value);
		changePasswordCard = view.findViewById(R.id.change_password_card);
		wipePasswordCard = view.findViewById(R.id.wipe_password_card);
		wipePasswordSummary = view.findViewById(R.id.wipe_password_summary);


		timeoutEntries = getResources().getStringArray(R.array.pref_key_lock_timeout_entries);
		timeoutValues = getResources().getStringArray(R.array.pref_key_lock_timeout_values);

		setupAppLockSwitch();

		screenshotProtectionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (buttonView.isPressed()) {
				uiPrefs.edit().putBoolean(PREF_SCREENSHOT_PROTECTION, isChecked).apply();
				// Apply immediately to current activity
				securityManager.applyScreenshotProtection(requireActivity());
			}
		});

		lockTimeoutCard.setOnClickListener(v -> showTimeoutDialog());


		changePasswordCard.setOnClickListener(v -> {
			Intent intent = new Intent(requireContext(), ChangePasswordActivity.class);
			startActivity(intent);
		});


		wipePasswordCard.setOnClickListener(v -> {
			if (wipePasswordManager.isWipePasswordEnabled()) {
				showWipePasswordRemoveDialog();
			} else {
				showWipePasswordSetDialog();
			}
		});


		observeSettings();
		updateWipePasswordSummary();
	}

	private void setupAppLockSwitch() {
		if (hasScreenLock(requireActivity())) {
			lockSwitch.setEnabled(true);
			viewModel.getScreenLockEnabled().observe(getViewLifecycleOwner(), enabled -> {
				lockSwitch.setOnCheckedChangeListener(null);
				lockSwitch.setChecked(enabled);
				lockTimeoutCard.setEnabled(enabled);
				lockTimeoutCard.setAlpha(enabled ? 1.0f : 0.6f);
				lockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
					if (buttonView.isPressed()) {
						viewModel.settingsStore.putBoolean(PREF_SCREEN_LOCK, isChecked);
					}
				});
			});
		} else {
			lockSwitch.setEnabled(false);
			lockSwitch.setChecked(false);
			lockTimeoutCard.setEnabled(false);
			lockTimeoutCard.setAlpha(0.6f);
		}
	}

	private void observeSettings() {
		boolean screenshotProtection = uiPrefs.getBoolean(
				PREF_SCREENSHOT_PROTECTION, true);
		screenshotProtectionSwitch.setChecked(screenshotProtection);

		viewModel.getScreenLockTimeout().observe(getViewLifecycleOwner(), timeout -> {
			updateTimeoutDisplay(timeout);
		});
	}

	private void updateTimeoutDisplay(String timeout) {
		String never = getString(R.string.pref_lock_timeout_value_never);
		if (timeout.equals(never)) {
			lockTimeoutValue.setText(R.string.pref_lock_timeout_never_summary);
		} else {

			for (int i = 0; i < timeoutValues.length; i++) {
				if (timeoutValues[i].equals(timeout)) {
					lockTimeoutValue.setText(timeoutEntries[i]);
					break;
				}
			}
		}
	}

	private void showTimeoutDialog() {
		if (!lockSwitch.isChecked()) {
			return;
		}

		String currentValue = viewModel.getScreenLockTimeout().getValue();
		int selectedIndex = 0;
		for (int i = 0; i < timeoutValues.length; i++) {
			if (timeoutValues[i].equals(currentValue)) {
				selectedIndex = i;
				break;
			}
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_lock_timeout_title)
				.setSingleChoiceItems(timeoutEntries, selectedIndex, (dialog, which) -> {
					String newValue = timeoutValues[which];
					viewModel.settingsStore.putString(PREF_SCREEN_LOCK_TIMEOUT, newValue);
					updateTimeoutDisplay(newValue);
					dialog.dismiss();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.security_settings_title);
	}

	private void updateWipePasswordSummary() {
		if (wipePasswordManager.isWipePasswordEnabled()) {
			wipePasswordSummary.setText(R.string.wipe_password_summary_enabled);
		} else {
			wipePasswordSummary.setText(R.string.wipe_password_summary_disabled);
		}
	}

	private void showWipePasswordSetDialog() {
		View dialogView = getLayoutInflater().inflate(R.layout.dialog_password, null);
		TextInputLayout passwordLayout1 = dialogView.findViewById(R.id.password_layout_1);
		TextInputLayout passwordLayout2 = dialogView.findViewById(R.id.password_layout_2);
		TextInputEditText passwordInput1 = dialogView.findViewById(R.id.password_input_1);
		TextInputEditText passwordInput2 = dialogView.findViewById(R.id.password_input_2);
		TextView warningText = dialogView.findViewById(R.id.warning_text);


		if (passwordLayout1 != null) passwordLayout1.setHint(getString(R.string.wipe_password_enter));
		if (passwordLayout2 != null) passwordLayout2.setHint(getString(R.string.wipe_password_confirm));


		if (warningText != null) {
			warningText.setText(R.string.wipe_password_warning);
			warningText.setVisibility(View.VISIBLE);
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wipe_password_dialog_title)
				.setMessage(R.string.wipe_password_dialog_message)
				.setView(dialogView)
				.setPositiveButton(R.string.set, (dialog, which) -> {
					String password1 = passwordInput1 != null && passwordInput1.getText() != null
							? passwordInput1.getText().toString() : "";
					String password2 = passwordInput2 != null && passwordInput2.getText() != null
							? passwordInput2.getText().toString() : "";


					if (password1.isEmpty() || password2.isEmpty()) {
						showToast(R.string.wipe_password_too_short);
						return;
					}

					if (password1.length() < 4) {
						showToast(R.string.wipe_password_too_short);
						return;
					}

					if (!password1.equals(password2)) {
						showToast(R.string.wipe_password_mismatch);
						return;
					}


					if (wipePasswordManager.setWipePassword(password1)) {
						showToast(R.string.wipe_password_set_success);
						updateWipePasswordSummary();
					} else {
						showToast(R.string.wipe_password_set_failed);
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showWipePasswordRemoveDialog() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wipe_password_remove_title)
				.setMessage(R.string.wipe_password_remove_message)
				.setIcon(R.drawable.ic_warning)
				.setPositiveButton(R.string.remove, (dialog, which) -> {
					if (wipePasswordManager.removeWipePassword()) {
						showToast(R.string.wipe_password_remove_success);
						updateWipePasswordSummary();
					} else {
						showToast(R.string.wipe_password_remove_failed);
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showToast(int messageResId) {
		android.widget.Toast.makeText(requireContext(), messageResId,
				android.widget.Toast.LENGTH_SHORT).show();
	}

}
