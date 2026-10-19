package com.professor.zerion.android.maxfont;

import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.appcompat.widget.ActionMenuView;
import androidx.appcompat.widget.Toolbar;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ChatHeaderAtMaxFontTest extends MaxFontTest {

	private static void assertInsideToolbar(Toolbar toolbar, TextView line) {
		String name = line.getText().toString();
		assertNotNull(name, line.getLayout());
		int needed = line.getCompoundPaddingTop()
				+ line.getLayout().getHeight()
				+ line.getCompoundPaddingBottom();
		assertTrue(name + " needs " + needed + "px and gets "
				+ line.getHeight(), line.getHeight() >= needed);
		int[] b = boundsIn(line, toolbar);
		assertTrue(name + " starts at " + b[1] + " above the header",
				b[1] >= 0);
		assertTrue(name + " ends at " + b[3] + " below the header of "
				+ toolbar.getHeight(), b[3] <= toolbar.getHeight());
		for (View p = (View) line.getParent(); p != toolbar;
				p = (View) p.getParent()) {
			int[] pb = boundsIn(p, toolbar);
			assertTrue(name + " is clipped by its block",
					b[1] >= pb[1] && b[3] <= pb[3]);
		}
	}

	private static Toolbar header(Largest a, int layout, int title,
			String titleText, int subtitle, String subtitleText) {
		View root = LayoutInflater.from(a).inflate(layout, null, false);
		((TextView) root.findViewById(title)).setText(titleText);
		((TextView) root.findViewById(subtitle)).setText(subtitleText);
		Toolbar toolbar = root.findViewById(R.id.toolbar);
		layoutIn(root, screenWidth(a), a.getResources().getDisplayMetrics()
				.heightPixels);
		return toolbar;
	}

	@Test
	public void theContactSubtitleIsWhollyVisible() {
		Largest a = activity();
		Toolbar toolbar = header(a, R.layout.activity_conversation,
				R.id.contactName, "PixelA", R.id.contactSubtitle,
				a.getString(R.string.conversation_subtitle_online));
		assertInsideToolbar(toolbar, toolbar.findViewById(R.id.contactName));
		assertInsideToolbar(toolbar,
				toolbar.findViewById(R.id.contactSubtitle));
	}

	@Test
	public void theGroupSubtitleIsWhollyVisible() {
		Largest a = activity();
		String subtitle = a.getResources().getQuantityString(
				R.plurals.grouptr_member_count, 2, 2) + " \u00b7 "
				+ a.getString(R.string.grouptr_subtitle_e2ee);
		Toolbar toolbar = header(a, R.layout.activity_grouptr_conversation,
				R.id.groupTitle, "GateGroup", R.id.groupSubtitle, subtitle);
		assertInsideToolbar(toolbar, toolbar.findViewById(R.id.groupTitle));
		assertInsideToolbar(toolbar, toolbar.findViewById(R.id.groupSubtitle));
	}

	@Test
	public void theChannelSubtitleIsWhollyVisible() {
		Largest a = activity();
		View root = LayoutInflater.from(a).inflate(
				R.layout.activity_channel_feed, null, false);
		Toolbar toolbar = root.findViewById(R.id.channelFeedToolbar);
		toolbar.setTitle("PhaseAopen");
		toolbar.setSubtitle(a.getString(R.string.channels_row_public_channel));
		layoutIn(root, screenWidth(a), a.getResources().getDisplayMetrics()
				.heightPixels);
		int found = 0;
		for (int i = 0; i < toolbar.getChildCount(); i++) {
			View child = toolbar.getChildAt(i);
			if (!(child instanceof TextView)) continue;
			TextView line = (TextView) child;
			if (line.getText().length() == 0) continue;
			found++;
			int needed = line.getCompoundPaddingTop()
					+ line.getLayout().getHeight()
					+ line.getCompoundPaddingBottom();
			assertTrue(line.getText() + " is squeezed",
					line.getHeight() >= needed);
			assertTrue(line.getText() + " is cut off", line.getTop() >= 0
					&& line.getBottom() <= toolbar.getHeight());
		}
		assertEquals(2, found);
	}

	@Test
	public void theChannelToolbarIconsStayWhereTheyWereAtNormalText() {
		Normal a = normalActivity();
		View root = LayoutInflater.from(a).inflate(
				R.layout.activity_channel_feed, null, false);
		Toolbar toolbar = root.findViewById(R.id.channelFeedToolbar);
		toolbar.setTitle("PhaseAopen");
		toolbar.setSubtitle(a.getString(R.string.channels_row_public_channel));
		toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
		toolbar.getMenu().add(R.string.channels_settings_title)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		layoutIn(root, screenWidth(a), a.getResources().getDisplayMetrics()
				.heightPixels);
		float density = dp(a);
		assertEquals(Math.round(64 * density), toolbar.getHeight());
		View navigation = null;
		View overflow = null;
		for (int i = 0; i < toolbar.getChildCount(); i++) {
			View child = toolbar.getChildAt(i);
			if (child instanceof ImageButton) navigation = child;
			if (child instanceof ActionMenuView) {
				overflow = ((ActionMenuView) child).getChildAt(0);
			}
		}
		assertNotNull(navigation);
		assertNotNull(overflow);
		int[] nav = boundsIn(navigation, toolbar);
		assertEquals("back arrow top", Math.round(4 * density), nav[1]);
		assertEquals("back arrow bottom", Math.round(60 * density), nav[3]);
		int[] more = boundsIn(overflow, toolbar);
		assertEquals("menu top", Math.round(8 * density), more[1]);
		assertEquals("menu bottom", Math.round(56 * density), more[3]);
	}
}
