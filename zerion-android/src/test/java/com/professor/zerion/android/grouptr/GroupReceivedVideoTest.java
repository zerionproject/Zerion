package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.conversation.MediaViewer;
import com.professor.zerion.android.conversation.OpenedVideos;
import com.professor.zerion.android.conversation.ReceivedVideoFrameTest.CountingRetriever;
import com.professor.zerion.android.conversation.VideoPlayerActivity;
import com.professor.zerion.android.grouptr.GroupMediaViewerTest.ProbeConversation;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectionBarTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrPost;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import androidx.appcompat.widget.AppCompatImageButton;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = CountingRetriever.class)
public class GroupReceivedVideoTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String GROUP_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	private static final class Stub implements GroupTrPostAdapter.Callback {

		private final boolean mine;
		int thumbs;

		Stub(boolean mine) {
			this.mine = mine;
		}

		@Override
		public boolean isMine(GroupTrPost p) {
			return mine;
		}

		@Override
		public void bindSender(TextView tv, GroupTrPost p) {
			tv.setText(p.getSenderName());
		}

		@Override
		public void onImageClick(GroupTrPost p, byte[] bytes,
				@Nullable String mime) {
		}

		@Override
		public void onVideoClick(GroupTrPost p, byte[] bytes,
				@Nullable String mime) {
		}

		@Override
		public void onVoiceClick(AppCompatImageButton btn, byte[] audio) {
		}

		@Nullable
		@Override
		public Bitmap videoThumb(byte[] videoBytes) {
			thumbs++;
			return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
		}

		@Override
		public String formatTime(long ts) {
			return "13:37";
		}

		@Override
		public String formatDuration(long ms) {
			return "0:01";
		}

		@Override
		public void onPostLongClick(GroupTrPost p) {
		}

		@Override
		public boolean onPostClick(GroupTrPost p) {
			return false;
		}

		@Override
		public boolean isSelected(GroupTrPost p) {
			return false;
		}

		@Override
		public boolean selecting() {
			return false;
		}

		@Override
		public String quoteAuthor(GroupTrPost p) {
			return "";
		}

