package com.professor.zerion.android.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

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
	public static final String PREF_TYPING_INDICATORS = "pref_typing_indicators";
	public static final String PREF_VOICE_CALLS_ENABLED = "voice_calls_enabled";
	public static final String PREF_VIDEO_CALLS_ENABLED = "video_calls_enabled";

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
	private SwitchMaterial typingIndicatorsSwitch;
	private SwitchMaterial voiceCallsSwitch;
	private SwitchMaterial videoCallsSwitch;
	private View lockTimeoutCard;
	private TextView lockTimeoutValue;
	private View defaultTimerCard;
	private TextView defaultTimerValue;
	private View changePasswordCard;
	private View wipePasswordCard;
	private TextView wipePasswordSummary;
	private SwitchMaterial decoySwitch;
	private View decoySetCodeCard;
	private TextView decoySetCodeSummary;

	private String[] timeoutEntries;
	private String[] timeoutValues;

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(SettingsViewModel.class);
	}

	@Nullable
	private WipePasswordManager getWipePasswordManager() {
		if (wipePasswordManager == null) {
			wipePasswordManager = WipePasswordManager.getInstance(requireContext());
		}
		return wipePasswordManager;
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
		defaultTimerCard = view.findViewById(R.id.default_timer_card);
		defaultTimerValue = view.findViewById(R.id.default_timer_value);
		changePasswordCard = view.findViewById(R.id.change_password_card);
		wipePasswordCard = view.findViewById(R.id.wipe_password_card);
		wipePasswordSummary = view.findViewById(R.id.wipe_password_summary);
		decoySwitch = view.findViewById(R.id.decoy_switch);
		decoySetCodeCard = view.findViewById(R.id.decoy_set_code_card);
		decoySetCodeSummary = view.findViewById(R.id.decoy_set_code_summary);
		setupDecoyControls();

		timeoutEntries = getResources().getStringArray(R.array.pref_key_lock_timeout_entries);
		timeoutValues = getResources().getStringArray(R.array.pref_key_lock_timeout_values);

		setupAppLockSwitch();

		screenshotProtectionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (buttonView.isPressed()) {
				uiPrefs.edit().putBoolean(PREF_SCREENSHOT_PROTECTION, isChecked).apply();
				securityManager.applyScreenshotProtection(requireActivity());
			}
		});

		lockTimeoutCard.setOnClickListener(v -> showTimeoutDialog());

		defaultTimerCard.setOnClickListener(v -> showDefaultTimerDialog());
		updateDefaultTimerDisplay();

		typingIndicatorsSwitch = view.findViewById(R.id.typing_indicators_switch);
		boolean typingEnabled = uiPrefs.getBoolean(PREF_TYPING_INDICATORS, true);
		typingIndicatorsSwitch.setChecked(typingEnabled);
		typingIndicatorsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (buttonView.isPressed()) {
				uiPrefs.edit().putBoolean(PREF_TYPING_INDICATORS, isChecked).apply();
			}
		});

		voiceCallsSwitch = view.findViewById(R.id.voice_calls_switch);
		if (voiceCallsSwitch != null) {
			boolean voiceEnabled = uiPrefs.getBoolean(
					PREF_VOICE_CALLS_ENABLED, true);
			voiceCallsSwitch.setChecked(voiceEnabled);
			voiceCallsSwitch.setOnCheckedChangeListener(
					(buttonView, isChecked) -> {
				if (buttonView.isPressed()) {
					uiPrefs.edit().putBoolean(PREF_VOICE_CALLS_ENABLED,
							isChecked).apply();
					if (!isChecked && videoCallsSwitch != null
							&& videoCallsSwitch.isChecked()) {
						videoCallsSwitch.setChecked(false);
						uiPrefs.edit().putBoolean(PREF_VIDEO_CALLS_ENABLED,
								false).apply();
					}
				}
			});
		}

		videoCallsSwitch = view.findViewById(R.id.video_calls_switch);
		if (videoCallsSwitch != null) {
			boolean videoEnabled = uiPrefs.getBoolean(
					PREF_VIDEO_CALLS_ENABLED, false);
			videoCallsSwitch.setChecked(videoEnabled);
			videoCallsSwitch.setOnCheckedChangeListener(
					(buttonView, isChecked) -> {
				if (buttonView.isPressed()) {
					if (isChecked) {
						showVideoCallsBetaDialog();
					} else {
						uiPrefs.edit().putBoolean(PREF_VIDEO_CALLS_ENABLED,
								false).apply();
					}
				}
			});
		}

		changePasswordCard.setOnClickListener(v -> {
			Intent intent = new Intent(requireContext(), ChangePasswordActivity.class);
			startActivity(intent);
		});

		wipePasswordCard.setOnClickListener(v -> {
			WipePasswordManager mgr = getWipePasswordManager();
			if (mgr != null && mgr.isWipePasswordEnabled()) {
				showWipePasswordRemoveDialog();
			} else {
				showWipePasswordSetDialog();
			}
		});

		View hardenedCard = view.findViewById(R.id.hardened_mode_card);
		if (hardenedCard != null) {
			hardenedCard.setOnClickListener(v -> showHardenedModeDialog());
			TextView hardenedSummary =
					view.findViewById(R.id.hardened_mode_summary);
			if (hardenedSummary != null) {
				hardenedSummary.setText(buildHardenedSummary());
			}
		}

		observeSettings();
		updateWipePasswordSummary();
	}

	private String buildHardenedSummary() {
		int count = 0;
		if (uiPrefs.getBoolean(com.professor.zerion.android.security
				.HardenedModeEvaluator.PREF_HARDENED_BOOT, false)) count++;
		if (uiPrefs.getBoolean(com.professor.zerion.android.security
				.HardenedModeEvaluator.PREF_HARDENED_TAMPER, false)) count++;
		if (uiPrefs.getBoolean(com.professor.zerion.android.security
				.HardenedModeEvaluator.PREF_HARDENED_USB_PANIC, false)) {
			count++;
		}
		return getString(R.string.hardened_mode_summary_format, count);
	}

	private void showVideoCallsBetaDialog() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.video_calls_beta_warning_title)
				.setMessage(R.string.video_calls_beta_warning_message)
				.setCancelable(false)
				.setPositiveButton(R.string.video_calls_beta_enable,
						(dialog, which) -> {
					uiPrefs.edit().putBoolean(PREF_VIDEO_CALLS_ENABLED,
							true).apply();
					if (voiceCallsSwitch != null
							&& !voiceCallsSwitch.isChecked()) {
						voiceCallsSwitch.setChecked(true);
						uiPrefs.edit().putBoolean(PREF_VOICE_CALLS_ENABLED,
								true).apply();
					}
				})
				.setNegativeButton(R.string.cancel, (dialog, which) -> {
					if (videoCallsSwitch != null) {
						videoCallsSwitch.setChecked(false);
					}
				})
				.show();
	}

	private void showHardenedModeDialog() {
		String[] labels = {
				getString(R.string.hardened_mode_strict_boot_label),
				getString(R.string.hardened_mode_tamper_label),
				getString(R.string.hardened_mode_usb_panic_label)
		};
		boolean[] checked = {
				uiPrefs.getBoolean(com.professor.zerion.android.security
						.HardenedModeEvaluator.PREF_HARDENED_BOOT, false),
				uiPrefs.getBoolean(com.professor.zerion.android.security
						.HardenedModeEvaluator.PREF_HARDENED_TAMPER, false),
				uiPrefs.getBoolean(com.professor.zerion.android.security
						.HardenedModeEvaluator.PREF_HARDENED_USB_PANIC,
						false)
		};
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.hardened_mode_dialog_title)
				.setMultiChoiceItems(labels, checked,
						(d, which, isChecked) -> checked[which] = isChecked)
				.setPositiveButton(android.R.string.ok, (d, w) ->
						confirmHardenedSelections(checked))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void confirmHardenedSelections(boolean[] checked) {
		boolean enablingDestructive = checked[2]
				&& !uiPrefs.getBoolean(com.professor.zerion.android.security
						.HardenedModeEvaluator.PREF_HARDENED_USB_PANIC,
						false);
		if (enablingDestructive) {
			new MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.hardened_mode_usb_confirm_title)
					.setMessage(R.string.hardened_mode_usb_confirm_message)
					.setPositiveButton(
							R.string.hardened_mode_usb_confirm_action,
							(d, w) -> showUsbPanicScopeDialog(checked))
					.setNegativeButton(android.R.string.cancel, null)
					.show();
			return;
		}
		applyHardenedSelections(checked, false);
	}

	private void showUsbPanicScopeDialog(boolean[] checked) {
		String[] scope = {
				getString(R.string.hardened_mode_usb_scope_signout),
				getString(R.string.hardened_mode_usb_scope_wipe)
		};
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.hardened_mode_usb_scope_title)
				.setSingleChoiceItems(scope, 0, null)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					int sel = ((androidx.appcompat.app.AlertDialog) d)
							.getListView().getCheckedItemPosition();
					applyHardenedSelections(checked, sel == 1);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void applyHardenedSelections(boolean[] checked,
			boolean usbPanicWipes) {
		uiPrefs.edit()
				.putBoolean(com.professor.zerion.android.security
						.HardenedModeEvaluator.PREF_HARDENED_BOOT,
						checked[0])
				.putBoolean(com.professor.zerion.android.security
						.HardenedModeEvaluator.PREF_HARDENED_TAMPER,
						checked[1])
				.putBoolean(com.professor.zerion.android.security
						.HardenedModeEvaluator.PREF_HARDENED_USB_PANIC,
						checked[2])
				.putBoolean(com.professor.zerion.android.security
						.HardenedModeEvaluator
						.PREF_HARDENED_USB_PANIC_WIPE,
						usbPanicWipes)
				.apply();
		TextView hardenedSummary = requireView()
				.findViewById(R.id.hardened_mode_summary);
		if (hardenedSummary != null) {
			hardenedSummary.setText(buildHardenedSummary());
		}
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
		if (currentValue == null) currentValue = timeoutValues[0];
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

	private static final long[] DEFAULT_TIMER_VALUES =
			{-1L, 86400000L, 604800000L, 2419200000L};

	private void updateDefaultTimerDisplay() {
		long value = uiPrefs.getLong("default_disappearing_timer", -1L);
		String text = "Off";
		if (value > 0) {
			for (int i = 1; i < DEFAULT_TIMER_VALUES.length; i++) {
				if (DEFAULT_TIMER_VALUES[i] == value) {
					text = new String[]{"Off", "1 day", "1 week",
							"4 weeks"}[i];
					break;
				}
			}
		}
		defaultTimerValue.setText(text);
	}

	private void showDefaultTimerDialog() {
		String[] entries = {"Off", "1 day", "1 week", "4 weeks"};
		long stored = uiPrefs.getLong("default_disappearing_timer", -1L);
		int selectedIndex = 0;
		for (int i = 0; i < DEFAULT_TIMER_VALUES.length; i++) {
			if (DEFAULT_TIMER_VALUES[i] == stored) {
				selectedIndex = i;
				break;
			}
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_default_disappearing_title)
				.setSingleChoiceItems(entries, selectedIndex,
						(dialog, which) -> {
					long value = DEFAULT_TIMER_VALUES[which];
					uiPrefs.edit().putLong("default_disappearing_timer",
							value).apply();
					updateDefaultTimerDisplay();
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
		WipePasswordManager mgr = getWipePasswordManager();
		if (mgr != null && mgr.isWipePasswordEnabled()) {
			wipePasswordSummary.setText(R.string.wipe_password_summary_enabled);
		} else {
			wipePasswordSummary.setText(R.string.wipe_password_summary_disabled);
		}
	}

	private void setupDecoyControls() {
		boolean enabled = com.professor.zerion.android.decoy.DecoyConfig
				.isEnabled(requireContext());
		decoySwitch.setChecked(enabled);
		decoySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (!buttonView.isPressed()) return;
			if (isChecked
					&& !com.professor.zerion.android.decoy.DecoyConfig
							.hasUnlockCode(requireContext())) {
				decoySwitch.setChecked(false);
				Toast.makeText(requireContext(),
						R.string.decoy_set_code_required,
						Toast.LENGTH_LONG).show();
				return;
			}
			com.professor.zerion.android.decoy.DecoyConfig
					.setEnabled(requireContext(), isChecked);
		});
		decoySetCodeCard.setOnClickListener(v -> showDecoySetCodeDialog());
		updateDecoyCodeSummary();
	}

	private void updateDecoyCodeSummary() {
		boolean has = com.professor.zerion.android.decoy.DecoyConfig
				.hasUnlockCode(requireContext());
		decoySetCodeSummary.setText(has
				? R.string.decoy_set_code_summary_set
				: R.string.decoy_set_code_summary_unset);
	}

	private void showDecoySetCodeDialog() {
		View dialogView = getLayoutInflater().inflate(
				R.layout.dialog_password, null);
		TextInputLayout codeLayout1 = dialogView.findViewById(
				R.id.password_layout_1);
		TextInputLayout codeLayout2 = dialogView.findViewById(
				R.id.password_layout_2);
		TextInputEditText codeInput1 = dialogView.findViewById(
				R.id.password_input_1);
		TextInputEditText codeInput2 = dialogView.findViewById(
				R.id.password_input_2);
		TextView warningText = dialogView.findViewById(R.id.warning_text);
		codeLayout1.setHint(getString(R.string.decoy_set_code_hint));
		codeLayout2.setHint(getString(R.string.decoy_set_code_confirm_hint));
		int maskedNumber = android.text.InputType.TYPE_CLASS_NUMBER
				| android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD;
		codeInput1.setInputType(maskedNumber);
		codeInput2.setInputType(maskedNumber);
		android.widget.CheckBox showCode =
				dialogView.findViewById(R.id.show_password_checkbox);
		if (showCode != null) {
			showCode.setOnCheckedChangeListener((btn, checked) -> {
				int type = checked
						? android.text.InputType.TYPE_CLASS_NUMBER
						: maskedNumber;
				codeInput1.setInputType(type);
				codeInput2.setInputType(type);
				codeInput1.setSelection(codeInput1.length());
				codeInput2.setSelection(codeInput2.length());
			});
		}
		warningText.setText(R.string.decoy_set_code_warning);
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.decoy_set_code_title)
				.setView(dialogView)
				.setPositiveButton(R.string.decoy_set_code_save,
						(d, w) -> {
							char[] a = readChars(codeInput1);
							char[] b = readChars(codeInput2);
							try {
								if (a.length == 0) {
									Toast.makeText(requireContext(),
											R.string.decoy_set_code_empty,
											Toast.LENGTH_SHORT).show();
									return;
								}
								if (!java.util.Arrays.equals(a, b)) {
									Toast.makeText(requireContext(),
											R.string.decoy_set_code_mismatch,
											Toast.LENGTH_SHORT).show();
									return;
								}
								com.professor.zerion.android.decoy.DecoyConfig
										.setUnlockCode(requireContext(), a);
								updateDecoyCodeSummary();
								Toast.makeText(requireContext(),
										R.string.decoy_set_code_saved,
										Toast.LENGTH_SHORT).show();
							} finally {
								java.util.Arrays.fill(a, '\0');
								java.util.Arrays.fill(b, '\0');
							}
						})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static char[] readChars(TextInputEditText input) {
		android.text.Editable e = input.getText();
		if (e == null) return new char[0];
		char[] out = new char[e.length()];
		e.getChars(0, e.length(), out, 0);
		return out;
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

		android.widget.CheckBox showWipePwd =
				dialogView.findViewById(R.id.show_password_checkbox);
		if (showWipePwd != null && passwordInput1 != null && passwordInput2 != null) {
			showWipePwd.setOnCheckedChangeListener((btn, checked) -> {
				int type = checked
						? android.text.InputType.TYPE_CLASS_TEXT
								| android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
						: android.text.InputType.TYPE_CLASS_TEXT
								| android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;
				passwordInput1.setInputType(type);
				passwordInput2.setInputType(type);
				passwordInput1.setSelection(passwordInput1.length());
				passwordInput2.setSelection(passwordInput2.length());
			});
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.wipe_password_dialog_title)
				.setMessage(R.string.wipe_password_dialog_message)
				.setView(dialogView)
				.setPositiveButton(R.string.set, (dialog, which) -> {
					char[] pw1 = null;
					char[] pw2 = null;
					try {
						CharSequence t1 = passwordInput1 != null ? passwordInput1.getText() : null;
						CharSequence t2 = passwordInput2 != null ? passwordInput2.getText() : null;
						pw1 = t1 != null && t1.length() > 0 ? new char[t1.length()] : null;
						pw2 = t2 != null && t2.length() > 0 ? new char[t2.length()] : null;
						if (pw1 == null || pw2 == null) {
							showToast(R.string.wipe_password_too_short);
							return;
						}
						for (int i = 0; i < t1.length(); i++) pw1[i] = t1.charAt(i);
						for (int i = 0; i < t2.length(); i++) pw2[i] = t2.charAt(i);

						if (pw1.length < 4) {
							showToast(R.string.wipe_password_too_short);
							return;
						}

						if (!java.util.Arrays.equals(pw1, pw2)) {
							showToast(R.string.wipe_password_mismatch);
							return;
						}

						WipePasswordManager mgr = getWipePasswordManager();
						final char[] pwToSet = pw1;
						new Thread(() -> {
							boolean ok = mgr != null && mgr.setWipePassword(pwToSet);
							java.util.Arrays.fill(pwToSet, '\0');
							requireActivity().runOnUiThread(() -> {
								if (ok) {
									showToast(R.string.wipe_password_set_success);
									updateWipePasswordSummary();
								} else {
									showToast(R.string.wipe_password_set_failed);
								}
							});
						}).start();
						pw1 = null;
					} finally {
						if (pw1 != null) java.util.Arrays.fill(pw1, '\0');
						if (pw2 != null) java.util.Arrays.fill(pw2, '\0');
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
					WipePasswordManager mgr = getWipePasswordManager();
					if (mgr != null && mgr.removeWipePassword()) {
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
