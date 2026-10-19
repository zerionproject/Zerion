package com.professor.zerion.android.maxfont;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class GroupBubbleTimeAtMaxFontTest extends MaxFontTest {

	private static final int LIST_PADDING_DP = 8;

	private static View bubble(Largest a, String text, String time) {
		FrameLayout host = new FrameLayout(a);
		View row = LayoutInflater.from(a).inflate(
				R.layout.list_item_grouptr_post_in, host, false);
		host.addView(row);
		((TextView) row.findViewById(R.id.senderName)).setText("PixelA");
		((TextView) row.findViewById(R.id.postText)).setText(text);
		((TextView) row.findViewById(R.id.postTime)).setText(time);
		int width = screenWidth(a) - Math.round(2 * LIST_PADDING_DP * dp(a));
		layoutAtWidth(host, width);
		return row;
	}

	private static void assertTimeShown(Largest a, String text, String time) {
		View row = bubble(a, text, time);
		View bubble = row.findViewById(R.id.bubble);
		TextView timeView = row.findViewById(R.id.postTime);
		TextView textView = row.findViewById(R.id.postText);
		float needed = RealText.width(timeView.getPaint(), time);
		int room = timeView.getWidth() - timeView.getCompoundPaddingLeft()
				- timeView.getCompoundPaddingRight();
		assertTrue("\"" + time + "\" needs " + needed + "px and has " + room,
				needed <= room);
		assertEquals(time + " wraps", 1,
				timeView.getLayout().getLineCount());
		assertEquals(time + " loses its end", time.length(),
				timeView.getLayout().getLineEnd(0));
		assertNull(brokenWord(textView));
		int[] t = boundsIn(timeView, bubble);
		int[] w = boundsIn(textView, bubble);
		assertTrue(time + " sticks out of the bubble", t[0] >= 0
				&& t[2] <= bubble.getWidth() - bubble.getPaddingRight()
				&& t[3] <= bubble.getHeight());
		boolean beside = t[0] >= w[2];
		boolean below = t[1] >= w[3];
		assertTrue(time + " overlaps the words", beside || below);
		assertTrue("the bubble leaves the row",
				boundsIn(bubble, row)[2] <= row.getWidth());
	}

	@Test
	public void theTimeOfAShortPostIsNeverClipped() {
		Largest a = activity();
		assertTimeShown(a, "gatefrompixel", "Eergisteren, 23:56");
		assertTimeShown(a, "B2 group post", "Gisteren, 13:46");
		assertTimeShown(a, "b9pixelpost", "7 uur geleden");
	}

	@Test
	public void theTimeOfALongPostMovesUnderTheWords() {
		Largest a = activity();
		String text = "Route for Saturday: we start at the community hall "
				+ "and finish at the station";
		assertTimeShown(a, text, "Eergisteren, 23:56");
		View row = bubble(a, text, "Eergisteren, 23:56");
		int[] t = boundsIn(row.findViewById(R.id.postTime), row);
		int[] w = boundsIn(row.findViewById(R.id.postText), row);
		assertTrue("the time sits under the words", t[1] >= w[3]);
	}

	@Test
	public void aShortTimeStaysBesideAShortPost() {
		Largest a = activity();
		View row = bubble(a, "hoi", "10:00");
		int[] t = boundsIn(row.findViewById(R.id.postTime), row);
		int[] w = boundsIn(row.findViewById(R.id.postText), row);
		assertTrue("the time stays on the line of the words", t[0] >= w[2]
				&& t[1] < w[3]);
	}
}
