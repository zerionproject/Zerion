package com.professor.zerion.android.conversation;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import static com.professor.zerion.android.conversation.ConversationActivity.CONTACT_ID;
import static com.professor.zerion.android.view.AuthorView.setAvatar;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChatSettingsActivity extends ZerionActivity {

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
	private TextView safetyNumberLabel;
	private TextView myFingerprintValue;
	private TextView theirFingerprintValue;
	private com.google.android.material.button.MaterialButton copySafetyNumberButton;
	private com.google.android.material.button.MaterialButton markVerifiedButton;
	private LinearLayout keysOutOfSyncCard;
	private com.google.android.material.button.MaterialButton readdContactButton;

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
		prefs = getProfilePrefs(this);

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
		safetyNumberLabel = findViewById(R.id.safety_number_label);
		myFingerprintValue = findViewById(R.id.my_fingerprint_value);
		theirFingerprintValue = findViewById(R.id.their_fingerprint_value);
		copySafetyNumberButton = findViewById(R.id.copy_safety_number_button);
		markVerifiedButton = findViewById(R.id.mark_verified_button);
		markVerifiedButton.setOnClickListener(v -> confirmMarkVerified());
		keysOutOfSyncCard = findViewById(R.id.keys_out_of_sync_card);
		readdContactButton = findViewById(R.id.readd_contact_button);
		readdContactButton.setOnClickListener(v -> readdContact());
		viewModel.areKeysOutOfSync().observe(this, outOfSync ->
				keysOutOfSyncCard.setVisibility(Boolean.TRUE.equals(outOfSync)
						? View.VISIBLE : View.GONE));

		viewModel.getIdentityKeys().observeEvent(this, keys -> {
			if (keys == null) return;
			String safety = com.professor.zerion.android.contact.identity
					.ContactSafetyNumber.forKeys(keys.localSigningPub,
							keys.localMlDsaPub, keys.remoteSigningPub,
							keys.remoteMlDsaPub);
			int version = com.professor.zerion.android.contact.identity
					.ContactSafetyNumber.versionFor(keys.localMlDsaPub,
							keys.remoteMlDsaPub);
			String hybridFormat =
					getString(R.string.identity_fingerprint_hybrid_format);
			String classicalFormat =
					getString(R.string.identity_fingerprint_classical_format);
			String myFp = com.professor.zerion.android.contact.identity
					.IdentityDisplay.lines(keys.localSigningPub,
							keys.localMlDsaPub, hybridFormat, classicalFormat);
			String theirFp = com.professor.zerion.android.contact.identity
					.IdentityDisplay.lines(keys.remoteSigningPub,
							keys.remoteMlDsaPub, hybridFormat, classicalFormat);
			safetyNumberLabel.setText(getString(
					R.string.identity_safety_number_version_label, version));
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
				disappearingMessagesValue.setText(DisappearingTimers.label(this, timer));
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
			boolean verified = contactItem != null
					&& contactItem.getAuthorInfo().getStatus()
					== AuthorInfo.Status.VERIFIED;
			if (verified) {
				trustIndicatorContainer.setVisibility(View.VISIBLE);
				trustIndicator.setVisibility(View.VISIBLE);
				trustIndicator.setImageResource(R.drawable.trust_indicator_verified);
				trustIndicatorText.setVisibility(View.VISIBLE);
				trustIndicatorText.setText(R.string.verified_contact);
			} else {
				trustIndicatorContainer.setVisibility(View.GONE);
			}
			markVerifiedButton.setVisibility(contactItem != null && !verified
					? View.VISIBLE : View.GONE);
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
		return isContactMuted(getProfilePrefs(context), contactId);
	}

	static boolean isContactMuted(SharedPreferences prefs,
			ContactId contactId) {
		return prefs.getBoolean(PREF_MUTE_PREFIX + contactId.getInt(), false);
	}

	public static boolean isVibrationEnabled(Context context, ContactId contactId) {
		return isVibrationEnabled(getProfilePrefs(context), contactId);
	}

	static boolean isVibrationEnabled(SharedPreferences prefs,
			ContactId contactId) {
		return prefs.getBoolean(PREF_VIBRATION_PREFIX + contactId.getInt(), true);
	}

	public static long getDisappearingTimer(Context context, ContactId contactId) {
		return getDisappearingTimer(getProfilePrefs(context), contactId);
	}

	static long getDisappearingTimer(SharedPreferences prefs,
			ContactId contactId) {
		return prefs.getLong(PREF_TIMER_PREFIX + contactId.getInt(), 0);
	}

	public static void forgetContact(Context context, ContactId contactId) {
		forgetContact(getProfilePrefs(context), contactId);
	}

	static void forgetContact(SharedPreferences prefs, ContactId contactId) {
		int id = contactId.getInt();
		prefs.edit()
				.remove(PREF_MUTE_PREFIX + id)
				.remove(PREF_VIBRATION_PREFIX + id)
				.remove(PREF_TIMER_PREFIX + id)
				.apply();
	}

	private static SharedPreferences getProfilePrefs(Context context) {
		return AppModule.getAndroidComponent(context).profilePreferences();
	}

	private void showAvatarFullScreen(com.professor.zerion.android.contact.ContactItem contactItem) {
		android.app.Dialog dialog = com.professor.zerion.android.security
				.SecureDialogs.protectSecret(new android.app.Dialog(this,
						android.R.style.Theme_Black_NoTitleBar_Fullscreen));
		dialog.setContentView(R.layout.dialog_avatar_fullscreen);

		ImageView fullScreenAvatar = dialog.findViewById(R.id.fullscreen_avatar);
		ImageView closeButton = dialog.findViewById(R.id.close_button);

		setAvatar((com.google.android.material.imageview.ShapeableImageView) fullScreenAvatar, contactItem);

		closeButton.setOnClickListener(v -> dialog.dismiss());

		dialog.findViewById(R.id.dialog_background).setOnClickListener(v -> dialog.dismiss());

		dialog.show();
	}

	private void showDisappearingMessagesDialog() {
		DisappearingTimers.showChatTimerDialog(this,
				viewModel.getAutoDeleteTimer().getValue(),
				viewModel::setAutoDeleteTimer);
	}

	private void readdContact() {
		Intent i = new Intent(this, com.professor.zerion.android.contact.add
				.remote.AddContactActivity.class);
		i.putExtra(com.professor.zerion.android.contact.add.remote
				.AddContactActivity.EXTRA_RE_ADD_CONTACT, true);
		startActivity(i);
	}

	private void confirmMarkVerified() {
		new SecureAlertDialogBuilder(this)
				.setTitle(R.string.identity_mark_verified_title)
				.setMessage(R.string.identity_mark_verified_message)
				.setPositiveButton(R.string.identity_mark_verified_confirm,
						(dialog, which) -> viewModel.markContactVerified())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
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
