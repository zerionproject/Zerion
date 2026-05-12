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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;

import org.briarproject.bramble.account.AndroidAccountManager;
import org.briarproject.bramble.api.account.AccountManager;
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
	AccountManager accountManager;

	private final Executor io = java.util.concurrent.Executors
			.newSingleThreadExecutor();

	@Nullable
	private TextView profileCountSummary;

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
		view.findViewById(R.id.add_profile_card)
				.setOnClickListener(v -> showAddProfileDialog());
		view.findViewById(R.id.switch_profile_card)
				.setOnClickListener(v -> showSwitchProfileDialog());
		view.findViewById(R.id.delete_profile_card)
				.setOnClickListener(v -> showDeleteProfileDialog());
		refreshProfileCount();
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.profiles_settings_title);
		refreshProfileCount();
	}

	private void refreshProfileCount() {
		AndroidAccountManager am = androidAccountManager();
		if (am == null || profileCountSummary == null) return;
		int n = am.profileCount();
		String summary = n == 1
				? getString(R.string.profiles_count_one)
				: getString(R.string.profiles_count_other, n);
		profileCountSummary.setText(summary);
	}

	@Nullable
	private AndroidAccountManager androidAccountManager() {
		return accountManager instanceof AndroidAccountManager
				? (AndroidAccountManager) accountManager : null;
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

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.profiles_add_dialog_title)
				.setMessage(R.string.profiles_add_dialog_message)
				.setView(dialogView)
				.setPositiveButton(R.string.profiles_add_title,
						(dialog, which) -> handleAddProfileSubmit(
								nameInput, pwInput, pwConfirmInput))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void handleAddProfileSubmit(@Nullable EditText nameInput,
			@Nullable EditText pwInput, @Nullable EditText pwConfirmInput) {
		String name = nameInput == null
				? "" : nameInput.getText().toString().trim();
		CharSequence pwSeq = pwInput == null ? "" : pwInput.getText();
		CharSequence pwConfirmSeq =
				pwConfirmInput == null ? "" : pwConfirmInput.getText();
		if (name.isEmpty()) {
			toast(R.string.profiles_name_too_short);
			return;
		}
		char[] pw = charsOf(pwSeq);
		char[] pwConfirm = charsOf(pwConfirmSeq);
		try {
			if (pw.length < 8) {
				toast(R.string.profiles_password_too_short);
				return;
			}
			if (!Arrays.equals(pw, pwConfirm)) {
				toast(R.string.profiles_password_mismatch);
				return;
			}
			AndroidAccountManager am = androidAccountManager();
			if (am == null) return;
			final char[] pwToUse = pw;
			io.execute(() -> {
				String newId = am.scheduleProfileCreation(name, pwToUse);
				Arrays.fill(pwToUse, '\0');
				requireActivity().runOnUiThread(() -> {
					if (newId != null) {
						toast(R.string.profiles_created_success);
						refreshProfileCount();
					} else {
						toast(R.string.profiles_create_failed);
					}
				});
			});
			pw = null;
		} finally {
			if (pw != null) Arrays.fill(pw, '\0');
			Arrays.fill(pwConfirm, '\0');
		}
	}

	private void showSwitchProfileDialog() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.profiles_switch_dialog_title)
				.setMessage(R.string.profiles_switch_dialog_message)
				.setPositiveButton(R.string.profiles_switch_action,
						(d, w) -> signOutAndExit())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showDeleteProfileDialog() {
		AndroidAccountManager am = androidAccountManager();
		boolean isLast = am != null && am.profileCount() <= 1;
		String msg = getString(R.string.profiles_delete_dialog_message);
		if (isLast) {
			msg += "\n\n" + getString(R.string.profiles_delete_last_warning);
		}
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.profiles_delete_dialog_title)
				.setMessage(msg)
				.setIcon(R.drawable.ic_warning)
				.setPositiveButton(R.string.profiles_delete_action,
						(d, w) -> doDeleteActiveProfile())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void doDeleteActiveProfile() {
		AndroidAccountManager am = androidAccountManager();
		if (am == null) return;
		am.deleteActiveProfile();
		signOutAndExit();
	}

	private void signOutAndExit() {
		if (getActivity() instanceof SettingsActivity) {
			((SettingsActivity) getActivity()).requestProfileSignOut();
		} else if (getActivity() != null) {
			getActivity().finishAffinity();
			System.exit(0);
		}
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
