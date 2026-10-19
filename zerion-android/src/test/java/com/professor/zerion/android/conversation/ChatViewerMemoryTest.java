package com.professor.zerion.android.conversation;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Looper;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.AttachmentItemsForTests;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.util.ArrayList;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChatViewerMemoryTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();

	private static Bitmap leftover() {
		return Bitmap.createBitmap(37, 23, Bitmap.Config.ARGB_8888);
	}

	@Test
	public void closingAChatViewerDropsDecodedPicturesGlideStillHolds() {
		BitmapPool pool = Glide.get(ctx).getBitmapPool();
		Bitmap probe = leftover();
		pool.put(probe);
		Bitmap again = pool.getDirty(37, 23, Bitmap.Config.ARGB_8888);
		assertSame("the image pool keeps nothing here", probe, again);
		pool.put(again);

		ArrayList<AttachmentItem> items = new ArrayList<>();
		items.add(AttachmentItemsForTests.missing(new AttachmentHeader(
				new GroupId(getRandomId()), new MessageId(getRandomId()),
				"image/jpeg")));
		Intent i = new Intent(ctx, ImageActivity.class)
				.putParcelableArrayListExtra(ImageActivity.ATTACHMENTS, items)
				.putExtra(ImageActivity.ATTACHMENT_POSITION, 0)
				.putExtra(ImageActivity.NAME, "Bob")
				.putExtra(ImageActivity.DATE, 1000L)
				.putExtra(ImageActivity.ITEM_ID, getRandomId());
		ActivityController<ImageActivity> c =
				Robolectric.buildActivity(ImageActivity.class, i).setup();
		c.get().finish();
		c.pause().stop().destroy();
		shadowOf(Looper.getMainLooper()).idle();

		assertNotSame("a decoded picture outlived the chat viewer", probe,
				pool.getDirty(37, 23, Bitmap.Config.ARGB_8888));
	}

	@Test
	public void theChatViewerAsksGlideNotToKeepThePicture() {
		AttachmentHeader header = new AttachmentHeader(
				new GroupId(getRandomId()), new MessageId(getRandomId()),
				"image/jpeg");
		com.bumptech.glide.RequestBuilder<?> request =
				ImageFragment.viewerRequest(Glide.with(ctx), header);
		org.junit.Assert.assertFalse("the full picture is cached in memory",
				request.isMemoryCacheable());
		org.junit.Assert.assertEquals(
				com.bumptech.glide.load.engine.DiskCacheStrategy.NONE,
				request.getDiskCacheStrategy());
	}
}
