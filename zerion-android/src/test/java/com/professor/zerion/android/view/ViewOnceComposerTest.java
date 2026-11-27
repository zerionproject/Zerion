package com.professor.zerion.android.view;

import android.net.Uri;
import android.view.View;
import android.widget.CompoundButton;

import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItemsForTests;
import com.professor.zerion.android.attachment.AttachmentManager;
import com.professor.zerion.android.attachment.AttachmentResult;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.view.TextAttachmentController.AttachmentListener;
import com.professor.zerion.android.view.TextSendController.SendState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ViewOnceComposerTest {

	static {
		TestAndroidKeyStore.register();
	}

	private AppCompatActivity activity;
	private TextInputView input;
	private ImagePreview preview;
	private TextAttachmentController controller;
	private FakeAttachments attachments;
	private FakeListener listener;

	@Before
	public void setUp() {
		activity = Robolectric.buildActivity(AppCompatActivity.class)
				.setup().get();
		activity.setTheme(R.style.ZerionTheme_NoActionBar);
		input = new TextInputView(activity);
		preview = new ImagePreview(activity);
		attachments = new FakeAttachments();
		listener = new FakeListener();
		controller = new TextAttachmentController(input, preview, listener,
				attachments);
		input.setSendController(controller);
	}

	private void pick(String... types) {
		List<Uri> uris = new ArrayList<>();
		List<com.professor.zerion.android.attachment.AttachmentItemResult>
				results = new ArrayList<>();
		GroupId g = new GroupId(getRandomId());
		for (int i = 0; i < types.length; i++) {
			Uri uri = Uri.parse("content://picked/" + i);
			uris.add(uri);
			AttachmentHeader h = new AttachmentHeader(g,
					new MessageId(getRandomId()), types[i]);
			attachments.headers.add(h);
			results.add(AttachmentItemsForTests.result(uri,
					AttachmentItemsForTests.available(h, "jpg")));
		}
		controller.onImageReceived(uris);
		attachments.result.setValue(new AttachmentResult(results, true));
	}

	private CompoundButton viewOnce() {
		return preview.findViewById(R.id.viewOnceToggle);
	}

	private void send() {
		controller.onSendEvent();
	}

	@Test
	public void theToggleIsOfferedOnlyToAContactThatSupportsIt() {
		pick("image/jpeg");
		assertEquals(View.GONE, viewOnce().getVisibility());

		controller.setViewOnceAvailable(true, false);

		assertEquals(View.VISIBLE, viewOnce().getVisibility());
		assertEquals(activity.getString(R.string.view_once),
				viewOnce().getText().toString());
	}

	@Test
	public void theToggleIsOfferedForOnePhotoOrOneVideoOnly() {
		controller.setViewOnceAvailable(true, false);
		pick("video/mp4");
		assertTrue(controller.isViewOnceOffered());
		controller.onCancel();

		pick("audio/ogg");
		assertFalse(controller.isViewOnceOffered());
		assertEquals(View.GONE, viewOnce().getVisibility());
		controller.onCancel();

		pick("application/pdf");
		assertFalse(controller.isViewOnceOffered());
		controller.onCancel();

		pick("image/jpeg", "image/jpeg");
		assertFalse(controller.isViewOnceOffered());
		assertEquals(View.GONE, viewOnce().getVisibility());
	}

	@Test
	public void aViewOncePhotoIsSentWithoutACaptionAndTheInputIsLocked() {
		controller.setViewOnceAvailable(true, false);
		pick("image/jpeg");

		viewOnce().setChecked(true);

		assertFalse(input.getEmojiTextInputView().isEnabled());
		send();
		assertTrue(listener.viewOnce);
		assertNull(listener.text);
		assertEquals(1, listener.headers.size());
	}

	@Test
	public void typingIntoAnOpenKeyboardWhileViewOnceIsOnChangesNothing() {
		controller.setViewOnceAvailable(true, false);
		pick("image/jpeg");
		android.widget.EditText edit = input.findViewById(R.id.input_text);
		edit.requestFocus();
		android.view.inputmethod.InputConnection keyboard =
				edit.onCreateInputConnection(
						new android.view.inputmethod.EditorInfo());
		assertNotNull(keyboard);

		viewOnce().setChecked(true);
		assertFalse(edit.isFocusable());
		assertFalse(edit.hasFocus());
		keyboard.commitText("hidden caption", 1);
		edit.dispatchKeyEvent(new android.view.KeyEvent(
				android.view.KeyEvent.ACTION_DOWN,
				android.view.KeyEvent.KEYCODE_A));
		edit.dispatchKeyEvent(new android.view.KeyEvent(
				android.view.KeyEvent.ACTION_UP,
				android.view.KeyEvent.KEYCODE_A));
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
				.idle();

		assertEquals("", edit.getText().toString());
		assertTrue("typing unticked View once", viewOnce().isChecked());
		send();
		assertTrue(listener.viewOnce);
		assertNull(listener.text);
	}

	@Test
	public void untickingViewOnceGivesTheCaptionBack() {
		controller.setViewOnceAvailable(true, false);
		pick("image/jpeg");
		android.widget.EditText edit = input.findViewById(R.id.input_text);
		viewOnce().setChecked(true);

		viewOnce().setChecked(false);

		assertTrue(edit.isFocusable());
		edit.setText("now a caption");
		assertEquals("now a caption", edit.getText().toString());
	}

	@Test
	public void aCaptionKeepsTheToggleOff() {
		controller.setViewOnceAvailable(true, false);
		pick("image/jpeg");
		input.getEmojiTextInputView().setText("look at this");

		assertFalse(viewOnce().isEnabled());
		viewOnce().setChecked(true);
		assertFalse(controller.isViewOnceChosen());
		send();
		assertFalse(listener.viewOnce);
		assertEquals("look at this", listener.text);
	}

	@Test
	public void sendingResetsTheToggle() {
		controller.setViewOnceAvailable(true, false);
		pick("image/jpeg");
		viewOnce().setChecked(true);
		send();

		listener.state.setValue(SendState.SENT);

		assertFalse(viewOnce().isChecked());
		assertEquals(View.GONE, viewOnce().getVisibility());
	}

	@Test
	public void offlineTheSwitchSaysWhyItIsOff() {
		controller.setViewOnceAvailable(true, true);
		pick("image/jpeg");

		assertEquals(View.VISIBLE, viewOnce().getVisibility());
		assertFalse(viewOnce().isEnabled());
		assertEquals(activity.getString(R.string.view_once_offline),
				viewOnce().getText().toString());
		assertFalse(controller.isViewOnceOffered());
		viewOnce().setChecked(true);
		send();
		assertFalse(listener.viewOnce);

		controller.setViewOnceAvailable(true, false);
		assertTrue(viewOnce().isEnabled());
		assertEquals(activity.getString(R.string.view_once),
				viewOnce().getText().toString());
	}

	@Test
	public void aContactThatLosesSupportGetsNoViewOnce() {
		controller.setViewOnceAvailable(true, false);
		pick("image/jpeg");
		viewOnce().setChecked(true);

		controller.setViewOnceAvailable(false, false);
		send();

		assertFalse(listener.viewOnce);
	}

	private static class FakeAttachments implements AttachmentManager {

		final List<AttachmentHeader> headers = new ArrayList<>();
		MutableLiveData<AttachmentResult> result = new MutableLiveData<>();

		@Override
		public LiveData<AttachmentResult> storeAttachments(Collection<Uri> uri,
				boolean restart) {
			result = new MutableLiveData<>();
			return result;
		}

		@Override
		public List<AttachmentHeader> getAttachmentHeadersForSending() {
			return new ArrayList<>(headers);
		}

		@Override
		public boolean hasValidAttachments() {
			return !headers.isEmpty();
		}

		@Override
		public void cancel() {
			headers.clear();
		}
	}

	private class FakeListener implements AttachmentListener {

		final MutableLiveData<SendState> state = new MutableLiveData<>();
		@Nullable
		String text;
		List<AttachmentHeader> headers = Collections.emptyList();
		boolean viewOnce;

		@Override
		public LiveData<SendState> onSendClick(@Nullable String text,
				List<AttachmentHeader> headers, long timer) {
			return onSendClick(text, headers, timer, false);
		}

		@Override
		public LiveData<SendState> onSendClick(@Nullable String text,
				List<AttachmentHeader> headers, long timer, boolean viewOnce) {
			this.text = text;
			this.headers = headers;
			this.viewOnce = viewOnce;
			return state;
		}

		@Override
		public void onAttachImageClicked() {
		}

		@Override
		public void onTooManyAttachments() {
		}

		@Override
		public void onDisappearingMessagesTimerChanged(long timer) {
		}

		@Override
		public Lifecycle getLifecycle() {
			return activity.getLifecycle();
		}
	}
}
