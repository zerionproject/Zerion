package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.webkit.MimeTypeMap;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.conversation.ImageActivity;
import com.professor.zerion.android.conversation.MediaViewer;
import com.professor.zerion.android.conversation.TestPictures;
import com.professor.zerion.android.conversation.VideoPlayerActivity;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.IdentityManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupMediaViewerTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String GROUP_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	public static class ProbeConversation extends GroupTrConversationActivity {

		static final List<Runnable> io = new ArrayList<>();
		static byte[] body = new byte[0];
		static boolean groupBlock;
		static byte[] postId = new byte[32];
		static long timerMs = 0L;

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			GroupTrManager m = mock(GroupTrManager.class);
			GroupTrState state = mock(GroupTrState.class);
			when(state.getName()).thenReturn("Group");
			long now = System.currentTimeMillis();
			GroupTrPost post = new GroupTrPost(new byte[32], new byte[32],
					"Member", body, now, 1L, false, timerMs, now, postId);
			try {
				when(m.getGroup(any())).thenReturn(state);
				when(m.getRecentPosts(any()))
						.thenReturn(Collections.singletonList(post));
				when(m.isLocalScreenshotBlocked(any())).thenReturn(groupBlock);
			} catch (DbException e) {
				throw new AssertionError(e);
			}
			groupTrManager = m;
			identityManager = mock(IdentityManager.class, RETURNS_DEEP_STUBS);
			ioExecutor = io::add;
		}
	}

	@Before
	public void setUp() {
		ProbeConversation.io.clear();
		ProbeConversation.groupBlock = false;
		ProbeConversation.postId = new byte[32];
		ProbeConversation.timerMs = 0L;
		shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("mp4", "video/mp4");
		shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("txt", "text/plain");
	}

	@After
	public void tearDown() {
		ProbeConversation.io.clear();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
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
				box("mdat", new byte[64]));
	}

	private static byte[] jpeg() {
		return TestPictures.png("group picture"
				.getBytes(StandardCharsets.US_ASCII));
	}

	private List<Intent> tap(byte[] postBody, int target) {
		ProbeConversation.body = postBody;
		Intent open = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		open.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		ProbeConversation a = Robolectric.buildActivity(
				ProbeConversation.class, open).setup().get();
		runIo();
		RecyclerView list = a.findViewById(R.id.postsRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		RecyclerView.ViewHolder holder =
				list.findViewHolderForAdapterPosition(0);
		assertNotNull("the post is shown", holder);
		while (shadowOf(a).getNextStartedActivity() != null) {
			idle();
		}
		try (MockedStatic<FileProvider> provider =
				mockStatic(FileProvider.class)) {
			provider.when(() -> FileProvider.getUriForFile(any(),
					anyString(), any(File.class))).thenAnswer(inv ->
					Uri.parse("content://" + inv.getArgument(1) + "/staged/"
							+ ((File) inv.getArgument(2)).getName()));
			holder.itemView.findViewById(target).performClick();
			runIo();
		}
		List<Intent> launched = new ArrayList<>();
		Intent i;
		while ((i = shadowOf(a).getNextStartedActivity()) != null) {
			launched.add(i);
		}
		return launched;
	}

	private List<Intent> tapVideo(byte[] content, String declared) {
		return tap(GroupTrBody.encodeVideo(content, declared, 1000L),
				R.id.mediaBubble);
	}

	private List<Intent> tapImage(byte[] content, String declared) {
		return tap(GroupTrBody.encodeImage(content, declared),
				R.id.imageView);
	}

	private static void nothingLeavesZerion(List<Intent> launched) {
		for (Intent i : launched) {
			assertFalse("handed to another app as " + i.getType(),
					Intent.ACTION_VIEW.equals(i.getAction()));
		}
	}

	private static String screen(Intent i) {
		return i.getComponent() == null ? String.valueOf(i.getAction())
				: i.getComponent().getClassName();
	}

	@Test
	public void aPlaylistPostedAsAVideoOpensNothing() {
		byte[] playlist = ("#EXTM3U\n#EXTINF:10,clip\n"
				+ "http://198.51.100.7/clip.mp4\n")
				.getBytes(StandardCharsets.UTF_8);
		List<Intent> launched = tapVideo(playlist, "video/mp4");
		assertEquals("a playlist was opened: " + launched, 0,
				launched.size());
	}

	@Test
	public void aRealVideoPlaysInZerionAndNeverInAnotherApp() {
		List<Intent> launched = tapVideo(mp4(), "video/mp4");
		nothingLeavesZerion(launched);
		assertEquals(1, launched.size());
		assertEquals(VideoPlayerActivity.class.getName(),
				screen(launched.get(0)));
	}

	@Test
	public void aPictureOpensInZerionsViewerAndNeverInAnotherApp() {
		List<Intent> launched = tapImage(jpeg(), "image/png");
		nothingLeavesZerion(launched);
		assertEquals(1, launched.size());
		assertEquals(ImageActivity.class.getName(), screen(launched.get(0)));
		assertEquals("only a token goes in the intent", 1,
				launched.get(0).getExtras().size());
	}

	private static String token(Intent i) {
		String key = i.getExtras().keySet().iterator().next();
		return i.getStringExtra(key);
	}

	@Test
	public void theViewerKeepsTheGroupsScreenshotBlock() {
		ProbeConversation.groupBlock = true;
		ProbeConversation.postId[0] = 1;
		assertTrue("the group's block is lost in the viewer",
				MediaViewer.secure(token(tapImage(jpeg(), "image/png")
						.get(0))));
		ProbeConversation.postId[0] = 2;
		assertTrue(MediaViewer.secure(token(tapVideo(mp4(), "video/mp4")
				.get(0))));
		ProbeConversation.groupBlock = false;
		ProbeConversation.postId[0] = 3;
		assertFalse(MediaViewer.secure(token(tapImage(jpeg(), "image/png")
				.get(0))));
	}

	@Test
	public void theViewerGoesWhenItsPostIsDeleted() throws Exception {
		ProbeConversation.postId = new byte[32];
		ProbeConversation.postId[0] = 5;
		String token = token(tapImage(jpeg(), "image/png").get(0));
		assertTrue(MediaViewer.holds(token));
		byte[] group = org.zerionproject.core.util.StringUtils
				.fromHexString(GROUP_HEX);
		com.professor.zerion.android.conversation.DeletedContentEvictor
				evictor = new com.professor.zerion.android.conversation
				.DeletedContentEvictor(new com.professor.zerion.android
				.conversation.voice.VoiceChunkAssembler(), () -> {
				});
		evictor.eventOccurred(new org.zerionproject.app.api.messaging.event
				.GroupTrPostsRemovedEvent(group,
				Collections.singletonList(new byte[32]), 0));
		assertTrue("another post's deletion closed the viewer",
				MediaViewer.holds(token));
		evictor.eventOccurred(new org.zerionproject.app.api.messaging.event
				.GroupTrPostsRemovedEvent(group, Collections.singletonList(
				ProbeConversation.postId.clone()), 0));
		assertFalse("the deleted post is still in the viewer",
				MediaViewer.holds(token));
	}

	@Test
	public void theViewerKeepsThePostsDisappearingTimer() {
		ProbeConversation.timerMs = 60_000L;
		String token = token(tapVideo(mp4(), "video/mp4").get(0));
		assertTrue(MediaViewer.holds(token));
		shadowOf(Looper.getMainLooper()).idleFor(
				java.time.Duration.ofSeconds(61));
		assertFalse("the video outlived its post's timer",
				MediaViewer.holds(token));
	}

	@Test
	public void aPlaylistPostedUnderAPlaylistTypeOpensNothing() {
		byte[] playlist = "#EXTM3U\nhttp://198.51.100.7/live.m3u8\n"
				.getBytes(StandardCharsets.UTF_8);
		List<Intent> launched = tapVideo(playlist, "audio/x-mpegurl");
		assertEquals("a playlist was opened: " + launched, 0,
				launched.size());
		assertTrue(tapImage(playlist, "audio/x-mpegurl").isEmpty());
	}
}
