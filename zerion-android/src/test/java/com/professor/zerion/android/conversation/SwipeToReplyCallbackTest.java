package com.professor.zerion.android.conversation;

import android.content.Context;
import android.view.ContextThemeWrapper;

import com.professor.zerion.R;
import com.professor.zerion.android.ContrastMath;
import com.professor.zerion.android.util.UiUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SwipeToReplyCallbackTest {

	private static void assertReadable(int... overlays) {
		Context ctx = new ContextThemeWrapper(
				RuntimeEnvironment.getApplication(), R.style.ZerionTheme);
		for (int o : overlays) ctx.getTheme().applyStyle(o, true);
		int icon = SwipeToReplyCallback.replyIconTint(ctx);
		int screen = UiUtils.resolveColorAttribute(ctx,
				R.attr.zerionColorBackground);
		double r = ContrastMath.ratio(icon, screen);
		assertTrue(String.format("reply icon %08x on %08x is %.2f:1", icon,
				screen, r), r >= 3.0);
	}

	@Test
	@Config(qualifiers = "notnight")
	public void theReplyIconShowsInTheLightTheme() {
		assertReadable();
	}

	@Test
	@Config(qualifiers = "night")
	public void theReplyIconShowsInTheDarkThemes() {
		assertReadable();
		assertReadable(R.style.AmoledOverlay);
	}
}
