package com.professor.zerion.android.vault.ui;

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
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultSettingsFragment extends BaseFragment {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	@AppModule.SecurePrefs
	SharedPreferences securePrefs;

	private VaultViewModel viewModel;

	private MaterialCardView changePasswordCard;
	private MaterialCardView autolockCard;
	private SwitchMaterial biometricSwitch;
	private TextView autolockValue;

	private SwitchMaterial clipboardSwitch;
	private SwitchMaterial hideContentSwitch;
	private TextView clipboardTimeoutValue;

	private MaterialCardView exportCard;
	private MaterialCardView wipeVaultCard;

	private TextView itemsCountInfo;

	private com.google.android.material.button.MaterialButton backToVaultButton;

	private int currentAutolockTimeout = 60;
	private int currentClipboardTimeout = 30;

	public static VaultSettingsFragment newInstance() {
		return new VaultSettingsFragment();
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_vault_settings, container, false);

		changePasswordCard = view.findViewById(R.id.change_password_card);
		autolockCard = view.findViewById(R.id.autolock_card);
		biometricSwitch = view.findViewById(R.id.biometric_switch);
		autolockValue = view.findViewById(R.id.autolock_value);
		clipboardSwitch = view.findViewById(R.id.clipboard_switch);
		hideContentSwitch = view.findViewById(R.id.hide_content_switch);
		clipboardTimeoutValue = view.findViewById(R.id.clipboard_timeout_value);

		exportCard = view.findViewById(R.id.export_card);
		wipeVaultCard = view.findViewById(R.id.wipe_vault_card);

		itemsCountInfo = view.findViewById(R.id.items_count_info);

		backToVaultButton = view.findViewById(R.id.back_to_vault_button);

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		setupClickListeners();
		observeViewModel();
		loadSettings();
	}

	private void setupClickListeners() {
		changePasswordCard.setOnClickListener(v -> showChangePasswordDialog());

		autolockCard.setOnClickListener(v -> showAutolockDialog());

		biometricSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (isChecked) {
				enableBiometricAuth();
			} else {
				disableBiometricAuth();
			}
			saveSetting("biometric_enabled", isChecked);
		});

		clipboardSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			saveSetting("clipboard_clear_enabled", isChecked);
		});

		hideContentSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			saveSetting("hide_content_enabled", isChecked);
		});

		exportCard.setOnClickListener(v -> showExportDialog());

		wipeVaultCard.setOnClickListener(v -> showWipeVaultDialog());

		backToVaultButton.setOnClickListener(v -> {
			VaultDashboardFragment fragment = VaultDashboardFragment.newInstance();
			showNextFragment(fragment);
		});
	}

	private void showChangePasswordDialog() {
		View dialogView = LayoutInflater.from(requireContext())
				.inflate(R.layout.dialog_change_password, null);

		TextInputLayout currentPasswordLayout = dialogView.findViewById(R.id.current_password_layout);
		SecurePasswordEditText currentPasswordInput = dialogView.findViewById(R.id.current_password_input);
		TextInputLayout newPasswordLayout = dialogView.findViewById(R.id.new_password_layout);
		SecurePasswordEditText newPasswordInput = dialogView.findViewById(R.id.new_password_input);
		TextInputLayout confirmPasswordLayout = dialogView.findViewById(R.id.confirm_password_layout);
		SecurePasswordEditText confirmPasswordInput = dialogView.findViewById(R.id.confirm_password_input);

		IncognitoInputHelper.configurePasswordField(currentPasswordInput);
		IncognitoInputHelper.configurePasswordField(newPasswordInput);
		IncognitoInputHelper.configurePasswordField(confirmPasswordInput);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle("Change Master Password")
				.setView(dialogView)
				.setPositiveButton("Change", (dialog, which) -> {
					char[] currentPassword = currentPasswordInput.getPasswordChars();
					char[] newPassword = newPasswordInput.getPasswordChars();
					char[] confirmPassword = confirmPasswordInput.getPasswordChars();

					try {
						if (validatePasswordChange(currentPassword, newPassword, confirmPassword,
								currentPasswordLayout, newPasswordLayout, confirmPasswordLayout)) {
							changePassword(currentPassword, newPassword);
						}
					} finally {
						currentPasswordInput.clearPassword();
						newPasswordInput.clearPassword();
						confirmPasswordInput.clearPassword();
						java.util.Arrays.fill(currentPassword, '\0');
						java.util.Arrays.fill(newPassword, '\0');
						java.util.Arrays.fill(confirmPassword, '\0');
					}
				})
				.setNegativeButton("Cancel", null)
				.show();
	}

	private boolean validatePasswordChange(char[] current, char[] newPass, char[] confirm,
			TextInputLayout currentLayout, TextInputLayout newLayout, TextInputLayout confirmLayout) {
		boolean valid = true;

		if (current.length == 0) {
			currentLayout.setError("Enter current password");
			valid = false;
		}

		if (newPass.length == 0) {
			newLayout.setError("Enter new password");
			valid = false;
		} else if (newPass.length < 8) {
			newLayout.setError("Password must be at least 8 characters");
			valid = false;
		}

		if (!java.util.Arrays.equals(newPass, confirm)) {
			confirmLayout.setError("Passwords don't match");
			valid = false;
		}

		return valid;
	}

	private void changePassword(char[] currentPassword, char[] newPassword) {
		viewModel.changePassword(currentPassword, newPassword);

		viewModel.getSuccessMessage().observe(getViewLifecycleOwner(), success -> {
			if (success != null && success.contains("Password changed")) {
				showToast("Password changed successfully");
			}
		});

		viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
			if (error != null && !error.isEmpty()) {
				showToast(error);
			}
		});
	}

	private void showAutolockDialog() {
		String[] options = {"30 seconds", "60 seconds", "2 minutes", "5 minutes", "Never"};
		int[] values = {30, 60, 120, 300, -1};

		int selectedIndex = 1;
		for (int i = 0; i < values.length; i++) {
			if (values[i] == currentAutolockTimeout) {
				selectedIndex = i;
				break;
			}
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle("Auto-lock Timeout")
				.setSingleChoiceItems(options, selectedIndex, (dialog, which) -> {
					currentAutolockTimeout = values[which];
					autolockValue.setText(options[which]);
					saveSetting("autolock_timeout", currentAutolockTimeout);
					dialog.dismiss();
				})
				.show();
	}

	private void showExportDialog() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle("Export Vault")
				.setMessage("Create an encrypted backup of your vault. You'll need to set a password for the backup file.")
				.setPositiveButton("Export", (dialog, which) -> {
					showExportPasswordDialog();
				})
				.setNegativeButton("Cancel", null)
				.show();
	}

	private void showExportPasswordDialog() {
		View dialogView = LayoutInflater.from(requireContext())
				.inflate(R.layout.dialog_export_password, null);

		com.google.android.material.textfield.TextInputEditText passwordInput =
				dialogView.findViewById(R.id.export_password_input);
		com.google.android.material.textfield.TextInputEditText confirmInput =
				dialogView.findViewById(R.id.export_password_confirm);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle("Set Export Password")
				.setMessage("Enter a password to encrypt your vault backup")
				.setView(dialogView)
				.setPositiveButton("Export", (dialog, which) -> {
					String password = passwordInput.getText() != null ?
							passwordInput.getText().toString() : "";
					String confirm = confirmInput.getText() != null ?
							confirmInput.getText().toString() : "";

					if (password.isEmpty()) {
						showToast("Password cannot be empty");
						return;
					}

					if (!password.equals(confirm)) {
						showToast("Passwords do not match");
						return;
					}

					exportVault(password.toCharArray());
				})
				.setNegativeButton("Cancel", null)
				.show();
	}

	private void exportVault(char[] exportPassword) {
		viewModel.exportVault(exportPassword, new VaultViewModel.ExportCallback() {
			@Override
			public void onExportSuccess(byte[] exportData) {
				try {
					String fileName = "vault_backup.vbk";
					java.io.File exportFile = new java.io.File(requireContext().getFilesDir(), fileName);
					java.io.FileOutputStream fos = new java.io.FileOutputStream(exportFile);
					fos.write(exportData);
					fos.close();

					java.util.Arrays.fill(exportData, (byte) 0);

					requireActivity().runOnUiThread(() -> {
						showToast("Vault exported securely");
					});
				} catch (Exception e) {
					requireActivity().runOnUiThread(() -> {
						showToast("Failed to save export file");
					});
				}
			}

			@Override
			public void onExportError(String error) {
				requireActivity().runOnUiThread(() -> {
					showToast("Export failed");
				});
			}
		});
	}

	private void showWipeVaultDialog() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle("WARNING: Wipe Vault")
				.setMessage("This will permanently delete all vault data. This action cannot be undone!\n\nAre you absolutely sure?")
				.setPositiveButton("WIPE VAULT", (dialog, which) -> {
					showWipeConfirmationDialog();
				})
				.setNegativeButton("Cancel", null)
				.show();
	}

	private void showWipeConfirmationDialog() {
		TextInputEditText confirmInput = new TextInputEditText(requireContext());
		confirmInput.setHint("Type DELETE");
		IncognitoInputHelper.configureForVault(confirmInput);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle("Final Confirmation")
				.setMessage("Type 'DELETE' to confirm vault wipe:")
				.setView(confirmInput)
				.setPositiveButton("Wipe", (dialog, which) -> {
					String typed = confirmInput.getText() != null ?
						confirmInput.getText().toString() : "";
					if (typed.equals("DELETE")) {
						wipeVault();
					} else {
						showToast("Confirmation text does not match");
					}
				})
				.setNegativeButton("Cancel", null)
				.show();
	}

	private void wipeVault() {
		viewModel.wipeVault();
		viewModel.clearSensitiveMemory();

		viewModel.getSuccessMessage().observe(getViewLifecycleOwner(), success -> {
			if (success != null && success.contains("wiped")) {
				if (getActivity() instanceof VaultActivity) {
					((VaultActivity) getActivity()).showFragment(
						new VaultSetupFragment(), "vault_setup", false);
				}
			}
		});
	}

	private void loadSettings() {
		currentAutolockTimeout = securePrefs.getInt("autolock_timeout", 60);
		updateAutolockDisplay();

		currentClipboardTimeout = securePrefs.getInt("clipboard_timeout", 30);
		updateClipboardTimeoutDisplay();

		biometricSwitch.setChecked(securePrefs.getBoolean("biometric_enabled", false));
		clipboardSwitch.setChecked(securePrefs.getBoolean("clipboard_clear_enabled", true));
		hideContentSwitch.setChecked(securePrefs.getBoolean("hide_content_enabled", true));
	}

	private void updateAutolockDisplay() {
		String display;
		if (currentAutolockTimeout == -1) {
			display = "Never";
		} else if (currentAutolockTimeout < 60) {
			display = currentAutolockTimeout + " seconds";
		} else {
			display = (currentAutolockTimeout / 60) + " minutes";
		}
		autolockValue.setText(display);
	}

	private void updateClipboardTimeoutDisplay() {
		String display;
		if (currentClipboardTimeout < 60) {
			display = currentClipboardTimeout + " seconds";
		} else {
			display = (currentClipboardTimeout / 60) + " minutes";
		}
		if (clipboardTimeoutValue != null) {
			clipboardTimeoutValue.setText(display);
		}
	}

	private void saveSetting(String key, Object value) {
		SharedPreferences.Editor editor = securePrefs.edit();

		if (value instanceof Boolean) {
			editor.putBoolean(key, (Boolean) value);
		} else if (value instanceof Integer) {
			editor.putInt(key, (Integer) value);
		} else if (value instanceof String) {
			editor.putString(key, (String) value);
		}

		editor.apply();
	}

	private void enableBiometricAuth() {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
			android.hardware.fingerprint.FingerprintManager fingerprintManager =
				(android.hardware.fingerprint.FingerprintManager) requireContext()
					.getSystemService(android.content.Context.FINGERPRINT_SERVICE);

			if (fingerprintManager != null && fingerprintManager.isHardwareDetected()) {
				if (fingerprintManager.hasEnrolledFingerprints()) {
					saveSetting("biometric_enabled", true);
					showToast("Biometric authentication enabled");
				} else {
					showToast("No fingerprints enrolled");
					biometricSwitch.setChecked(false);
				}
			} else {
				showToast("Biometric hardware not available");
				biometricSwitch.setChecked(false);
			}
		} else {
			showToast("Biometric authentication not supported on this device");
			biometricSwitch.setChecked(false);
		}
	}

	private void disableBiometricAuth() {
		saveSetting("biometric_enabled", false);
		showToast("Biometric authentication disabled");
	}

	private void observeViewModel() {
		viewModel.getVaultItems().observe(getViewLifecycleOwner(), items -> {
			if (items != null) {
				itemsCountInfo.setText(getString(
						R.string.vault_settings_info_items_count, items.size()));
			}
		});
	}

	private void showToast(String message) {
		android.widget.Toast.makeText(requireContext(), message,
				android.widget.Toast.LENGTH_SHORT).show();
	}

	@Override
	public String getUniqueTag() {
		return "VaultSettingsFragment";
	}
}