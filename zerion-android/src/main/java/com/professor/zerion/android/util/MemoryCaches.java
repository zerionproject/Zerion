package com.professor.zerion.android.util;

import com.professor.zerion.android.channel.ChannelFeedActivity;
import com.professor.zerion.android.conversation.ImageViewHolder;
import com.professor.zerion.android.grouptr.GroupTrMediaCache;
import com.professor.zerion.android.vault.ui.adapters.VaultGalleryAdapter;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class MemoryCaches {

	private MemoryCaches() {
	}

	public static void clearThumbnails() {
		VaultGalleryAdapter.clearThumbnailCache();
		ImageViewHolder.clearVideoThumbCache();
		ChannelFeedActivity.clearFeedThumbCache();
		GroupTrMediaCache.clear();
	}
}
