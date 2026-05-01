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

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.login.ChangePasswordActivity;
import com.professor.zerion.android.panic.WipePasswordManager;
import com.professor.zerion.android.AppModule;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.plugin.tor.B4OnionRotation;

import java.util.concurrent.Executor;

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

	@Inject
	B4OnionRotation b4OnionRotation;

	@Inject
	@IoExecutor
	Executor ioExecutor;

	private SettingsViewModel viewModel;
	private WipePasswordManager wipePasswordManager;


	private SwitchMaterial lockSwitch;
	private SwitchMaterial screenshotProtectionSwitch;
	private SwitchMaterial typingIndicatorsSwitch;
	private SwitchMaterial voiceCallsSwitch;
	private SwitchMaterial videoCallsSwitch;
	private View lockTimeoutCard;
	private TextView lockTimeoutValue;
	private View changePasswordCard;
	private View registrationLockCard;
	private TextView registrationLockSummary;
	private View wipePasswordCard;
	private TextView wipePasswordSummary;
	private View rotateOnionCard;
	private View forceCompleteRotationCard;

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
		changePasswordCard = view.findViewById(R.id.change_password_card);
		registrationLockCard = view.findViewById(R.id.registration_lock_card);
		registrationLockSummary = view.findViewById(R.id.reg_lock_summary);
		wipePasswordCard = view.findViewById(R.id.wipe_password_card);
		wipePasswordSummary = view.findViewById(R.id.wipe_password_summary);
		rotateOnionCard = view.findViewById(R.id.rotate_onion_card);
		forceCompleteRotationCard =
				view.findViewById(R.id.force_complete_rotation_card);


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

		typingIndicatorsSwitch = view.findViewById(R.id.typing_indicators_switch);
		boolean typingEnabled = uiPrefs.getBoolean(PREF_TYPING_INDICATORS, true);
		typingIndicatorsSwitch.setChecked(typingEnabled);
		typingIndicatorsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (buttonView.isPressed()) {
				uiPrefs.edit().putBoolean(PREF_TYPING_INDICATORS, isChecked).apply();
			}
		});

		if (rotateOnionCard != null) {
			rotateOnionCard.setOnClickListener(v -> showRotateOnionDialog());
		}

		if (forceCompleteRotationCard != null) {
			forceCompleteRotationCard.setOnClickListener(
					v -> showForceCompleteRotationDialog());
		}

		voiceCallsSwitch = view.findViewById(R.id.voice_calls_switch);
		if (voiceCallsSwitch != null) {
			boolean voiceEnabled = uiPrefs.getBoolean(
					PREF_VOICE_CALLS_ENABLED, false);
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
					uiPrefs.edit().putBoolean(PREF_VIDEO_CALLS_ENABLED,
							isChecked).apply();
					if (isChecked && voiceCallsSwitch != null
							&& !voiceCallsSwitch.isChecked()) {
						voiceCallsSwitch.setChecked(true);
						uiPrefs.edit().putBoolean(PREF_VOICE_CALLS_ENABLED,
								true).apply();
					}
				}
			});
		}

		changePasswordCard.setOnClickListener(v -> {
			Intent intent = new Intent(requireContext(), ChangePasswordActivity.class);
			startActivity(intent);
		});


		if (registrationLockCard != null) {
			registrationLockCard.setOnClickListener(v -> showRegistrationLockDialog());
			updateRegistrationLockSummary();
		}

		wipePasswordCard.setOnClickListener(v -> {
			WipePasswordManager mgr = getWipePasswordManager();
			if (mgr != null && mgr.isWipePasswordEnabled()) {
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

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.security_settings_title);
		refreshForceCompleteVisibility();
	}

	private void refreshForceCompleteVisibility() {
		if (forceCompleteRotationCard == null) return;
		ioExecutor.execute(() -> {
			B4OnionRotation.RotationPhase phase;
			try {
				phase = b4OnionRotation.getPhase();
			} catch (DbException e) {
				phase = B4OnionRotation.RotationPhase.IDLE;
			}
			final boolean show =
					phase == B4OnionRotation.RotationPhase.ANNOUNCING;
			if (getActivity() == null) return;
			requireActivity().runOnUiThread(() -> {
				if (forceCompleteRotationCard == null) return;
				forceCompleteRotationCard.setVisibility(
						show ? View.VISIBLE : View.GONE);
			});
		});
	}

	private void showRotateOnionDialog() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_rotate_onion_confirm_title)
				.setMessage(R.string.pref_rotate_onion_confirm_message)
				.setPositiveButton(R.string.pref_rotate_onion_confirm_action,
						(dialog, which) -> triggerRotation())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showForceCompleteRotationDialog() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_force_complete_rotation_confirm_title)
				.setMessage(
						R.string.pref_force_complete_rotation_confirm_message)
				.setPositiveButton(
						R.string.pref_force_complete_rotation_confirm_action,
						(dialog, which) -> triggerForceCompleteRotation())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void triggerForceCompleteRotation() {
		Context appContext = requireContext().getApplicationContext();
		ioExecutor.execute(() -> {
			boolean wasAnnouncing = false;
			try {
				wasAnnouncing = b4OnionRotation.getPhase()
						== B4OnionRotation.RotationPhase.ANNOUNCING;
				b4OnionRotation.forceCompleteRotation();
			} catch (DbException ignored) {
			}
			final boolean wasAnnouncingFinal = wasAnnouncing;
			if (getActivity() == null) return;
			requireActivity().runOnUiThread(() -> {
				if (getContext() == null) return;
				Toast.makeText(appContext, wasAnnouncingFinal
								? R.string.pref_force_complete_rotation_done
								: R.string.pref_force_complete_rotation_not_announcing,
						Toast.LENGTH_LONG).show();
				refreshForceCompleteVisibility();
				new androidx.lifecycle.ViewModelProvider(
						requireActivity(), viewModelFactory)
						.get(com.professor.zerion.android.navdrawer
								.PluginViewModel.class)
						.refreshTorState();
			});
		});
	}

	private void triggerRotation() {
		Context appContext = requireContext().getApplicationContext();
		ioExecutor.execute(() -> {
			String newOnion = null;
			boolean success = false;
			try {
				b4OnionRotation.forceRotate();
				newOnion = b4OnionRotation.getAliceNextOnion();
				success = true;
			} catch (DbException ignored) {
			}
			boolean ok = success;
			String displayOnion = newOnion;
			if (getActivity() == null) return;
			requireActivity().runOnUiThread(() -> {
				if (getContext() == null) return;
				if (ok && displayOnion != null) {
					new androidx.lifecycle.ViewModelProvider(
							requireActivity(), viewModelFactory)
							.get(com.professor.zerion.android.navdrawer
									.PluginViewModel.class)
							.refreshTorState();
					new MaterialAlertDialogBuilder(requireContext())
							.setTitle(R.string.pref_rotate_onion_success_title)
							.setMessage(getString(
									R.string.pref_rotate_onion_success_message,
									displayOnion + ".onion"))
							.setPositiveButton(android.R.string.ok, null)
							.show();
				} else {
					Toast.makeText(appContext,
							ok ? R.string.pref_rotate_onion_started
									: R.string.pref_rotate_onion_failed,
							Toast.LENGTH_LONG).show();
				}
			});
		});
	}

	private void updateWipePasswordSummary() {
		WipePasswordManager mgr = getWipePasswordManager();
		if (mgr != null && mgr.isWipePasswordEnabled()) {
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

	private void updateRegistrationLockSummary() {
		if (registrationLockSummary == null) return;
		if (!RegistrationLockManager.isEnabled(requireContext())) {
			registrationLockSummary.setText(R.string.reg_lock_summary_off);
		} else {
			int type = RegistrationLockManager.getType(requireContext());
			registrationLockSummary.setText(
					type == RegistrationLockManager.TYPE_PIN
							? R.string.reg_lock_summary_pin
							: R.string.reg_lock_summary_password);
		}
	}

	private void showRegistrationLockDialog() {
		if (RegistrationLockManager.isEnabled(requireContext())) {
			String[] options = {
					getString(R.string.reg_lock_change),
					getString(R.string.reg_lock_disable)
			};
			new MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.reg_lock_title)
					.setItems(options, (dialog, which) -> {
						if (which == 0) {
							showRegLockTypeChooser();
						} else {
							new MaterialAlertDialogBuilder(requireContext())
									.setTitle(R.string.reg_lock_disable)
									.setMessage(R.string.reg_lock_disable_confirm)
									.setPositiveButton(R.string.remove, (d, w) -> {
										RegistrationLockManager.disable(requireContext());
										updateRegistrationLockSummary();
										showToast(R.string.reg_lock_disabled);
									})
									.setNegativeButton(R.string.cancel, null)
									.show();
						}
					})
					.setNegativeButton(R.string.cancel, null)
					.show();
		} else {
			showRegLockTypeChooser();
		}
	}

	private void showRegLockTypeChooser() {
		String[] types = {
				getString(R.string.reg_lock_set_pin),
				getString(R.string.reg_lock_set_password)
		};
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.reg_lock_choose_type)
				.setItems(types, (dialog, which) -> {
					if (which == 0) {
						showRegLockPinEntry(RegistrationLockManager.TYPE_PIN);
					} else {
						showRegLockPinEntry(RegistrationLockManager.TYPE_PASSWORD);
					}
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showRegLockPinEntry(int type) {
		boolean isPin = type == RegistrationLockManager.TYPE_PIN;
		int inputType = isPin
				? (android.text.InputType.TYPE_CLASS_NUMBER
						| android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
				: (android.text.InputType.TYPE_CLASS_TEXT
						| android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
		int hintRes = isPin ? R.string.reg_lock_enter_pin
				: R.string.reg_lock_enter_password;
		int minLength = 6;

		android.widget.EditText input1 = new android.widget.EditText(requireContext());
		input1.setHint(hintRes);
		input1.setInputType(inputType);
		input1.setTextColor(0xFFFFFFFF);
		input1.setHintTextColor(0x80FFFFFF);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(isPin ? R.string.reg_lock_set_pin
						: R.string.reg_lock_set_password)
				.setView(input1)
				.setPositiveButton(R.string.continue_button, (d, w) -> {
					String val = input1.getText().toString();
					if (val.length() < minLength) {
						showToast(isPin ? R.string.reg_lock_pin_too_short
								: R.string.reg_lock_password_too_short);
						return;
					}
					showRegLockConfirmEntry(type, val.toCharArray());
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showRegLockConfirmEntry(int type, char[] firstEntry) {
		boolean isPin = type == RegistrationLockManager.TYPE_PIN;
		int inputType = isPin
				? (android.text.InputType.TYPE_CLASS_NUMBER
						| android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
				: (android.text.InputType.TYPE_CLASS_TEXT
						| android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

		android.widget.EditText input2 = new android.widget.EditText(requireContext());
		input2.setHint(R.string.reg_lock_confirm);
		input2.setInputType(inputType);
		input2.setTextColor(0xFFFFFFFF);
		input2.setHintTextColor(0x80FFFFFF);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.reg_lock_confirm)
				.setView(input2)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					char[] confirm = input2.getText().toString().toCharArray();
					try {
						if (!java.util.Arrays.equals(firstEntry, confirm)) {
							showToast(R.string.reg_lock_mismatch);
							return;
						}
						if (RegistrationLockManager.setRegistrationLock(
								requireContext(), firstEntry, type)) {
							updateRegistrationLockSummary();
							showToast(R.string.reg_lock_enabled);
						}
					} finally {
						java.util.Arrays.fill(firstEntry, '\0');
						java.util.Arrays.fill(confirm, '\0');
					}
				})
				.setNegativeButton(R.string.cancel, (d, w) -> {
					java.util.Arrays.fill(firstEntry, '\0');
				})
				.show();
	}

}
