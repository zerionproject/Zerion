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
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Arrays;
import java.util.List;
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

	private final Executor io = java.util.concurrent.Executors
			.newSingleThreadExecutor();

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
		refreshProfileCount();
		backfillActiveDisplayNameIfNeeded();
		renderProfileRows();
	}

	private void refreshProfileCount() {
		if (profileCountSummary == null) return;
		int n = accountManager.profileCount();
		String summary = n == 1
				? getString(R.string.profiles_count_one)
				: getString(R.string.profiles_count_other, n);
		profileCountSummary.setText(summary);
	}

	private void backfillActiveDisplayNameIfNeeded() {
		String activeId = accountManager.getActiveProfileId();
		if (accountManager.readDisplayName(activeId) != null) return;
		io.execute(() -> {
			String name = null;
			try {
				LocalAuthor la = identityManager.getLocalAuthor();
				name = la.getName();
			} catch (Exception ignored) {
			}
			final String finalName = name;
			if (finalName == null || finalName.isEmpty()) return;
			accountManager.ensureActiveDisplayName(finalName);
			android.app.Activity a = getActivity();
			if (a != null) a.runOnUiThread(this::renderProfileRows);
		});
	}

	private void renderProfileRows() {
		if (profilesListGroup == null) return;
		profilesListGroup.removeAllViews();
		String activeId = accountManager.getActiveProfileId();
		List<String> ids = accountManager.listProfileIds();
		for (String id : ids) {
			String name = accountManager.readDisplayName(id);
			boolean isActive = id.equals(activeId);
			View row = buildProfileRow(name, isActive, id);
			profilesListGroup.addView(row);
		}
	}

	private View buildProfileRow(@Nullable String name, boolean isActive,
			String id) {
		View row = getLayoutInflater().inflate(
				R.layout.row_profile_entry, profilesListGroup, false);
		TextView titleView = row.findViewById(R.id.profile_row_title);
		TextView summaryView = row.findViewById(R.id.profile_row_summary);
		android.widget.ImageView chevron =
				row.findViewById(R.id.profile_row_chevron);
		String display = (name == null || name.isEmpty())
				? getString(R.string.profiles_row_unknown_name) : name;
		titleView.setText(display);
		if (isActive) {
			summaryView.setText(R.string.profiles_row_active);
			chevron.setVisibility(View.GONE);
			row.setClickable(false);
			row.setFocusable(false);
		} else {
			summaryView.setText(R.string.profiles_row_tap_to_switch);
			row.setOnClickListener(v -> showSwitchToProfileDialog(display));
		}
		return row;
	}

	private void showSwitchToProfileDialog(String displayName) {
		String msg = getString(R.string.profiles_switch_to_message, displayName);
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(getString(R.string.profiles_switch_to_title,
						displayName))
				.setMessage(msg)
				.setPositiveButton(R.string.profiles_switch_action,
						(d, w) -> signOutAndExit())
				.setNegativeButton(R.string.cancel, null)
				.show();
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
			final char[] pwToUse = pw;
			io.execute(() -> {
				String newId = accountManager.scheduleProfileCreation(name,
						pwToUse);
				Arrays.fill(pwToUse, '\0');
				requireActivity().runOnUiThread(() -> {
					if (newId != null) {
						toast(R.string.profiles_created_success);
						refreshProfileCount();
					} else {
						String reason =
								accountManager.getLastProfileCreationError();
						if (com.professor.zerion.BuildConfig.DEBUG
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
		boolean isLast = accountManager.profileCount() <= 1;
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
		accountManager.deleteActiveProfile();
		signOutAndExit();
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
		Context ctx = requireContext().getApplicationContext();
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
