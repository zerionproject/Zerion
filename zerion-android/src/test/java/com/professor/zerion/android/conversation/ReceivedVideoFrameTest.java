package com.professor.zerion.android.conversation;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.AttachmentItemsForTests;
import com.professor.zerion.android.conversation.glide.Radii;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectionBarTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.io.ByteArrayInputStream;
import java.util.Collections;

import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = ReceivedVideoFrameTest.CountingRetriever.class)
public class ReceivedVideoFrameTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Implements(MediaMetadataRetriever.class)
	public static class CountingRetriever {

		public static int sources;
		public static int frames;

		@Implementation
		protected void setDataSource(MediaDataSource source) {
			sources++;
		}

		@Implementation
		protected Bitmap getFrameAtTime(long timeUs, int option) {
			frames++;
			return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
		}

		@Implementation
		protected Bitmap getFrameAtTime() {
			frames++;
			return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
		}

		@Implementation
		protected void release() {
		}
	}

	private static final byte[] VIDEO = video();

	private static byte[] video() {
		byte[] v = new byte[1_572_864];
		byte[] start = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o',
				'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '1'};
		System.arraycopy(start, 0, v, 0, start.length);
		return v;
	}

	private static final class Bubble extends FrameLayout {

		Bubble(Context ctx) {
			super(ctx);
		}

		@Override
		protected void onMeasure(int width, int height) {
			setMeasuredDimension(100, 100);
		}

		@Override
		protected void onLayout(boolean changed, int l, int t, int r, int b) {
		}
	}

	private AttachmentReader reader;

	@Before
	public void setUp() throws Exception {
		CountingRetriever.sources = 0;
		CountingRetriever.frames = 0;
		ImageViewHolder.clearVideoThumbCache();
		OpenedVideos.clear();
		reader = mock(AttachmentReader.class);
		when(reader.getAttachment(any(AttachmentHeader.class))).thenAnswer(
				inv -> new Attachment(inv.getArgument(0),
						new ByteArrayInputStream(VIDEO)));
		when(reader.getStoredSize(any(AttachmentHeader.class)))
				.thenReturn((long) VIDEO.length);
	}

	@After
	public void tearDown() {
		ImageViewHolder.clearVideoThumbCache();
		OpenedVideos.clear();
		Glide.tearDown();
	}

	private ImageViewHolder holder(MessageId madeFor) {
		SelectionBarTest.Probe ctx = Robolectric.buildActivity(
				SelectionBarTest.Probe.class).setup().get();
		FrameLayout bubble = new Bubble(ctx);
		ctx.setContentView(bubble);
		View v = LayoutInflater.from(ctx).inflate(R.layout.list_item_image,
				bubble, false);
		bubble.addView(v);
		v.setLayoutParams(new StaggeredGridLayoutManager.LayoutParams(100,
				100));
		return new ImageViewHolder(v, 100, madeFor, reader, Runnable::run,
				Runnable::run);
	}

	private static AttachmentItem video(MessageId attachment) {
		return AttachmentItemsForTests.available(new AttachmentHeader(
				new GroupId(getRandomId()), attachment, "video/mp4"), "mp4");
	}

	private static void show(ImageViewHolder h, MessageId message,
			AttachmentItem video, boolean incoming) {
		h.inBubble(incoming);
		h.ofMessage(message);
		h.bind(video, new Radii(0, 0, 0, 0), true, false);
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static int shownResource(ImageViewHolder h) {
		ImageView image = h.itemView.findViewById(R.id.imageView);
		return image.getDrawable() == null ? 0
				: shadowOf(image.getDrawable()).getCreatedFromResId();
	}

	private static TextView sizeLabel(ImageViewHolder h) {
		return h.itemView.findViewById(R.id.videoSize);
	}

	@Test
	public void aReceivedVideoIsNotParsedWhenTheChatShowsIt()
			throws Exception {
		MessageId message = new MessageId(getRandomId());
		ImageViewHolder h = holder(message);
		show(h, message, video(new MessageId(getRandomId())), true);

		assertEquals("the video was handed to the system parser", 0,
				CountingRetriever.sources);
		assertEquals(0, CountingRetriever.frames);
		assertEquals(R.drawable.ic_video, shownResource(h));
		assertEquals(View.VISIBLE, h.itemView.findViewById(R.id.playOverlay)
				.getVisibility());
		TextView size = sizeLabel(h);
		assertEquals(View.VISIBLE, size.getVisibility());
		String expected = Formatter.formatShortFileSize(size.getContext(),
				VIDEO.length);
		assertEquals(expected, size.getText().toString());
		assertTrue(expected, expected.contains("MB"));
		ImageView image = h.itemView.findViewById(R.id.imageView);
		assertEquals("Video, " + expected,
				String.valueOf(image.getContentDescription()));
		verify(reader, never()).getAttachment(any(AttachmentHeader.class));
	}

	@Test
	public void aReceivedVideoWithoutAStoredSizeShowsNoSize()
			throws Exception {
		when(reader.getStoredSize(any(AttachmentHeader.class)))
				.thenReturn(-1L);
		MessageId message = new MessageId(getRandomId());
		ImageViewHolder h = holder(message);
		show(h, message, video(new MessageId(getRandomId())), true);

		assertEquals(View.GONE, sizeLabel(h).getVisibility());
		assertEquals(R.drawable.ic_video, shownResource(h));
		verify(reader, never()).getAttachment(any(AttachmentHeader.class));
		assertEquals(0, CountingRetriever.sources);
	}

	@Test
	public void aReceivedVideoGetsItsFrameOnceItWasOpened() {
		MessageId message = new MessageId(getRandomId());
		MessageId attachment = new MessageId(getRandomId());
		ImageViewHolder h = holder(message);
		show(h, message, video(attachment), true);
		assertEquals(0, CountingRetriever.sources);

		assertTrue(ImageViewHolder.videoOpened(message, attachment));
		show(h, message, video(attachment), true);

		assertEquals("no frame was taken after the open", 1,
				CountingRetriever.sources);
		assertEquals(1, CountingRetriever.frames);
		assertNotEquals("the placeholder stayed", R.drawable.ic_video,
				shownResource(h));
		assertEquals(View.GONE, sizeLabel(h).getVisibility());

		show(h, message, video(attachment), true);
		assertEquals("the kept frame was not used again", 1,
				CountingRetriever.sources);
		assertFalse("the open was forgotten",
				ImageViewHolder.videoOpened(message, attachment));
	}

	@Test
	public void anOwnVideoGetsItsFrameAtOnceWhileAReceivedOneWaits() {
		MessageId mine = new MessageId(getRandomId());
		show(holder(mine), mine, video(new MessageId(getRandomId())), false);
		assertEquals("an own video lost its frame", 1,
				CountingRetriever.sources);

		MessageId theirs = new MessageId(getRandomId());
		ImageViewHolder waiting = holder(theirs);
		show(waiting, theirs, video(new MessageId(getRandomId())), true);
		assertEquals("a received video was parsed", 1,
				CountingRetriever.sources);
		assertEquals(R.drawable.ic_video, shownResource(waiting));
	}

	@Test
	public void openingOneVideoLeavesEveryOtherVideoWaiting() {
		MessageId opened = new MessageId(getRandomId());
		MessageId attachment = new MessageId(getRandomId());
		ImageViewHolder.videoOpened(opened, attachment);

		MessageId other = new MessageId(getRandomId());
		show(holder(other), other, video(new MessageId(getRandomId())),
				true);
		show(holder(other), other, video(attachment), true);
		assertEquals("another message borrowed the open", 0,
				CountingRetriever.sources);
	}

	@Test
	public void aRecycledTileGoesByTheMessageItShowsNow() {
		MessageId first = new MessageId(getRandomId());
		MessageId second = new MessageId(getRandomId());
		MessageId attachment = new MessageId(getRandomId());
		ImageViewHolder.videoOpened(first, attachment);

		ImageViewHolder h = holder(first);
		show(h, second, video(attachment), true);
		assertEquals("the tile went by the message it was made for", 0,
				CountingRetriever.sources);

		ImageViewHolder other = holder(second);
		show(other, first, video(attachment), true);
		assertEquals("the tile went by the message it was made for", 1,
				CountingRetriever.sources);
	}

	@Test
	public void aDeletedMessageIsNoLongerRememberedAsOpened() {
		MessageId message = new MessageId(getRandomId());
		MessageId attachment = new MessageId(getRandomId());
		ImageViewHolder.videoOpened(message, attachment);
		MessageId kept = new MessageId(getRandomId());
		ImageViewHolder.videoOpened(kept, attachment);

		ImageViewHolder.evictVideoThumbs(Collections.singleton(message));
		show(holder(message), message, video(attachment), true);
		assertEquals("a deleted message kept its open", 0,
				CountingRetriever.sources);
		assertFalse("another message lost its open",
				ImageViewHolder.videoOpened(kept, attachment));

		ImageViewHolder.forgetOpenedVideos();
		assertTrue(ImageViewHolder.videoOpened(kept, attachment));
	}

	@Test
	public void onlyAVideoThatHasArrivedCanBeMarkedAsOpened() {
		MessageId message = new MessageId(getRandomId());
		AttachmentHeader header = new AttachmentHeader(
				new GroupId(getRandomId()), new MessageId(getRandomId()),
				"video/mp4");
		assertFalse(ImageViewHolder.videoOpened(message,
				AttachmentItemsForTests.loading(header)));
		assertFalse(ImageViewHolder.videoOpened(message,
				AttachmentItemsForTests.missing(header)));
		assertEquals(0, OpenedVideos.size());
		assertTrue(ImageViewHolder.videoOpened(message,
				AttachmentItemsForTests.available(header, "mp4")));
		assertFalse(ImageViewHolder.videoOpened(message,
				header.getMessageId()));
	}

	@Test
	public void theListOfOpenedVideosStaysSmall() {
		for (int i = 0; i < OpenedVideos.MAX_REMEMBERED + 40; i++) {
			OpenedVideos.add("video " + i);
		}
		assertEquals(OpenedVideos.MAX_REMEMBERED, OpenedVideos.size());
		assertFalse(OpenedVideos.contains("video 0"));
		assertTrue(OpenedVideos.contains("video "
				+ (OpenedVideos.MAX_REMEMBERED + 39)));
	}
}
