package com.professor.zerion.android.maxfont;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class WrapRowMeasureCostTest extends MaxFontTest {

	private static final String POST = "Route for Saturday: we start at the "
			+ "community hall at nine, walk along the river to the old mill, "
			+ "stop for lunch at the bakery and finish at the station before "
			+ "the last train leaves for the city in the evening";

	private static void forceLayout(View v) {
		v.forceLayout();
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) forceLayout(g.getChildAt(i));
		}
	}

	@Test
	public void aGroupPostIsNotMeasuredWordByWordOnEveryPass() {
		for (Largest a : new Largest[] {activity(), normalActivity()}) {
			FrameLayout host = new FrameLayout(a);
			View row = LayoutInflater.from(a).inflate(
					R.layout.list_item_grouptr_post_in, host, false);
			host.addView(row);
			((TextView) row.findViewById(R.id.postText)).setText(POST);
			((TextView) row.findViewById(R.id.postTime)).setText(
					"Eergisteren, 23:56");
			int width = screenWidth(a);
			RealText.measureTextCalls = 0;
			layoutAtWidth(host, width);
			assertTrue("the first pass checks the words",
					RealText.measureTextCalls > 0);
			RealText.measureTextCalls = 0;
			for (int pass = 0; pass < 3; pass++) {
				forceLayout(host);
				layoutAtWidth(host, width);
			}
			assertEquals("the same post is measured word by word again", 0,
					RealText.measureTextCalls);
		}
	}
}
