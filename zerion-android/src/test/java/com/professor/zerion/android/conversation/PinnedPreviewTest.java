package com.professor.zerion.android.conversation;

import android.content.Context;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.util.Collections;
import java.util.List;

import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PinnedPreviewTest {

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private final GroupId group = new GroupId(getRandomId());

	@Test
	public void aTextMessageShowsItsFirstLine() {
		ConversationMessageItem item = text("Meet at nine\nat the station");
		assertEquals("Meet at nine", PinnedPreview.of(ctx, item, null));
		assertEquals("Edited text", PinnedPreview.of(ctx, item, "Edited text"));
	}

	@Test
	public void attachmentsAreNamedByKindNeverByContent() {
		assertEquals("Photo", PinnedPreview.of(ctx,
				attachment("image/jpeg"), null));
		assertEquals("Video", PinnedPreview.of(ctx,
				attachment("video/mp4"), null));
		assertEquals("Voice message", PinnedPreview.of(ctx,
				attachment("audio/ogg"), null));
		assertEquals("File", PinnedPreview.of(ctx,
				attachment("application/pdf"), null));
		assertEquals("File", PinnedPreview.of(ctx,
				attachment("application/zip"), null));
	}

	@Test
	public void voiceMemoTextShowsVoiceMessage() {
		assertEquals("Voice message", PinnedPreview.of(ctx,
				text("[VOICE:abc:1:2]"), null));
	}

	@Test
	public void aSecretNoteNeverShowsItsContent() {
		String shown = PinnedPreview.of(ctx, text("SECRET:30:the content"),
				null);
		assertEquals(ctx.getString(R.string.secret_note_label), shown);
		assertFalse(shown.contains("the content"));
	}

	@Test
	public void aMessageWithoutTextOrAttachmentsSaysMedia() {
		assertEquals(ctx.getString(R.string.media),
				PinnedPreview.of(ctx, text(null), null));
	}

	@Test
	public void onlyLoadedMessageItemsCanBePinned() {
		assertTrue(PinnedPreview.canPin(text("hello")));
		assertTrue(PinnedPreview.canPin(attachment("image/jpeg")));
		assertFalse(PinnedPreview.canPin(null));
		ConversationMessageItem notLoaded = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out, header(true,
						Collections.emptyList()), new MutableLiveData<>("Bob"));
		assertFalse("text still loading", PinnedPreview.canPin(notLoaded));
	}

	private ConversationMessageItem text(String text) {
		ConversationMessageItem item = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out,
				header(text != null, Collections.emptyList()),
				new MutableLiveData<>("Bob"));
		if (text != null) item.setText(text);
		return item;
	}

	private ConversationMessageItem attachment(String contentType) {
		List<AttachmentHeader> headers = Collections.singletonList(
				new AttachmentHeader(group, new MessageId(getRandomId()),
						contentType));
		return new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out,
				header(false, headers), new MutableLiveData<>("Bob"));
	}

	private PrivateMessageHeader header(boolean hasText,
			List<AttachmentHeader> attachments) {
		return new PrivateMessageHeader(new MessageId(getRandomId()), group,
				System.currentTimeMillis(), true, true, true, false, hasText,
				attachments, -1L, null, false, false);
	}
}
