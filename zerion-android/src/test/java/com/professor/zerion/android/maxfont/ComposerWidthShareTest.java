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
public class ComposerWidthShareTest extends MaxFontTest {

	private static final float MIN_BOX_SHARE = 0.80f;
	private static final float MIN_TYPING_SHARE = 0.55f;
	private static final float MAX_OUTSIDE_DP = 56;

	private static TextInputView bar(Activity a) {
		TextInputView bar = new TextInputView(a);
		layoutAtWidth(bar, screenWidth(a));
		return bar;
	}

	private static float share(View v, View bar) {
		return v.getWidth() / (float) bar.getWidth();
	}

	private static void assertShares(Activity a) {
		TextInputView bar = bar(a);
		View box = bar.findViewById(R.id.inputBox);
		View typing = bar.findViewById(R.id.input_text);
		float boxShare = share(box, bar);
		float typingShare = share(typing, bar);
		assertTrue("the text box takes " + Math.round(boxShare * 100)
				+ " percent of the row", boxShare >= MIN_BOX_SHARE);
		assertTrue("the typing area takes " + Math.round(typingShare * 100)
				+ " percent of the row", typingShare >= MIN_TYPING_SHARE);
		float outside = (bar.getWidth() - box.getWidth()) / dp(a);
		assertTrue("the send button and padding take " + outside + "dp",
				outside <= MAX_OUTSIDE_DP + 0.5f);
		int[] attachment = boundsIn(bar.findViewById(R.id.attachmentButton),
				bar);
		int[] note = boundsIn(bar.findViewById(R.id.secretNoteButton), bar);
		int[] boxBounds = boundsIn(box, bar);
		assertTrue("the attachment icon is outside the box",
				attachment[0] >= boxBounds[0] && attachment[2] <= boxBounds[2]);
		assertTrue("the secret note icon is outside the box",
				note[0] >= boxBounds[0] && note[2] <= boxBounds[2]);
		int[] text = boundsIn(typing, bar);
		assertTrue("the icons overlap the typing area",
				text[2] <= Math.min(attachment[0], note[0])
						|| text[0] >= Math.max(attachment[2], note[2]));
	}

	@Test
	public void theTextBoxTakesFourFifthsOfTheRowOnA360dpPhone() {
		assertShares(normalActivity());
	}

	@Test
	@Config(qualifiers = "nl-w412dp-h915dp-xhdpi")
	public void theTextBoxTakesFourFifthsOfTheRowOnA412dpPhone() {
		assertShares(normalActivity());
	}

	@Test
	@Config(qualifiers = "ar-w360dp-h740dp-xhdpi")
	public void theSharesHoldRightToLeft() {
		assertShares(activity());
	}

	@Test
	public void theSharesHoldAtTheLargestFont() {
		assertShares(activity());
	}
}
