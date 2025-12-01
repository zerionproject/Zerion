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
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

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
		prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

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

		viewModel.getContactItem().observe(this, contactItem -> {
			if (contactItem != null) {
				setAvatar(contactAvatar, contactItem);
				contactName.setText(contactItem.getContact().getAuthor().getName());

				contactStatus.setText(R.string.offline);

				contactAvatar.setOnClickListener(v -> showAvatarFullScreen(contactItem));
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
		SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		return prefs.getBoolean(PREF_MUTE_PREFIX + contactId.getInt(), false);
	}

	public static boolean isVibrationEnabled(Context context, ContactId contactId) {
		SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		return prefs.getBoolean(PREF_VIBRATION_PREFIX + contactId.getInt(), true);
	}

	public static long getDisappearingTimer(Context context, ContactId contactId) {
		SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		return prefs.getLong(PREF_TIMER_PREFIX + contactId.getInt(), 0);
	}

	private void showAvatarFullScreen(com.professor.zerion.android.contact.ContactItem contactItem) {
		android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
		dialog.setContentView(R.layout.dialog_avatar_fullscreen);

		ImageView fullScreenAvatar = dialog.findViewById(R.id.fullscreen_avatar);
		ImageView closeButton = dialog.findViewById(R.id.close_button);

		setAvatar((de.hdodenhof.circleimageview.CircleImageView) fullScreenAvatar, contactItem);

		closeButton.setOnClickListener(v -> dialog.dismiss());

		dialog.findViewById(R.id.dialog_background).setOnClickListener(v -> dialog.dismiss());

		dialog.show();
	}
}
