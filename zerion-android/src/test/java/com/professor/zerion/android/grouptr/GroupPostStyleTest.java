package com.professor.zerion.android.grouptr;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.CountingSecurePrefs;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectedLook;
import com.professor.zerion.android.selection.SelectionBarTest;
import com.professor.zerion.android.settings.ChatPreferences;
import com.professor.zerion.android.util.UiUtils;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrPost;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.widget.ImageViewCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.ContrastMath.over;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupPostStyleTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static Context ctx() {
		return new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
	}

	private static void prefs(String key, int value) {
		AppModule.getAndroidComponent(RuntimeEnvironment.getApplication())
				.securePreferences().edit().putInt(key, value).commit();
	}

	@After
	public void tearDown() {
		AppModule.getAndroidComponent(RuntimeEnvironment.getApplication())
				.securePreferences().edit()
				.remove(ChatPreferences.PREF_TEXT_SIZE)
				.remove(ChatPreferences.PREF_BUBBLE_COLOR).commit();
	}

	private static final class Stub implements GroupTrPostAdapter.Callback {
		private final boolean mine;
		private boolean selected = false;

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
			return null;
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
			return selected;
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

	private static GroupTrPostAdapter.PostHolder bound(boolean mine) {
		GroupTrPostAdapter adapter = new GroupTrPostAdapter(new Stub(mine));
		adapter.setPosts(Collections.singletonList(new GroupTrPost(
				new byte[32], new byte[32], "Alice",
				"hello".getBytes(StandardCharsets.UTF_8), 1000L, 1L, mine)));
		FrameLayout parent = new FrameLayout(ctx());
		GroupTrPostAdapter.PostHolder h = adapter.onCreateViewHolder(parent,
				adapter.getItemViewType(0));
		adapter.onBindViewHolder(h, 0);
		return h;
	}

	@Test
	public void groupPostsFollowTheMessageTextSize() {
		prefs(ChatPreferences.PREF_TEXT_SIZE,
				ChatPreferences.TEXT_SIZE_EXTRA_LARGE);
		Context ctx = ctx();
		float expected = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
				ChatPreferences.getMessageTextSizeSp(ctx),
				ctx.getResources().getDisplayMetrics());
		for (boolean mine : new boolean[] {true, false}) {
			TextView body = bound(mine).itemView.findViewById(R.id.postText);
			assertEquals("mine " + mine, expected, body.getTextSize(), 0.01f);
		}
	}

	@Test
	public void bindingManyPostsReadsTheSettingsOnce() throws Exception {
		GroupTrPostAdapter adapter = new GroupTrPostAdapter(new Stub(true));
		List<GroupTrPost> posts = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			posts.add(new GroupTrPost(new byte[32], new byte[32], "Alice",
					("post " + i).getBytes(StandardCharsets.UTF_8), 1000L + i,
					1L, true));
		}
		adapter.setPosts(posts);
		FrameLayout parent = new FrameLayout(ctx());
		try (CountingSecurePrefs counter = CountingSecurePrefs.install(
				RuntimeEnvironment.getApplication())) {
			for (int i = 0; i < posts.size(); i++) {
				GroupTrPostAdapter.PostHolder h = adapter.onCreateViewHolder(
						parent, adapter.getItemViewType(i));
				adapter.onBindViewHolder(h, i);
			}
			assertTrue("binding 20 posts read the settings "
					+ counter.reads() + " times", counter.reads() <= 2);
		}
	}

	@Test
	public void outgoingGroupPostsUseTheChosenBubbleColor() {
		prefs(ChatPreferences.PREF_BUBBLE_COLOR, ChatPreferences.BUBBLE_GREEN);
		Context ctx = ctx();
		int green = ctx.getColor(R.color.bubble_green);
		View out = bound(true).itemView.findViewById(R.id.bubble);
		assertEquals(green, SelectedLook.painted(out, false));
		View in = bound(false).itemView.findViewById(R.id.bubble);
		assertEquals(UiUtils.resolveColorAttribute(in.getContext(),
				R.attr.zerionColorBubbleIn), SelectedLook.painted(in, false));
	}

	private static byte[][] kinds() {
		return new byte[][] {GroupTrBody.encodeText("see https://example.org"),
				GroupTrBody.encodeReply(new byte[32], "agreed"),
				GroupTrBody.encodeVoice(new byte[8], 1000L),
				GroupTrBody.encodeImage(new byte[8], "image/png"),
				GroupTrBody.encodeVideo(new byte[8], "video/mp4", 1000L)};
	}

	private static GroupTrPostAdapter.PostHolder shown(boolean mine,
			byte[] body, boolean selected) {
		return shown(mine, body, selected,
				AppCompatDelegate.MODE_NIGHT_UNSPECIFIED);
	}

	private static GroupTrPostAdapter.PostHolder shown(boolean mine,
			byte[] body, boolean selected, int night) {
		Stub stub = new Stub(mine);
		stub.selected = selected;
		GroupTrPostAdapter adapter = new GroupTrPostAdapter(stub);
		adapter.setPosts(Collections.singletonList(new GroupTrPost(
				new byte[32], new byte[32], "Alice", body, 1000L, 1L, mine)));
		FrameLayout parent = new FrameLayout(SelectedLook.start(
				Robolectric.buildActivity(SelectionBarTest.Probe.class), night));
		GroupTrPostAdapter.PostHolder h = adapter.onCreateViewHolder(parent,
				adapter.getItemViewType(0));
		parent.addView(h.itemView);
		adapter.onBindViewHolder(h, 0);
		parent.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0,
				View.MeasureSpec.UNSPECIFIED));
		parent.layout(0, 0, parent.getMeasuredWidth(),
				parent.getMeasuredHeight());
		return h;
	}

	@Test
	public void everyKindOfSelectedPostTintsItsBubbleAndMovesNothing()
			throws Exception {
		prefs(ChatPreferences.PREF_BUBBLE_COLOR, ChatPreferences.BUBBLE_GREEN);
		for (boolean mine : new boolean[] {true, false}) {
			for (byte[] body : kinds()) {
				GroupTrPostAdapter.PostHolder plain = shown(mine, body, false);
				GroupTrPostAdapter.PostHolder picked = shown(mine, body, true);
				View row = picked.itemView;
				View bubble = SelectedLook.bubble(row);
				Context ctx = row.getContext();
				int base = mine ? ctx.getColor(R.color.bubble_green)
						: UiUtils.resolveColorAttribute(ctx,
						R.attr.zerionColorBubbleIn);
				int layer = UiUtils.resolveColorAttribute(ctx, mine
						? R.attr.zerionColorMessageSelectedOwn
						: R.attr.zerionColorMessageSelectedReceived);
				String what = (mine ? "mine " : "theirs ")
						+ bubble.getResources().getResourceEntryName(
						bubble.getId());
				assertNull(what, row.getBackground());
				assertFalse(what, SelectedLook.drawsSelected(plain.itemView));
				assertEquals(what, base, SelectedLook.painted(
						SelectedLook.bubble(plain.itemView)));
				assertTrue(what, SelectedLook.drawsSelected(row));
				assertTrue(what, SelectedLook.near(over(layer, base),
						SelectedLook.painted(bubble)));
				assertEquals(what, SelectedLook.strokeWidth(bubble, false),
						SelectedLook.strokeWidth(bubble, true));
				assertEquals(what, SelectedLook.boxes(plain.itemView),
						SelectedLook.boxes(row));
				for (int id : new int[] {R.id.imageView, R.id.videoThumb}) {
					ImageView media = row.findViewById(id);
					if (media == null) continue;
					assertEquals(what, UiUtils.resolveColorAttribute(ctx,
							R.attr.zerionColorMessageSelectedOwn),
							ImageViewCompat.getImageTintList(media)
									.getColorForState(media.getDrawableState(),
											0));
				}
			}
		}
	}

	private static List<String> unreadableWhenSelected(int night) {
		List<String> problems = new ArrayList<>();
		for (boolean mine : new boolean[] {true, false}) {
			for (byte[] body : kinds()) {
				problems.addAll(SelectedLook.unreadable(
						shown(mine, body, true, night).itemView, 4.5, 4.5));
			}
		}
		return problems;
	}

	@Test
	public void wordsOnASelectedPostStayReadableInLight() {
		List<String> problems = unreadableWhenSelected(
				AppCompatDelegate.MODE_NIGHT_NO);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void wordsOnASelectedPostStayReadableInDark() {
		List<String> problems = unreadableWhenSelected(
				AppCompatDelegate.MODE_NIGHT_YES);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}
}
