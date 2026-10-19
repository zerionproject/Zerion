package com.professor.zerion.android.maxfont;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ChannelComposerAtMaxFontTest extends MaxFontTest {

	private static final int MIN_INPUT_DP = 160;

	private static void assertComposerUsable() {
		Largest a = activity();
		View screen = LayoutInflater.from(a).inflate(
				R.layout.activity_channel_feed, null, false);
		ViewGroup bar = screen.findViewById(R.id.channelComposeBar);
		bar.setVisibility(View.VISIBLE);
		layoutIn(screen, screenWidth(a), a.getResources().getDisplayMetrics()
				.heightPixels);
		EditText input = screen.findViewById(R.id.channelComposeInput);
		float width = input.getWidth() / dp(a);
		assertTrue("the input is only " + width + "dp wide",
				width >= MIN_INPUT_DP);
		int room = input.getWidth() - input.getCompoundPaddingLeft()
				- input.getCompoundPaddingRight();
		for (String word : input.getHint().toString().split("\\s+")) {
			float w = RealText.width(input.getPaint(), word);
			assertTrue("\"" + word + "\" needs " + w + "px and has " + room,
					w <= room);
		}
		View send = screen.findViewById(R.id.channelComposeSendButton);
		assertEquals(a.getString(R.string.channels_compose_send),
				send.getContentDescription().toString());
		assertTrue(send.getWidth() >= Math.round(48 * dp(a))
				&& send.getHeight() >= Math.round(48 * dp(a)));
		for (int i = 0; i < bar.getChildCount(); i++) {
			View child = bar.getChildAt(i);
			if (child.getVisibility() == View.GONE) continue;
			assertTrue(child + " leaves the screen",
					child.getLeft() >= 0 && child.getRight() <= bar.getWidth());
		}
	}

	@Test
	public void theDutchComposerKeepsRoomToWrite() {
		assertComposerUsable();
	}

	@Test
	@Config(qualifiers = "de-w360dp-h740dp-xhdpi")
	public void theGermanComposerKeepsRoomToWrite() {
		assertComposerUsable();
	}

	@Test
	@Config(qualifiers = "en-w360dp-h740dp-xhdpi")
	public void theEnglishComposerKeepsRoomToWrite() {
		assertComposerUsable();
	}
}
