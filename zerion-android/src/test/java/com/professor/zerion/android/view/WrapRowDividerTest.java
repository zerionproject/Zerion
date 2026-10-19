package com.professor.zerion.android.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.UNSPECIFIED;
import static android.view.View.MeasureSpec.makeMeasureSpec;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class WrapRowDividerTest {

	private static final class Counting extends ColorDrawable {

		int draws = 0;

		@Override
		public void draw(Canvas canvas) {
			draws++;
		}

		@Override
		public int getIntrinsicWidth() {
			return 2;
		}

		@Override
		public int getIntrinsicHeight() {
			return 2;
		}
	}

	private static final class Probe extends WrapRow {

		Probe(Context ctx) {
			super(ctx, null);
		}

		void drawNow(Canvas canvas) {
			onDraw(canvas);
		}
	}

	private static Probe row(Counting divider) {
		Context ctx = ApplicationProvider.getApplicationContext();
		Probe row = new Probe(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setDividerDrawable(divider);
		row.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
		for (String words : new String[] {"Route for Saturday morning",
				"We need drivers"}) {
			TextView t = new TextView(ctx);
			t.setText(words);
			t.setMaxLines(1);
			row.addView(t);
		}
		return row;
	}

	private static void layout(View v, int width) {
		v.measure(makeMeasureSpec(width, EXACTLY),
				makeMeasureSpec(0, UNSPECIFIED));
		v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
	}

	@Test
	public void aStackedRowDrawsNoHorizontalDividers() {
		Counting divider = new Counting();
		Probe row = row(divider);
		layout(row, 2000);
		assertFalse(row.isStacked());
		row.drawNow(new Canvas());
		assertTrue("a row with room draws its divider", divider.draws > 0);

		divider.draws = 0;
		layout(row, 40);
		assertTrue(row.isStacked());
		row.drawNow(new Canvas());

		assertEquals("a stacked row drew horizontal dividers", 0,
				divider.draws);
	}
}
