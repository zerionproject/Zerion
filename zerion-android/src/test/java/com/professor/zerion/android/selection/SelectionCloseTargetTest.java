package com.professor.zerion.android.selection;

import android.os.Looper;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.util.EnumSet;
import java.util.Set;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, qualifiers = "w411dp-h915dp-xxhdpi")
public class SelectionCloseTargetTest {

	@Test
	public void theClearSelectionCrossIsAtLeast48dpToTouch() {
		SelectionBarTest.Probe activity = Robolectric.buildActivity(
				SelectionBarTest.Probe.class).setup().get();
		SelectionBar bar = new SelectionBar(activity, new SelectionBar.Host() {
			@Override
			public Set<SelectionAction> offeredActions() {
				return EnumSet.of(SelectionAction.COPY);
			}

			@Override
			public void onSelectionAction(SelectionAction action) {
			}

			@Override
			public void onSelectionClosed() {
			}
		});
		bar.show(1);
		shadowOf(Looper.getMainLooper()).idle();
		View decor = activity.getWindow().getDecorView();
		int width = activity.getResources().getDisplayMetrics().widthPixels;
		int height = activity.getResources().getDisplayMetrics().heightPixels;
		decor.measure(View.MeasureSpec.makeMeasureSpec(width,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				height, View.MeasureSpec.EXACTLY));
		decor.layout(0, 0, width, height);
		View close = decor.findViewById(
				androidx.appcompat.R.id.action_mode_close_button);
		assertNotNull(close);
		float density = activity.getResources().getDisplayMetrics().density;
		int min = Math.round(48 * density);
		assertTrue("the cross is " + close.getWidth() / density + "x"
						+ close.getHeight() / density + "dp",
				close.getWidth() >= min && close.getHeight() >= min);
	}
}
