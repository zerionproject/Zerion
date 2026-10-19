package com.professor.zerion.android.util;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;

import com.professor.zerion.R;
import com.professor.zerion.android.ContrastMath;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ListDividerTest {

	private static final double VISIBLE = 1.1;

	private static void assertVisible(int... overlays) {
		Context ctx = new ContextThemeWrapper(
				RuntimeEnvironment.getApplication(), R.style.ZerionTheme);
		for (int o : overlays) ctx.getTheme().applyStyle(o, true);
		View divider = UiUtils.listDivider(ctx, 12);
		int color = ((ColorDrawable) divider.getBackground()).getColor();
		for (int surface : new int[] {
				UiUtils.resolveColorAttribute(ctx, R.attr.zerionColorBackground),
				UiUtils.resolveColorAttribute(ctx, R.attr.zerionColorSurface)}) {
			double r = ContrastMath.ratio(color, surface);
			assertTrue(String.format("divider %08x on %08x is %.2f:1", color,
					surface, r), r >= VISIBLE);
		}
		LinearLayout.LayoutParams lp =
				(LinearLayout.LayoutParams) divider.getLayoutParams();
		assertTrue(lp.height >= 1);
		assertEquals(12, lp.getMarginStart());
	}

	@Test
	@Config(qualifiers = "notnight")
	public void dividersShowInTheLightTheme() {
		assertVisible();
	}

	@Test
	@Config(qualifiers = "night")
	public void dividersShowInTheDarkThemes() {
		assertVisible();
		assertVisible(R.style.AmoledOverlay);
	}

	@Test
	@Config(qualifiers = "ldpi")
	public void dividersAreNeverZeroPixelsHigh() {
		assertVisible();
	}
}
