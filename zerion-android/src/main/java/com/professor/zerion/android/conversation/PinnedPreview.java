package com.professor.zerion.android.conversation;

import android.content.Context;

import com.professor.zerion.R;
import com.professor.zerion.android.conversation.voice.VoiceMessageChunkFormat;

import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
final class PinnedPreview {

	private PinnedPreview() {
	}

	static boolean canPin(@Nullable ConversationItem item) {
		return item instanceof ConversationMessageItem
				&& !ConversationActivity.textNotLoaded(item);
	}

	static String of(Context ctx, ConversationItem item,
			@Nullable String text) {
		if (!(item instanceof ConversationMessageItem)) {
			return ctx.getString(R.string.media);
		}
		String t = text == null ? item.getText() : text;
		if (t != null && !t.isEmpty()) {
			if (SecretNotes.isSecret(t)) {
				return ctx.getString(R.string.secret_note_label);
			}
			if (t.startsWith("[VOICE:") || VoiceMessageChunkFormat.isPart(t)) {
				return ctx.getString(R.string.voice_message);
			}
			int nl = t.indexOf('\n');
			return nl >= 0 ? t.substring(0, nl) : t;
		}
		PrivateMessageHeader h = ((ConversationMessageItem) item).getHeader();
		List<AttachmentHeader> attachments = h == null
				? java.util.Collections.<AttachmentHeader>emptyList()
				: h.getAttachmentHeaders();
		if (attachments.isEmpty()) return ctx.getString(R.string.media);
		return ctx.getString(kind(attachments.get(0).getContentType()));
	}

	static int kind(@Nullable String contentType) {
		String type = contentType == null ? "" : contentType;
		if (type.startsWith("image/")) return R.string.pinned_preview_photo;
		if (type.startsWith("video/")) return R.string.video;
		if (type.startsWith("audio/")) return R.string.voice_message;
		return R.string.attachment_kind_file;
	}
}
