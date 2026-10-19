package com.professor.zerion.android.selection;

import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.maxfont.MaxFontTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
public class SelectionBarCoversHeaderTest extends MaxFontTest {

	public static class LargeChat extends Largest {

		@Override
		protected void onCreate(Bundle state) {
			super.onCreate(state);
			setContentView(R.layout.activity_conversation);
			((TextView) findViewById(R.id.contactName)).setText("PixelA");
			((TextView) findViewById(R.id.contactSubtitle)).setText(
					R.string.conversation_subtitle_online);
		}
	}

	public static class LargeGroup extends Largest {

		@Override
		protected void onCreate(Bundle state) {
			super.onCreate(state);
			setContentView(R.layout.activity_grouptr_conversation);
			((TextView) findViewById(R.id.groupTitle)).setText("GateGroup");
			((TextView) findViewById(R.id.groupSubtitle)).setText(
					getResources().getQuantityString(
							R.plurals.grouptr_member_count, 2, 2));
		}
	}

	public static class LargeFeed extends Largest {

		@Override
		protected void onCreate(Bundle state) {
			super.onCreate(state);
			setContentView(R.layout.activity_channel_feed);
			Toolbar toolbar = findViewById(R.id.channelFeedToolbar);
			toolbar.setTitle("PhaseAopen");
			toolbar.setSubtitle(R.string.channels_row_public_channel);
		}
	}

	public static class NormalChat extends Normal {

		@Override
		protected void onCreate(Bundle state) {
			super.onCreate(state);
			setContentView(R.layout.activity_conversation);
			((TextView) findViewById(R.id.contactName)).setText("PixelA");
			((TextView) findViewById(R.id.contactSubtitle)).setText(
					R.string.conversation_subtitle_online);
		}
	}

	public static class NormalFeed extends Normal {

		@Override
		protected void onCreate(Bundle state) {
			super.onCreate(state);
			setContentView(R.layout.activity_channel_feed);
			Toolbar toolbar = findViewById(R.id.channelFeedToolbar);
			toolbar.setTitle("PhaseAopen");
			toolbar.setSubtitle(R.string.channels_row_public_channel);
		}
	}

	private static final SelectionBar.Host HOST = new SelectionBar.Host() {

		@Override
		public Set<SelectionAction> offeredActions() {
			return EnumSet.of(SelectionAction.REPLY, SelectionAction.COPY,
					SelectionAction.DELETE);
		}

		@Override
		public void onSelectionAction(SelectionAction action) {
		}

		@Override
		public void onSelectionClosed() {
		}
	};

	private static void layout(AppCompatActivity a) {
		View decor = a.getWindow().getDecorView();
		DisplayMetrics m = a.getResources().getDisplayMetrics();
		decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				m.heightPixels, View.MeasureSpec.EXACTLY));
		decor.layout(0, 0, m.widthPixels, m.heightPixels);
	}

	private static int[] selecting(AppCompatActivity a, int header) {
		layout(a);
		new SelectionBar(a, HOST).show(1);
		shadowOf(Looper.getMainLooper()).idle();
		layout(a);
		View bar = a.findViewById(androidx.appcompat.R.id.action_mode_bar);
		View top = a.findViewById(header);
		assertEquals(View.VISIBLE, bar.getVisibility());
		int[] b = new int[2];
		int[] h = new int[2];
		bar.getLocationInWindow(b);
		top.getLocationInWindow(h);
		return new int[] {b[1], b[1] + bar.getHeight(), h[1],
				h[1] + top.getHeight()};
	}

	private static int themeHeight(AppCompatActivity a) {
		TypedArray t = a.obtainStyledAttributes(null,
				androidx.appcompat.R.styleable.ActionMode,
				androidx.appcompat.R.attr.actionModeStyle, 0);
		try {
			return t.getLayoutDimension(
					androidx.appcompat.R.styleable.ActionMode_height, 0);
		} finally {
			t.recycle();
		}
	}

	private static void assertCovers(AppCompatActivity a, int header,
			String screen) {
		int[] p = selecting(a, header);
		assertTrue(screen + " header is no taller than the bar, so this "
						+ "proves nothing", p[3] - p[2] > themeHeight(a));
		assertTrue(screen + " header starts above the bar",
				p[0] <= p[2]);
		assertTrue(screen + " header ends at " + p[3]
				+ " below the bar at " + p[1], p[1] >= p[3]);
	}

	@Test
	public void theBarCoversATallChatHeader() {
		assertCovers(Robolectric.buildActivity(LargeChat.class).setup().get(),
				R.id.toolbar, "chat");
	}

	@Test
	public void theBarCoversATallGroupHeader() {
		assertCovers(Robolectric.buildActivity(LargeGroup.class).setup().get(),
				R.id.toolbar, "group");
	}

	@Test
	public void theBarCoversATallChannelHeader() {
		assertCovers(Robolectric.buildActivity(LargeFeed.class).setup().get(),
				R.id.channelFeedToolbar, "channel");
	}

	private static int layoutListeners(View v) throws Exception {
		java.lang.reflect.Field info = View.class.getDeclaredField(
				"mListenerInfo");
		info.setAccessible(true);
		Object listeners = info.get(v);
		if (listeners == null) return 0;
		java.lang.reflect.Field field = listeners.getClass().getDeclaredField(
				"mOnLayoutChangeListeners");
		field.setAccessible(true);
		java.util.List<?> list = (java.util.List<?>) field.get(listeners);
		return list == null ? 0 : list.size();
	}

	@Test
	public void theBarFollowsAHeaderThatGrowsWhileSelecting() throws Exception {
		NormalChat a = Robolectric.buildActivity(NormalChat.class).setup()
				.get();
		settle();
		View decor = a.getWindow().getDecorView();
		int[] layouts = {0};
		decor.getViewTreeObserver().addOnGlobalLayoutListener(
				() -> layouts[0]++);
		View header = a.findViewById(R.id.toolbar);
		int listening = layoutListeners(header);
		SelectionBar selection = new SelectionBar(a, HOST);
		selection.show(1);
		settle();
		View bar = a.findViewById(androidx.appcompat.R.id.action_mode_bar);
		int themed = themeHeight(a);
		assertEquals(themed, bar.getHeight());
		TextView subtitle = a.findViewById(R.id.contactSubtitle);
		subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f);
		settle();
		assertTrue("the header did not grow", header.getHeight() > themed);
		assertTrue("the header ends " + header.getHeight()
						+ " below the bar of " + bar.getHeight(),
				bar.getHeight() >= header.getHeight());
		int settled = layouts[0];
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
		assertEquals("the window keeps laying out", settled, layouts[0]);
		subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
		settle();
		assertEquals("the header did not shrink", themed, header.getHeight());
		assertEquals("the bar stayed tall", themed, bar.getHeight());
		selection.close();
		settle();
		assertEquals("the closed bar still listens to the header", listening,
				layoutListeners(header));
	}

	private static void settle() {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
	}

	@Test
	public void normalTextKeepsTheBarAsItWas() {
		for (AppCompatActivity a : new AppCompatActivity[] {
				Robolectric.buildActivity(NormalChat.class).setup().get(),
				Robolectric.buildActivity(NormalFeed.class).setup().get()}) {
			int header = a instanceof NormalChat ? R.id.toolbar
					: R.id.channelFeedToolbar;
			int[] p = selecting(a, header);
			assertEquals(themeHeight(a), p[1] - p[0]);
			assertEquals("the bar moved", p[2], p[0]);
		}
	}
}
