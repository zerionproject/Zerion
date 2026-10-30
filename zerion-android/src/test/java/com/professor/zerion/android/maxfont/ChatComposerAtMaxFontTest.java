package com.professor.zerion.android.maxfont;

import android.app.Activity;
import android.view.View;

import com.professor.zerion.R;
import com.professor.zerion.android.view.TextInputView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ChatComposerAtMaxFontTest extends MaxFontTest {

	private static final float MIN_INPUT_DP = 208;
	private static final float MIN_NARROW_INPUT_DP = 168;
	private static final float MIN_ICON_DP = 48;
	private static final float MAX_ICON_STEP_DP = 56;
	private static final int[] ICONS = {R.id.secretNoteButton,
			R.id.attachmentButton, R.id.voiceButton};

	private static void assertRoomyComposer(Activity a) {
		assertRoomyComposer(a, MIN_INPUT_DP);
	}

	private static void assertRoomyComposer(Activity a, float minInputDp) {
		TextInputView bar = new TextInputView(a);
		layoutAtWidth(bar, screenWidth(a));
		float dp = dp(a);
		View input = bar.findViewById(R.id.input_text);
		float width = input.getWidth() / dp;
		assertTrue("the input is only " + width + "dp wide",
				width >= minInputDp);
		float previous = Float.NaN;
		float previousRight = Float.NaN;
		float previousLeft = Float.NaN;
		for (int id : ICONS) {
			View icon = bar.findViewById(id);
			assertTrue(icon + " is smaller than " + MIN_ICON_DP + "dp",
					icon.getWidth() >= Math.round(MIN_ICON_DP * dp)
							&& icon.getHeight() >= Math.round(MIN_ICON_DP * dp));
			float left = leftIn(icon, bar);
			float right = left + icon.getWidth();
			if (!Float.isNaN(previousRight)) {
				float gap = Math.max(left - previousRight,
						previousLeft - right);
				assertTrue(icon + " overlaps its neighbour", gap >= 0);
			}
			previousRight = right;
			previousLeft = left;
			assertTrue(icon + " leaves the screen", left >= 0
					&& left + icon.getWidth() <= bar.getWidth());
			float centre = (left + icon.getWidth() / 2f) / dp;
			if (!Float.isNaN(previous)) {
				float step = Math.abs(centre - previous);
				assertTrue("icons are " + step + "dp apart",
						step <= MAX_ICON_STEP_DP + 0.5f);
			}
			previous = centre;
		}
	}

	private static int leftIn(View v, View root) {
		int left = 0;
		for (View p = v; p != root; p = (View) p.getParent()) {
			left += p.getLeft();
		}
		return left;
	}

	@Test
	public void theInputIsWideAtTheLargestFont() {
		assertRoomyComposer(activity());
	}

	@Test
	public void theInputIsWideAtTheNormalFont() {
		assertRoomyComposer(normalActivity());
	}

	@Test
	@Config(qualifiers = "nl-w320dp-h640dp-xhdpi")
	public void theInputKeepsRoomOnANarrowScreen() {
		assertRoomyComposer(activity(), MIN_NARROW_INPUT_DP);
	}

	@Test
	@Config(qualifiers = "ar-w360dp-h740dp-xhdpi")
	public void theInputIsWideRightToLeft() {
		assertRoomyComposer(activity());
	}
}
