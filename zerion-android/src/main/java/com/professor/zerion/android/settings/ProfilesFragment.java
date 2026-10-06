package com.professor.zerion.android.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;

import org.zerionproject.core.account.AndroidAccountManager;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.ReservedNames;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Arrays;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import static com.professor.zerion.android.AppModule.getAndroidComponent;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ProfilesFragment extends Fragment {

	@Inject
	AndroidAccountManager accountManager;

	@Inject
	IdentityManager identityManager;

	@Inject
	com.professor.zerion.android.profile.ProfileStorage profileStorage;

	@Inject
	com.professor.zerion.android.vault.VaultManager vaultManager;

	@Inject
	org.zerionproject.core.api.crypto.PasswordStrengthEstimator
			strengthEstimator;

	private final java.util.concurrent.ExecutorService io =
			java.util.concurrent.Executors.newSingleThreadExecutor();

	@Override
	public void onDestroy() {
		super.onDestroy();
		io.shutdown();
	}

	@Nullable
	private TextView profileCountSummary;
	@Nullable
	private android.widget.LinearLayout profilesListGroup;

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_settings_profiles, container,
				false);
	}

	@Override
	public void onViewCreated(@NonNull View view,
			@Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		profileCountSummary = view.findViewById(R.id.profile_count_summary);
		profilesListGroup = view.findViewById(R.id.profiles_list_group);
		view.findViewById(R.id.add_profile_card)
				.setOnClickListener(v -> showAddProfileDialog());
		view.findViewById(R.id.switch_profile_card)
				.setOnClickListener(v -> showSwitchProfileDialog());
		view.findViewById(R.id.delete_profile_card)
				.setOnClickListener(v -> showDeleteProfileDialog());
		refreshProfileList();
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.profiles_settings_title);
		refreshProfileList();
	}

	private void refreshProfileList() {
		showHiddenProfilesNote();
		backfillActiveDisplayNameIfNeeded();
		renderProfileRows();
	}

	private void showHiddenProfilesNote() {
		if (profileCountSummary == null) return;
		profileCountSummary.setText(R.string.profiles_hidden_note);
	}

	private void backfillActiveDisplayNameIfNeeded() {
		if (accountManager.readActiveDisplayName() != null) return;
		io.execute(() -> {
			String name = null;
			try {
				LocalAuthor la = identityManager.getLocalAuthor();
				name = la.getName();
			} catch (Exception ignored) {
			}
			final String finalName = name;
			if (finalName == null || finalName.isEmpty()
					|| !isReadableText(finalName)) {
				return;
			}
			accountManager.ensureActiveDisplayName(finalName);
			android.app.Activity a = getActivity();
			if (a != null) a.runOnUiThread(this::renderProfileRows);
		});
	}

	private void renderProfileRows() {
		if (profilesListGroup == null) return;
		profilesListGroup.removeAllViews();
		profilesListGroup.addView(
				buildProfileRow(accountManager.readActiveDisplayName()));
	}

	private static boolean isReadableText(@Nullable String s) {
		if (s == null || s.isEmpty()) return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == 0xFFFD) return false;
			if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') return false;
		}
		return true;
	}

	private View buildProfileRow(@Nullable String name) {
		View row = getLayoutInflater().inflate(
				R.layout.row_profile_entry, profilesListGroup, false);
		TextView titleView = row.findViewById(R.id.profile_row_title);
		TextView summaryView = row.findViewById(R.id.profile_row_summary);
		android.widget.ImageView chevron =
				row.findViewById(R.id.profile_row_chevron);
		String display = (name == null || name.isEmpty()
				|| !isReadableText(name))
				? getString(R.string.profiles_row_unknown_name) : name;
		titleView.setText(display);
		summaryView.setText(R.string.profiles_row_active);
		chevron.setVisibility(View.GONE);
		row.setClickable(false);
		row.setFocusable(false);
		return row;
	}

	private void showAddProfileDialog() {
		View dialogView = getLayoutInflater().inflate(
				R.layout.dialog_add_profile, null);
		TextInputLayout nameLayout =
				dialogView.findViewById(R.id.profile_name_layout);
		TextInputEditText nameInput =
				dialogView.findViewById(R.id.profile_name_input);
		TextInputLayout pwLayout =
				dialogView.findViewById(R.id.profile_password_layout);
		TextInputEditText pwInput =
				dialogView.findViewById(R.id.profile_password_input);
		TextInputLayout pwConfirmLayout =
				dialogView.findViewById(R.id.profile_password_confirm_layout);
		TextInputEditText pwConfirmInput =
				dialogView.findViewById(R.id.profile_password_confirm_input);
		if (nameLayout != null) nameLayout.setHint(
				getString(R.string.profiles_add_name_hint));
		if (pwLayout != null) pwLayout.setHint(
				getString(R.string.profiles_add_password_hint));
		if (pwConfirmLayout != null) pwConfirmLayout.setHint(
				getString(R.string.profiles_add_password_confirm_hint));

		androidx.appcompat.app.AlertDialog dlg =
				new SecureAlertDialogBuilder(requireContext())
						.setTitle(R.string.profiles_add_dialog_title)
						.setMessage(R.string.profiles_add_dialog_message)
						.setView(dialogView)
						.setPositiveButton(R.string.profiles_add_title, null)
						.setNegativeButton(R.string.cancel, null)
						.create();
		dlg.setOnShowListener(dd -> dlg.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(x -> {
					if (handleAddProfileSubmit(nameInput, pwInput,
							pwConfirmInput)) {
						dlg.dismiss();
					}
				}));
		dlg.show();
	}

	private boolean handleAddProfileSubmit(@Nullable EditText nameInput,
			@Nullable EditText pwInput, @Nullable EditText pwConfirmInput) {
		String name = nameInput == null
				? "" : nameInput.getText().toString().trim();
		CharSequence pwSeq = pwInput == null ? "" : pwInput.getText();
		CharSequence pwConfirmSeq =
				pwConfirmInput == null ? "" : pwConfirmInput.getText();
		if (name.isEmpty()) {
			toast(R.string.profiles_name_too_short);
			return false;
		}
		if (ReservedNames.isReserved(name)) {
			toast(R.string.name_reserved);
			return false;
		}
		char[] pw = charsOf(pwSeq);
		char[] pwConfirm = charsOf(pwConfirmSeq);
		try {
			if (!com.professor.zerion.android.login.AccountPasswordPolicy
					.acceptable(strengthEstimator, pw)) {
				toast(R.string.password_too_weak);
				return false;
			}
			if (!com.professor.zerion.android.login.AccountPasswordPolicy
					.sameNormalForm(pw, pwConfirm)) {
				toast(R.string.profiles_password_mismatch);
				return false;
			}
			final char[] pwToUse = pw;
			pw = null;
			AccountPasswordGate.prompt(requireContext(), accountManager, io,
					new android.os.Handler(android.os.Looper.getMainLooper()),
					R.string.settings_password_required_title,
					R.string.profiles_add_password_gate_message,
					() -> createProfile(name, pwToUse),
					() -> Arrays.fill(pwToUse, '\0'));
		} finally {
			if (pw != null) Arrays.fill(pw, '\0');
			Arrays.fill(pwConfirm, '\0');
		}
		return true;
	}

	private void createProfile(String name, char[] password) {
		Context app = requireContext().getApplicationContext();
		android.os.Handler main =
				new android.os.Handler(android.os.Looper.getMainLooper());
		io.execute(() -> {
			String newId = accountManager.scheduleProfileCreation(name,
					password);
			Arrays.fill(password, '\0');
			AndroidAccountManager.ProfileCreationRefusal refusal =
					accountManager.getLastProfileCreationRefusal();
			main.post(() -> {
				if (newId != null) {
					Toast.makeText(app, R.string.profiles_created_success,
							Toast.LENGTH_SHORT).show();
					if (isAdded() && getActivity() instanceof SettingsActivity) {
						signOutAndExit();
					} else {
						ProfileSignOut.signOutAndRestart(app);
					}
					return;
				}
				if (!isAdded()) return;
				if (refusal == AndroidAccountManager
						.ProfileCreationRefusal.PASSWORD_UNAVAILABLE) {
					toast(R.string.profiles_password_unavailable);
				} else if (refusal == AndroidAccountManager
						.ProfileCreationRefusal.LOCKED_OUT) {
					toast(R.string.profiles_create_locked_out);
				} else {
					String reason =
							accountManager.getLastProfileCreationError();
					if (com.professor.zerion.android.TestingConstants.IS_DEBUG_BUILD
							&& reason != null && !reason.isEmpty()) {
						Toast.makeText(requireContext(),
								getString(R.string.profiles_create_failed)
										+ "\n" + reason,
								Toast.LENGTH_LONG).show();
					} else {
						toast(R.string.profiles_create_failed);
					}
				}
			});
		});
	}

	private void showSwitchProfileDialog() {
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.profiles_switch_dialog_title)
				.setMessage(R.string.profiles_switch_dialog_message)
				.setPositiveButton(R.string.profiles_switch_action,
						(d, w) -> signOutAndExit())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showDeleteProfileDialog() {
		String target = accountManager.getActiveProfileId();
		if (target == null) return;
		String msg = getString(R.string.profiles_delete_dialog_message)
				+ "\n\n" + getString(R.string.profiles_delete_then_sign_in);
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.profiles_delete_dialog_title)
				.setMessage(msg)
				.setIcon(R.drawable.ic_warning)
				.setPositiveButton(R.string.profiles_delete_action,
						(d, w) -> AccountPasswordGate.prompt(requireContext(),
								accountManager, io,
								new android.os.Handler(
										android.os.Looper.getMainLooper()),
								R.string.settings_password_required_title,
								R.string.settings_password_required_message,
								() -> {
									if (isAdded()) doDeleteProfile(target);
								}, () -> {
								}))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void doDeleteProfile(String target) {
		io.execute(() -> {
			if (!profileStorage.vaultIsShared()) {
				try {
					vaultManager.wipeVault();
				} catch (Exception ignored) {
				}
			}
			profileStorage.forgetClaimOf(target);
			boolean deleted = accountManager.deleteActiveProfile(target);
			android.app.Activity activity = getActivity();
			if (activity == null) return;
			activity.runOnUiThread(() -> {
				if (!isAdded()) return;
				if (!deleted) {
					toast(R.string.delete_account_failed);
					return;
				}
				signOutAndExit();
			});
		});
	}

	private void signOutAndExit() {
		toast(R.string.profiles_switch_progress);
		scheduleRestart();
		if (getActivity() instanceof SettingsActivity) {
			((SettingsActivity) getActivity()).requestProfileSignOut();
		} else if (getActivity() != null) {
			getActivity().finishAffinity();
			System.exit(0);
		}
	}

	private void scheduleRestart() {
		scheduleRestart(requireContext().getApplicationContext());
	}

	static void scheduleRestart(Context ctx) {
		android.content.Intent restartIntent =
				ctx.getPackageManager().getLaunchIntentForPackage(
						ctx.getPackageName());
		if (restartIntent == null) return;
		restartIntent.addFlags(
				android.content.Intent.FLAG_ACTIVITY_NEW_TASK
				| android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
		android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
				ctx, 0, restartIntent,
				android.app.PendingIntent.FLAG_CANCEL_CURRENT
				| android.app.PendingIntent.FLAG_IMMUTABLE);
		android.app.AlarmManager am = (android.app.AlarmManager)
				ctx.getSystemService(Context.ALARM_SERVICE);
		if (am == null) return;
		am.set(android.app.AlarmManager.RTC,
				System.currentTimeMillis() + 250, pi);
	}

	private void toast(int resId) {
		Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show();
	}

	private static char[] charsOf(CharSequence s) {
		if (s == null || s.length() == 0) return new char[0];
		char[] out = new char[s.length()];
		for (int i = 0; i < s.length(); i++) out[i] = s.charAt(i);
		return out;
	}
}
