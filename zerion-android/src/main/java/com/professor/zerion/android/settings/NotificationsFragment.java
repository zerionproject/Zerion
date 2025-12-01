package com.professor.zerion.android.settings;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import android.content.SharedPreferences;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import static android.app.Activity.RESULT_OK;
import static android.media.RingtoneManager.ACTION_RINGTONE_PICKER;
import static android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI;
import static android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI;
import static android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI;
import static android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT;
import static android.media.RingtoneManager.EXTRA_RINGTONE_TITLE;
import static android.media.RingtoneManager.EXTRA_RINGTONE_TYPE;
import static android.media.RingtoneManager.TYPE_NOTIFICATION;
import static android.os.Build.VERSION.SDK_INT;
import static android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS;
import static android.provider.Settings.EXTRA_APP_PACKAGE;
import static android.provider.Settings.EXTRA_CHANNEL_ID;
import static android.provider.Settings.System.DEFAULT_NOTIFICATION_URI;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.Objects.requireNonNull;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;
import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static com.professor.zerion.android.activity.RequestCodes.REQUEST_RINGTONE;
import static com.professor.zerion.android.api.AndroidNotificationManager.CONTACT_CHANNEL_ID;
import static com.professor.zerion.android.api.AndroidNotificationManager.GROUP_CHANNEL_ID;
import static com.professor.zerion.android.api.AndroidNotificationManager.PREF_NOTIFY_GROUP;
import static com.professor.zerion.android.api.AndroidNotificationManager.PREF_NOTIFY_PRIVATE;
import static com.professor.zerion.android.api.AndroidNotificationManager.PREF_NOTIFY_VOICE_CALLS;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class NotificationsFragment extends Fragment {

	public static final String PREF_NOTIFY_SIGN_IN = "pref_key_notify_sign_in";
	private static final int NOTIFICATION_CHANNEL_API = 26;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	@AppModule.UiPrefs
	SharedPreferences uiPrefs;

	private SettingsViewModel viewModel;
	private NotificationsManager nm;


	private SwitchMaterial notifySignInSwitch;
	private SwitchMaterial notifyPrivateMessagesSwitch;
	private SwitchMaterial notifyGroupMessagesSwitch;
	private SwitchMaterial notifyVoiceCallsSwitch;
	private SwitchMaterial notifyVibrationSwitch;
	private MaterialCardView notifySoundCard;
	private TextView notifySoundValue;

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(SettingsViewModel.class);
		nm = viewModel.notificationsManager;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_settings_notifications, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);


		notifySignInSwitch = view.findViewById(R.id.notify_sign_in_switch);
		notifyPrivateMessagesSwitch = view.findViewById(R.id.notify_private_messages_switch);
		notifyGroupMessagesSwitch = view.findViewById(R.id.notify_group_messages_switch);
		notifyVoiceCallsSwitch = view.findViewById(R.id.notify_voice_calls_switch);
		notifyVibrationSwitch = view.findViewById(R.id.notify_vibration_switch);
		notifySoundCard = view.findViewById(R.id.notify_sound_card);
		notifySoundValue = view.findViewById(R.id.notify_sound_value);


		if (SDK_INT < NOTIFICATION_CHANNEL_API) {
			setupPreAndroidONotifications();
		} else {
			setupAndroidOAndLaterNotifications();
		}


		setupSignInNotifications();
	}

	private void setupSignInNotifications() {
		boolean enabled = uiPrefs.getBoolean(PREF_NOTIFY_SIGN_IN, true);
		notifySignInSwitch.setChecked(enabled);
		notifySignInSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (buttonView.isPressed()) {
				uiPrefs.edit()
						.putBoolean(PREF_NOTIFY_SIGN_IN, isChecked)
						.apply();
			}
		});
	}

	private void setupPreAndroidONotifications() {

		nm.getNotifyPrivateMessages().observe(getViewLifecycleOwner(), enabled -> {
			notifyPrivateMessagesSwitch.setOnCheckedChangeListener(null);
			notifyPrivateMessagesSwitch.setChecked(enabled);
			notifyPrivateMessagesSwitch.setEnabled(true);
			notifyPrivateMessagesSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
				if (buttonView.isPressed()) {
					viewModel.settingsStore.putBoolean(PREF_NOTIFY_PRIVATE, isChecked);
				}
			});
		});

		nm.getNotifyGroupMessages().observe(getViewLifecycleOwner(), enabled -> {
			notifyGroupMessagesSwitch.setOnCheckedChangeListener(null);
			notifyGroupMessagesSwitch.setChecked(enabled);
			notifyGroupMessagesSwitch.setEnabled(true);
			notifyGroupMessagesSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
				if (buttonView.isPressed()) {
					viewModel.settingsStore.putBoolean(PREF_NOTIFY_GROUP, isChecked);
				}
			});
		});

		boolean voiceCallsEnabled = uiPrefs.getBoolean(PREF_NOTIFY_VOICE_CALLS, true);
		notifyVoiceCallsSwitch.setChecked(voiceCallsEnabled);
		notifyVoiceCallsSwitch.setEnabled(true);
		notifyVoiceCallsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (buttonView.isPressed()) {
				viewModel.settingsStore.putBoolean(PREF_NOTIFY_VOICE_CALLS, isChecked);
			}
		});

		nm.getNotifyVibration().observe(getViewLifecycleOwner(), enabled -> {
			notifyVibrationSwitch.setOnCheckedChangeListener(null);
			notifyVibrationSwitch.setChecked(enabled);
			notifyVibrationSwitch.setEnabled(true);
			notifyVibrationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
				if (buttonView.isPressed()) {
					viewModel.settingsStore.putBoolean("notifyVibration", isChecked);
				}
			});
		});


		nm.getNotifySound().observe(getViewLifecycleOwner(), enabled -> {
			String text;
			if (enabled) {
				String ringtoneName = nm.getRingtoneName();
				if (isNullOrEmpty(ringtoneName)) {
					text = getString(R.string.notify_sound_setting_default);
				} else {
					text = ringtoneName;
				}
			} else {
				text = getString(R.string.notify_sound_setting_disabled);
			}
			notifySoundValue.setText(text);
			notifySoundCard.setEnabled(true);
		});

		notifySoundCard.setOnClickListener(v -> onNotificationSoundClicked());
	}

	@TargetApi(NOTIFICATION_CHANNEL_API)
	private void setupAndroidOAndLaterNotifications() {

		notifyPrivateMessagesSwitch.setEnabled(false);
		notifyPrivateMessagesSwitch.setChecked(true);

		notifyGroupMessagesSwitch.setEnabled(false);
		notifyGroupMessagesSwitch.setChecked(true);

		notifyVoiceCallsSwitch.setEnabled(false);
		notifyVoiceCallsSwitch.setChecked(true);


		notifyVibrationSwitch.setVisibility(View.GONE);
		notifySoundCard.setVisibility(View.GONE);


	}

	@TargetApi(NOTIFICATION_CHANNEL_API)
	private void openChannelSettings(String channelId) {
		String packageName = requireContext().getPackageName();
		Intent intent = new Intent(ACTION_CHANNEL_NOTIFICATION_SETTINGS)
				.putExtra(EXTRA_APP_PACKAGE, packageName)
				.putExtra(EXTRA_CHANNEL_ID, channelId);
		Context ctx = requireContext();
		if (intent.resolveActivity(ctx.getPackageManager()) != null) {
			startActivity(intent);
		} else {
			Toast.makeText(ctx, R.string.error_start_activity, LENGTH_SHORT).show();
		}
	}

	private void onNotificationSoundClicked() {
		String title = getString(R.string.choose_ringtone_title);
		Intent i = new Intent(ACTION_RINGTONE_PICKER);
		i.putExtra(EXTRA_RINGTONE_TYPE, TYPE_NOTIFICATION);
		i.putExtra(EXTRA_RINGTONE_TITLE, title);
		i.putExtra(EXTRA_RINGTONE_DEFAULT_URI, DEFAULT_NOTIFICATION_URI);
		i.putExtra(EXTRA_RINGTONE_SHOW_SILENT, true);

		if (requireNonNull(nm.getNotifySound().getValue())) {
			Uri uri;
			String ringtoneUri = nm.getRingtoneUri();
			if (isNullOrEmpty(ringtoneUri)) {
				uri = DEFAULT_NOTIFICATION_URI;
			} else {
				uri = Uri.parse(ringtoneUri);
			}
			i.putExtra(EXTRA_RINGTONE_EXISTING_URI, uri);
		}

		if (i.resolveActivity(requireActivity().getPackageManager()) != null) {
			startActivityForResult(i, REQUEST_RINGTONE);
		} else {
			Toast.makeText(getContext(), R.string.cannot_load_ringtone, LENGTH_SHORT).show();
		}
	}

	@Override
	public void onActivityResult(int request, int result, @Nullable Intent data) {
		super.onActivityResult(request, result, data);
		if (request == REQUEST_RINGTONE && result == RESULT_OK && data != null) {
			Uri uri = data.getParcelableExtra(EXTRA_RINGTONE_PICKED_URI);
			nm.onRingtoneSet(uri);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.notification_settings_title);
	}

}
