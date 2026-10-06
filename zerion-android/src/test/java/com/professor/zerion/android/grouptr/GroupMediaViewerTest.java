package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.webkit.MimeTypeMap;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
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
import static org.junit.Assert.assertNull;
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
		static byte[] videoBody = new byte[0];

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			GroupTrManager m = mock(GroupTrManager.class);
			GroupTrState state = mock(GroupTrState.class);
			when(state.getName()).thenReturn("Group");
			GroupTrPost post = new GroupTrPost(new byte[32], new byte[32],
					"Member", videoBody, System.currentTimeMillis(), 1L,
					false);
			try {
				when(m.getGroup(any())).thenReturn(state);
				when(m.getRecentPosts(any()))
						.thenReturn(Collections.singletonList(post));
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

	private Intent tapVideo(byte[] content, String declared) {
		ProbeConversation.videoBody =
				GroupTrBody.encodeVideo(content, declared, 1000L);
		Intent open = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		open.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		ProbeConversation a = Robolectric.buildActivity(
				ProbeConversation.class, open).setup().get();
		idle();
		for (Runnable r : new ArrayList<>(ProbeConversation.io)) {
			try {
				r.run();
			} catch (RuntimeException ignored) {
			}
		}
		idle();
		RecyclerView list = a.findViewById(R.id.postsRecycler);
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);
		RecyclerView.ViewHolder holder =
				list.findViewHolderForAdapterPosition(0);
		assertNotNull("the video post is shown", holder);
		while (shadowOf(a).getNextStartedActivity() != null) {
			idle();
		}
		try (MockedStatic<FileProvider> provider =
				mockStatic(FileProvider.class)) {
			provider.when(() -> FileProvider.getUriForFile(any(),
					anyString(), any(File.class))).thenAnswer(inv ->
					Uri.parse("content://" + inv.getArgument(1) + "/staged/"
							+ ((File) inv.getArgument(2)).getName()));
			holder.itemView.findViewById(R.id.mediaBubble).performClick();
			idle();
			for (Runnable r : new ArrayList<>(ProbeConversation.io)) {
				try {
					r.run();
				} catch (RuntimeException ignored) {
				}
			}
			idle();
		}
		Intent launched;
		while ((launched = shadowOf(a).getNextStartedActivity()) != null) {
			if (Intent.ACTION_VIEW.equals(launched.getAction())) {
				return launched;
			}
		}
		return null;
	}

	@Test
	public void aPlaylistPostedAsAVideoOpensNothing() {
		byte[] playlist = ("#EXTM3U\n#EXTINF:10,clip\n"
				+ "http://198.51.100.7/clip.mp4\n")
				.getBytes(StandardCharsets.UTF_8);
		Intent launched = tapVideo(playlist, "video/mp4");
		assertNull("a playlist was handed to a player as "
				+ (launched == null ? "" : launched.getType()), launched);
	}

	@Test
	public void aRealVideoStillOpensInAPlayer() {
		byte[] mp4 = concat(box("ftyp", "isom\0\0\2\0isommp41"
						.getBytes(StandardCharsets.ISO_8859_1)),
				box("moov", box("mvhd", new byte[100])),
				box("mdat", new byte[64]));
		Intent launched = tapVideo(mp4, "video/mp4");
		assertNotNull("a real video must still open", launched);
		assertEquals(Intent.ACTION_VIEW, launched.getAction());
		assertEquals("video/mp4", launched.getType());
		assertTrue(launched.getData().getPath().endsWith(".mp4"));
	}

	@Test
	public void aPlaylistPostedUnderAPlaylistTypeIsShownAsTextOnly() {
		byte[] playlist = "#EXTM3U\nhttp://198.51.100.7/live.m3u8\n"
				.getBytes(StandardCharsets.UTF_8);
		Intent launched = tapVideo(playlist, "audio/x-mpegurl");
		assertNotNull(launched);
		assertEquals("text/plain", launched.getType());
		String path = launched.getData().getPath();
		assertFalse("a playlist was staged as a video file: " + path,
				path.endsWith(".mp4"));
		assertTrue(path, path.endsWith(".txt"));
	}
}
