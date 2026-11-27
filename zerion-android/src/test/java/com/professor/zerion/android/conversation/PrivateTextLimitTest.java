package com.professor.zerion.android.conversation;

import android.os.Looper;
import android.widget.EditText;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.SnackbarProbe;
import com.professor.zerion.android.view.TextInputView;
import com.professor.zerion.android.view.TextSendController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.app.api.messaging.MessagingConstants.MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PrivateTextLimitTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final List<String> sent = new ArrayList<>();

	private AppCompatActivity activity;
	private TextSendController controller;

	private TextInputView composer() {
		activity = Robolectric.buildActivity(AppCompatActivity.class)
				.setup().get();
		activity.setTheme(R.style.ZerionTheme_NoActionBar);
		TextInputView input = new TextInputView(activity);
		activity.setContentView(input);
		controller = new TextSendController(input,
				new TextSendController.SendListener() {
					@Override
					public Lifecycle getLifecycle() {
						return activity.getLifecycle();
					}

					@Override
					public LiveData<TextSendController.SendState> onSendClick(
							String text, List<AttachmentHeader> headers,
							long expectedAutoDeleteTimer) {
						sent.add(text);
						return new MutableLiveData<>();
					}
				}, false);
		input.setSendController(controller);
		controller.setReady(true);
		input.setMaxTextLength(MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH);
		return input;
	}

	private void typeAndSend(TextInputView input, String text) {
		EditText field = input.findViewById(R.id.input_text);
		field.setText(text);
		controller.onSendEvent();
		shadowOf(Looper.getMainLooper()).idle();
	}

	@Test
	public void aTextLongerThanOneSyncRecordHoldsIsNotSent() {
		TextInputView input = composer();

		typeAndSend(input, ascii(MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH
				+ 1));

		assertTrue(sent.isEmpty());
		assertEquals(activity.getString(R.string.text_too_long),
				SnackbarProbe.text(activity));
	}

	@Test
	public void aTextAtTheLimitIsSent() {
		TextInputView input = composer();

		typeAndSend(input, ascii(MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH));

		assertEquals(1, sent.size());
	}

	@Test
	public void aSecretNoteIsCheckedWithItsWrapping() {
		assertFalse(ConversationActivity.secretNoteTooLong("hello"));
		assertTrue(ConversationActivity.secretNoteTooLong(ascii(
				MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH)));
		assertFalse(ConversationActivity.secretNoteTooLong(ascii(
				MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH - 64)));
	}

	private static String ascii(int length) {
		char[] c = new char[length];
		Arrays.fill(c, 'a');
		return new String(c);
	}
}
