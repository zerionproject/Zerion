package com.professor.zerion.android.conversation;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.selection.SelectionBarTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, qualifiers = "w411dp-h915dp-xxhdpi")
public class ReactionBubbleLayoutTest {

	private static final class Bubble {

		final int height;
		final int textTop;
		final int timeTop;

		Bubble(int height, int textTop, int timeTop) {
			this.height = height;
			this.textTop = textTop;
			this.timeTop = timeTop;
		}
	}

	private static Bubble measure(int layout, boolean reaction) {
		SelectionBarTest.Probe activity = Robolectric.buildActivity(
				SelectionBarTest.Probe.class).setup().get();
		FrameLayout host = new FrameLayout(activity);
		View row = LayoutInflater.from(activity).inflate(layout, host, false);
		host.addView(row);
		TextView text = row.findViewById(R.id.text);
		text.setText("night test moto to pixel");
		((TextView) row.findViewById(R.id.time)).setText("16 hr ago");
		TextView reactions = row.findViewById(R.id.reactionsView);
		reactions.setText("👍");
		reactions.setVisibility(reaction ? View.VISIBLE : View.GONE);
		int width = activity.getResources().getDisplayMetrics().widthPixels;
		host.measure(View.MeasureSpec.makeMeasureSpec(width,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0,
				View.MeasureSpec.UNSPECIFIED));
		host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());
		View bubble = row.findViewById(R.id.layout);
		return new Bubble(bubble.getHeight(), text.getTop(),
				row.findViewById(R.id.statusLayout).getTop());
	}

	private static void assertOnlyTheChipRowIsAdded(int layout) {
		float density = Robolectric.buildActivity(SelectionBarTest.Probe.class)
				.setup().get().getResources().getDisplayMetrics().density;
		Bubble plain = measure(layout, false);
		Bubble reacted = measure(layout, true);
		int added = reacted.height - plain.height;
		assertTrue("a reaction adds " + added / density + "dp",
				added > 0 && added <= Math.round(32 * density));
		assertEquals("the words stay where they were", plain.textTop,
				reacted.textTop);
		assertEquals("the time stays under the words", plain.timeTop,
				reacted.timeTop);
	}

	@Test
	public void aReactionOnAnIncomingMessageOnlyAddsItsRow() {
		assertOnlyTheChipRowIsAdded(R.layout.list_item_conversation_msg_in);
	}

	@Test
	public void aReactionOnMyMessageOnlyAddsItsRow() {
		assertOnlyTheChipRowIsAdded(R.layout.list_item_conversation_msg_out);
	}
}
