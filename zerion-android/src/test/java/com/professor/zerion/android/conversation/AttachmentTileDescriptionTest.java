package com.professor.zerion.android.conversation;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.AttachmentItemsForTests;
import com.professor.zerion.android.conversation.glide.Radii;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AttachmentTileDescriptionTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static Context ctx() {
		return new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
	}

	private static AttachmentHeader header(String type) {
		return new AttachmentHeader(new GroupId(getRandomId()),
				new MessageId(getRandomId()), type);
	}

	private static String described(AttachmentItem item) {
		View v = LayoutInflater.from(ctx()).inflate(R.layout.list_item_image,
				new FrameLayout(ctx()), false);
		v.setLayoutParams(new StaggeredGridLayoutManager.LayoutParams(100,
				100));
		ImageViewHolder h = new ImageViewHolder(v, 100,
				new MessageId(getRandomId()));
		h.bind(item, new Radii(0, 0, 0, 0), true, false);
		ImageView image = v.findViewById(R.id.imageView);
		return String.valueOf(image.getContentDescription());
	}

	@Test
	public void aDocumentTileSaysWhichKindOfDocumentItIs() {
		assertEquals("PDF document", described(AttachmentItemsForTests
				.available(header("application/pdf"), "pdf")));
		assertEquals("Text document", described(AttachmentItemsForTests
				.available(header("text/plain"), "txt")));
	}

	@Test
	public void aPictureOrVideoTileStillWaitingSaysWhatItIs() {
		assertEquals("Image", described(AttachmentItemsForTests
				.missing(header("image/jpeg"))));
		assertEquals("Video", described(AttachmentItemsForTests
				.missing(header("video/mp4"))));
	}
}
