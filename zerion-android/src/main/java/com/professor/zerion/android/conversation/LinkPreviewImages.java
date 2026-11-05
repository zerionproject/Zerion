package com.professor.zerion.android.conversation;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class LinkPreviewImages {

	private LinkPreviewImages() {
	}

	public static void clear() {
		ConversationItemViewHolder.clearPreviewImages();
	}
}