		@Override
		public void onQuoteClick(byte[] postId) {
		}
	}

	private Executor decoder;

	@Before
	public void setUp() {
		decoder = GroupTrPostAdapter.decoder;
		GroupTrPostAdapter.decoder = Runnable::run;
		CountingRetriever.sources = 0;
		CountingRetriever.frames = 0;
		OpenedVideos.clear();
		GroupTrMediaCache.clear();
		ProbeConversation.io.clear();
		ProbeConversation.groupBlock = false;
		ProbeConversation.timerMs = 0L;
		ProbeConversation.postId = getRandomId();
		shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("mp4", "video/mp4");
	}

	@After
	public void tearDown() {
		GroupTrPostAdapter.decoder = decoder;
		OpenedVideos.clear();
		GroupTrMediaCache.clear();
		ProbeConversation.io.clear();
		ProbeConversation.postId = new byte[32];
		MediaViewer.clearAll();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static byte[] box(String type, byte[] body) {
		int n = body.length + 8;
		return concat(new byte[] {(byte) (n >> 24), (byte) (n >> 16),
				(byte) (n >> 8), (byte) n},
				type.getBytes(StandardCharsets.US_ASCII), body);
	}

	private static byte[] mp4() {
		return concat(box("ftyp", "isom\0\0\2\0isommp41"
						.getBytes(StandardCharsets.ISO_8859_1)),
				box("moov", box("mvhd", new byte[100])),
				box("mdat", new byte[4096]));
	}

	private static GroupTrPost videoPost(boolean mine) {
		return new GroupTrPost(new byte[32], new byte[32], "Alice",
				GroupTrBody.encodeVideo(mp4(), "video/mp4", 1000L), 1000L,
				1L, mine, 0L, 1000L, getRandomId());
	}

	private static GroupTrPostAdapter.PostHolder shown(
			GroupTrPostAdapter adapter) {
		SelectionBarTest.Probe screen = Robolectric.buildActivity(
				SelectionBarTest.Probe.class).setup().get();
		FrameLayout parent = new FrameLayout(screen);
		screen.setContentView(parent);
		GroupTrPostAdapter.PostHolder h = adapter.onCreateViewHolder(parent,
				adapter.getItemViewType(0));
		parent.addView(h.itemView);
		adapter.onBindViewHolder(h, 0);
		idle();
		return h;
	}

	@Test
	public void aMembersVideoIsNotParsedBeforeItIsOpened() {
		Stub cb = new Stub(false);
		GroupTrPostAdapter adapter = new GroupTrPostAdapter(cb);
		GroupTrPost post = videoPost(false);
		adapter.setPosts(Collections.singletonList(post));
		GroupTrPostAdapter.PostHolder h = shown(adapter);

		assertEquals("a frame was asked for before any tap", 0, cb.thumbs);
		ImageView thumb = h.itemView.findViewById(R.id.videoThumb);
		assertNull(thumb.getDrawable());
		TextView size = h.itemView.findViewById(R.id.videoSize);
		assertEquals(View.VISIBLE, size.getVisibility());
		assertEquals(Formatter.formatShortFileSize(size.getContext(),
				mp4().length), size.getText().toString());
		assertEquals(View.VISIBLE, h.itemView.findViewById(
				R.id.videoPlayIcon).getVisibility());

		adapter.videoOpened(post);
		adapter.onBindViewHolder(h, 0);
		idle();
		assertEquals("no frame was taken after the open", 1, cb.thumbs);
		assertNotNull("the frame was not drawn", thumb.getDrawable());
		assertEquals(View.GONE, size.getVisibility());

		adapter.onBindViewHolder(h, 0);
		idle();
		assertEquals("the kept frame was not used again", 1, cb.thumbs);
	}

	@Test
	public void myOwnVideoGetsItsFrameAtOnceWhileAMembersVideoWaits() {
		Stub own = new Stub(true);
		GroupTrPostAdapter mine = new GroupTrPostAdapter(own);
		mine.setPosts(Collections.singletonList(videoPost(true)));
		GroupTrPostAdapter.PostHolder h = shown(mine);
		assertEquals("an own video lost its frame", 1, own.thumbs);
		assertNotNull(((ImageView) h.itemView.findViewById(R.id.videoThumb))
				.getDrawable());

		Stub member = new Stub(false);
		GroupTrPostAdapter theirs = new GroupTrPostAdapter(member);
		theirs.setPosts(Collections.singletonList(videoPost(false)));
		shown(theirs);
		assertEquals("a member's video was parsed", 0, member.thumbs);
	}

	@Test
	public void aRemovedPostIsNoLongerRememberedAsOpened() {
		Stub cb = new Stub(false);
		GroupTrPostAdapter adapter = new GroupTrPostAdapter(cb);
		GroupTrPost post = videoPost(false);
		GroupTrPost other = videoPost(false);
		adapter.setPosts(Collections.singletonList(post));
		adapter.videoOpened(post);
		adapter.videoOpened(other);
		assertTrue(GroupTrMediaCache.isVideoOpened(post));

		GroupTrMediaCache.clear();
		assertTrue("freeing memory forgot the open",
				GroupTrMediaCache.isVideoOpened(post));

		GroupTrMediaCache.evictPosts(post.getGroupId(),
				Collections.singletonList(post.getPostId()));
		assertFalse(GroupTrMediaCache.isVideoOpened(post));
		assertTrue(GroupTrMediaCache.isVideoOpened(other));

		adapter.setPosts(Collections.singletonList(post));
		shown(adapter);
		assertEquals("a removed post kept its open", 0, cb.thumbs);

		GroupTrMediaCache.evictGroup(other.getGroupId());
		assertFalse(GroupTrMediaCache.isVideoOpened(other));

		GroupTrPost deleted = videoPost(false);
		adapter.setPosts(Collections.singletonList(deleted));
		adapter.videoOpened(deleted);
		assertTrue(GroupTrMediaCache.isVideoOpened(deleted));
		adapter.removePost(deleted);
		assertFalse("a deleted post kept its open",
				GroupTrMediaCache.isVideoOpened(deleted));
	}

	private static void runIo() {
		for (int round = 0; round < 4; round++) {
			idle();
			List<Runnable> pending = new ArrayList<>(ProbeConversation.io);
			ProbeConversation.io.clear();
			for (Runnable r : pending) {
				try {
					r.run();
				} catch (RuntimeException ignored) {
				}
			}
		}
		idle();
	}

	private static RecyclerView.ViewHolder row(ProbeConversation a) {
		RecyclerView list = a.findViewById(R.id.postsRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		idle();
		RecyclerView.ViewHolder holder =
				list.findViewHolderForAdapterPosition(0);
		assertNotNull("the post is shown", holder);
		return holder;
	}

	@Test
	public void openingAGroupParsesNoVideoUntilTheVideoIsTapped() {
		ProbeConversation.body = GroupTrBody.encodeVideo(mp4(), "video/mp4",
				1000L);
		Intent open = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		open.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		ProbeConversation a = Robolectric.buildActivity(
				ProbeConversation.class, open).setup().get();
		runIo();
		RecyclerView.ViewHolder holder = row(a);

		assertEquals("the video was handed to the system parser", 0,
				CountingRetriever.sources);
		assertEquals(View.VISIBLE, holder.itemView.findViewById(
				R.id.videoSize).getVisibility());

		while (shadowOf(a).getNextStartedActivity() != null) {
			idle();
		}
		holder.itemView.findViewById(R.id.mediaBubble).performClick();
		runIo();
		Intent player = shadowOf(a).getNextStartedActivity();
		assertNotNull("the player did not start", player);
		assertEquals(VideoPlayerActivity.class.getName(),
				player.getComponent().getClassName());

		row(a);
		assertEquals("no frame was taken after the open", 1,
				CountingRetriever.sources);
		assertEquals(1, CountingRetriever.frames);
	}
}
