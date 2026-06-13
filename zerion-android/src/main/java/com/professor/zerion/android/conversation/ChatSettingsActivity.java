package com.professor.zerion.android.conversation;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.File;
import java.util.Map;

import javax.inject.Inject;

import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;
import static com.professor.zerion.android.view.AuthorView.setAvatar;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChatSettingsActivity extends ZerionActivity {

	private static final String PREFS_NAME = "chat_settings";
	private static final String PREF_MUTE_PREFIX = "mute_";
	private static final String PREF_VIBRATION_PREFIX = "vibration_";
	private static final String PREF_TIMER_PREFIX = "timer_";

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	@Inject
	ConnectionRegistry connectionRegistry;

	private ConversationViewModel viewModel;
	private ContactId contactId;
	private SharedPreferences prefs;

	private ImageView contactAvatar;
	private TextView contactName;
	private TextView contactStatus;
	private LinearLayout trustIndicatorContainer;
	private ImageView trustIndicator;
	private TextView trustIndicatorText;
	private SwitchMaterial muteNotificationsSwitch;
	private SwitchMaterial vibrationSwitch;
	private TextView securityLevelTitle;
	private TextView securityLevelDescription;
	private LinearLayout disappearingMessagesOption;
	private TextView disappearingMessagesValue;
	private LinearLayout identityCard;
	private TextView safetyNumberValue;
	private TextView myFingerprintValue;
	private TextView theirFingerprintValue;
	private com.google.android.material.button.MaterialButton copySafetyNumberButton;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(ConversationViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		Intent i = getIntent();
		int id = i.getIntExtra(CONTACT_ID, -1);
		if (id == -1) throw new IllegalStateException("Contact ID required");
		contactId = new ContactId(id);

		viewModel.setContactId(contactId);
		viewModel.checkConnectionStatus(connectionRegistry);
		prefs = getSecurePrefs(this);
		migrateFromPlaintextPrefs();

		setContentView(R.layout.activity_chat_settings);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}

		contactAvatar = findViewById(R.id.contact_avatar);
		contactName = findViewById(R.id.contact_name);
		contactStatus = findViewById(R.id.contact_status);
		trustIndicatorContainer = findViewById(R.id.trust_indicator_container);
		trustIndicator = findViewById(R.id.trust_indicator);
		trustIndicatorText = findViewById(R.id.trust_indicator_text);
		muteNotificationsSwitch = findViewById(R.id.mute_notifications_switch);
		vibrationSwitch = findViewById(R.id.vibration_switch);
		securityLevelTitle = findViewById(R.id.security_level_title);
		securityLevelDescription = findViewById(R.id.security_level_description);
		disappearingMessagesOption = findViewById(R.id.disappearing_messages_option);
		disappearingMessagesValue = findViewById(R.id.disappearing_messages_value);
		disappearingMessagesOption.setOnClickListener(v -> showDisappearingMessagesDialog());

		identityCard = findViewById(R.id.identity_card);
		safetyNumberValue = findViewById(R.id.safety_number_value);
		myFingerprintValue = findViewById(R.id.my_fingerprint_value);
		theirFingerprintValue = findViewById(R.id.their_fingerprint_value);
		copySafetyNumberButton = findViewById(R.id.copy_safety_number_button);

		viewModel.getIdentityKeys().observeEvent(this, keys -> {
			if (keys == null) return;
			String safety = com.professor.zerion.android.contact.identity
					.ContactSafetyNumber.forKeys(
							keys.localSigningPub, keys.remoteSigningPub);
			String myFp = com.professor.zerion.android.contact.identity
					.IdentityFingerprint.forSigningPub(keys.localSigningPub);
			String theirFp = com.professor.zerion.android.contact.identity
					.IdentityFingerprint.forSigningPub(keys.remoteSigningPub);
			safetyNumberValue.setText(formatSafetyNumberMultiline(safety));
			myFingerprintValue.setText(myFp);
			theirFingerprintValue.setText(theirFp);
			identityCard.setVisibility(View.VISIBLE);
			copySafetyNumberButton.setOnClickListener(v -> {
				com.professor.zerion.android.util.Haptics.tap(v);
				com.professor.zerion.android.util.SecureClipboard.copy(this,
						getString(R.string.identity_section_title), safety);
				android.widget.Toast.makeText(this,
						R.string.identity_copied,
						android.widget.Toast.LENGTH_SHORT).show();
			});
		});
		viewModel.loadIdentityKeys();
		viewModel.getAutoDeleteTimer().observe(this, timer -> {
			if (timer != null) {
				disappearingMessagesValue.setText(getTimerDisplayText(timer));
			}
		});

		viewModel.getContactItem().observe(this, contactItem -> {
			if (contactItem != null) {
				setAvatar(contactAvatar, contactItem);
				contactName.setText(contactItem.getContact().getAuthor().getName());

				contactAvatar.setOnClickListener(v -> showAvatarFullScreen(contactItem));
				boolean hybridSig = contactItem.getContact()
						.hasHybridSigCapability();
				if (contactItem.isPostQuantum() && hybridSig) {
					securityLevelTitle.setText(R.string.security_level_post_quantum);
					securityLevelDescription.setText(
							R.string.security_level_post_quantum_description);
				} else if (contactItem.isPostQuantum()) {
					securityLevelTitle.setText(
							R.string.security_level_post_quantum);
					securityLevelDescription.setText(
							R.string.security_level_legacy_auth_description);
				} else {
					securityLevelTitle.setText(R.string.security_level_classical);
					securityLevelDescription.setText(
							R.string.security_level_classical_description);
				}
			}
		});
		viewModel.isContactConnected().observe(this, connected -> {
			if (connected != null) {
				contactStatus.setText(connected ? R.string.online : R.string.offline);
			}
		});

		viewModel.getContactDisplayName().observe(this, name -> {
			if (name != null) {
				contactName.setText(name);
			}
		});

		viewModel.getContactItem().observe(this, contactItem -> {
			if (contactItem != null && contactItem.getAuthorInfo().getStatus() == AuthorInfo.Status.VERIFIED) {
				trustIndicatorContainer.setVisibility(View.VISIBLE);
				trustIndicator.setVisibility(View.VISIBLE);
				trustIndicator.setImageResource(R.drawable.trust_indicator_verified);
				trustIndicatorText.setVisibility(View.VISIBLE);
				trustIndicatorText.setText(R.string.verified_contact);
			} else {
				trustIndicatorContainer.setVisibility(View.GONE);
			}
		});

		loadSettings();

		muteNotificationsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			prefs.edit().putBoolean(PREF_MUTE_PREFIX + contactId.getInt(), isChecked).apply();
		});

		vibrationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			prefs.edit().putBoolean(PREF_VIBRATION_PREFIX + contactId.getInt(), isChecked).apply();
		});
	}

	private void loadSettings() {
		boolean isMuted = prefs.getBoolean(PREF_MUTE_PREFIX + contactId.getInt(), false);
		muteNotificationsSwitch.setChecked(isMuted);

		boolean vibrationEnabled = prefs.getBoolean(PREF_VIBRATION_PREFIX + contactId.getInt(), true);
		vibrationSwitch.setChecked(vibrationEnabled);

	}

	@Override
	public boolean onSupportNavigateUp() {
		onBackPressed();
		return true;
	}

	public static boolean isContactMuted(Context context, ContactId contactId) {
		SharedPreferences prefs = getSecurePrefs(context);
		return prefs.getBoolean(PREF_MUTE_PREFIX + contactId.getInt(), false);
	}

	public static boolean isVibrationEnabled(Context context, ContactId contactId) {
		SharedPreferences prefs = getSecurePrefs(context);
		return prefs.getBoolean(PREF_VIBRATION_PREFIX + contactId.getInt(), true);
	}

	public static long getDisappearingTimer(Context context, ContactId contactId) {
		SharedPreferences prefs = getSecurePrefs(context);
		return prefs.getLong(PREF_TIMER_PREFIX + contactId.getInt(), 0);
	}

	private static SharedPreferences getSecurePrefs(Context context) {
		return AppModule.getAndroidComponent(context).securePreferences();
	}

	private void migrateFromPlaintextPrefs() {
		File prefsDir = new File(getApplicationInfo().dataDir, "shared_prefs");
		File oldFile = new File(prefsDir, PREFS_NAME + ".xml");
		if (!oldFile.exists()) return;

		SharedPreferences oldPrefs =
				getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		Map<String, ?> allEntries = oldPrefs.getAll();
		if (!allEntries.isEmpty()) {
			SharedPreferences.Editor editor = prefs.edit();
			for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
				Object value = entry.getValue();
				if (value instanceof Boolean) {
					editor.putBoolean(entry.getKey(), (Boolean) value);
				} else if (value instanceof Long) {
					editor.putLong(entry.getKey(), (Long) value);
				} else if (value instanceof Integer) {
					editor.putInt(entry.getKey(), (Integer) value);
				} else if (value instanceof String) {
					editor.putString(entry.getKey(), (String) value);
				}
			}
			editor.commit();
		}
		oldPrefs.edit().clear().commit();
		try {
			if (oldFile.exists() && oldFile.canWrite()) {
				long len = oldFile.length();
				if (len > 0) {
					try (java.io.RandomAccessFile raf =
							new java.io.RandomAccessFile(oldFile, "rw")) {
						byte[] zeros = new byte[(int) Math.min(len, 8192)];
						long remaining = len;
						while (remaining > 0) {
							int w = (int) Math.min(remaining, zeros.length);
							raf.write(zeros, 0, w);
							remaining -= w;
						}
						raf.getFD().sync();
					}
				}
			}
		} catch (Exception e) {
		}
		oldFile.delete();
	}

	private void showAvatarFullScreen(com.professor.zerion.android.contact.ContactItem contactItem) {
		android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
		dialog.setContentView(R.layout.dialog_avatar_fullscreen);

		ImageView fullScreenAvatar = dialog.findViewById(R.id.fullscreen_avatar);
		ImageView closeButton = dialog.findViewById(R.id.close_button);

		setAvatar((com.google.android.material.imageview.ShapeableImageView) fullScreenAvatar, contactItem);

		closeButton.setOnClickListener(v -> dialog.dismiss());

		dialog.findViewById(R.id.dialog_background).setOnClickListener(v -> dialog.dismiss());

		dialog.show();
	}

	private void showDisappearingMessagesDialog() {
		View dialogView = getLayoutInflater().inflate(
				R.layout.dialog_disappearing_messages, null);
		RadioGroup radioGroup = dialogView.findViewById(
				R.id.disappearing_messages_radio_group);

		Long currentTimer = viewModel.getAutoDeleteTimer().getValue();
		if (currentTimer != null) {
			radioGroup.check(getRadioIdForTimer(currentTimer));
		}

		new MaterialAlertDialogBuilder(this)
				.setView(dialogView)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					viewModel.setAutoDeleteTimer(getTimerForRadioId(
							radioGroup.getCheckedRadioButtonId()));
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private String getTimerDisplayText(long timer) {
		if (timer <= 0) return getString(R.string.off);
		long seconds = timer / 1000;
		long minutes = seconds / 60;
		long hours = minutes / 60;
		long weeks = hours / 24 / 7;
		if (seconds <= 30) return "30 seconds";
		if (minutes <= 5) return "5 minutes";
		if (minutes <= 30) return "30 minutes";
		if (hours <= 1) return "1 hour";
		if (hours <= 8) return "8 hours";
		if (hours <= 12) return "12 hours";
		if (hours <= 24) return "24 hours";
		if (weeks <= 1) return "1 week";
		return "4 weeks";
	}

	private int getRadioIdForTimer(long timer) {
		if (timer <= 0) return R.id.timer_off;
		long seconds = timer / 1000;
		long minutes = seconds / 60;
		long hours = minutes / 60;
		long weeks = hours / 24 / 7;
		if (seconds <= 30) return R.id.timer_30_seconds;
		if (minutes <= 5) return R.id.timer_5_minutes;
		if (minutes <= 30) return R.id.timer_30_minutes;
		if (hours <= 1) return R.id.timer_1_hour;
		if (hours <= 8) return R.id.timer_8_hours;
		if (hours <= 12) return R.id.timer_12_hours;
		if (hours <= 24) return R.id.timer_24_hours;
		if (weeks <= 1) return R.id.timer_1_week;
		return R.id.timer_4_weeks;
	}

	private long getTimerForRadioId(int radioId) {
		if (radioId == R.id.timer_30_seconds) return 30 * 1000L;
		if (radioId == R.id.timer_5_minutes) return 5 * 60 * 1000L;
		if (radioId == R.id.timer_30_minutes) return 30 * 60 * 1000L;
		if (radioId == R.id.timer_1_hour) return 60 * 60 * 1000L;
		if (radioId == R.id.timer_8_hours) return 8 * 60 * 60 * 1000L;
		if (radioId == R.id.timer_12_hours) return 12 * 60 * 60 * 1000L;
		if (radioId == R.id.timer_24_hours) return 24 * 60 * 60 * 1000L;
		if (radioId == R.id.timer_1_week) return 7 * 24 * 60 * 60 * 1000L;
		if (radioId == R.id.timer_4_weeks) return 4 * 7 * 24 * 60 * 60 * 1000L;
		return -1L;
	}

	private static String formatSafetyNumberMultiline(String single) {
		if (single == null || single.isEmpty()) return "";
		String[] groups = single.split(" ");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < groups.length; i++) {
			if (i > 0 && i % 2 == 0) sb.append('\n');
			else if (i > 0) sb.append("   ");
			sb.append(groups[i]);
		}
		return sb.toString();
	}
}
