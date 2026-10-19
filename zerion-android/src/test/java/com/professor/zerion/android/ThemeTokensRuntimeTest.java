package com.professor.zerion.android;

import android.content.Context;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import com.professor.zerion.R;
import com.professor.zerion.android.util.UiUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ThemeTokensRuntimeTest {

	private static Context themed(int... overlays) {
		ContextThemeWrapper ctx = new ContextThemeWrapper(
				RuntimeEnvironment.getApplication(), R.style.ZerionTheme);
		for (int o : overlays) ctx.getTheme().applyStyle(o, true);
		return ctx;
	}

	private static int color(Context ctx, int attr) {
		TypedValue v = new TypedValue();
		assertTrue(ctx.getTheme().resolveAttribute(attr, v, true));
		return UiUtils.resolveColorAttribute(ctx, attr);
	}

	@Test
	@Config(qualifiers = "notnight")
	public void theLightThemeHasALightIncomingBubbleWithDarkText() {
		Context ctx = themed();
		assertEquals(0xFFF4F5F7, color(ctx, R.attr.zerionColorBackground));
		assertEquals(0xFFFFFFFF, color(ctx, R.attr.zerionColorBubbleIn));
		assertEquals(0xFF14151A, color(ctx, R.attr.zerionColorOnBubbleIn));
		assertEquals(0xFFE3E5EA, color(ctx, R.attr.zerionColorDivider));
	}

	@Test
	@Config(qualifiers = "night")
	public void theDarkThemeMatchesTheApprovedPalette() {
		Context ctx = themed();
		assertEquals(0xFF0F1014, color(ctx, R.attr.zerionColorBackground));
		assertEquals(0xFF1C1D22, color(ctx, R.attr.zerionColorSurface));
		assertEquals(0xFF2D2E36, color(ctx, R.attr.zerionColorDivider));
		assertEquals(0xFF26B7F0, color(ctx, R.attr.zerionColorAccent));
		assertEquals(0xFF007EA8, color(ctx, R.attr.zerionColorBubbleOut));
		assertEquals(0xFF2C2D32, color(ctx, R.attr.zerionColorBubbleIn));
		assertEquals(0xFFFFFFFF, color(ctx, R.attr.zerionColorOnBubbleIn));
		assertEquals(0xFF9CA3AF, color(ctx,
				R.attr.zerionColorOnSurfaceMuted));
		assertEquals(0xFFEF4444, color(ctx, R.attr.zerionColorDanger));
		assertEquals(0xFFF87171, color(ctx, R.attr.zerionColorDangerText));
	}

	@Test
	@Config(qualifiers = "night")
	public void theAmoledOverlayTurnsTheSurfacesBlack() {
		Context ctx = themed(R.style.AmoledOverlay);
		assertEquals(0xFF000000, color(ctx, R.attr.zerionColorBackground));
		assertEquals(0xFF0A0A0A, color(ctx, R.attr.zerionColorSurface));
	}

	@Test
	public void anAccentOverlayReachesTheAccentTokens() {
		Context ctx = themed(R.style.AccentOverlay_Violet);
		int violet = ctx.getColor(R.color.accent_violet);
		assertEquals(violet, color(ctx, R.attr.zerionColorAccent));
		assertEquals(0xFF000000, color(ctx, R.attr.zerionColorOnAccent));
		assertEquals(ctx.getColor(R.color.accent_violet_text),
				color(ctx, R.attr.zerionColorAccentText));
		assertEquals(ctx.getColor(R.color.accent_violet_text),
				color(ctx, R.attr.zerionColorAccentIcon));
	}

	@Test
	@Config(qualifiers = "notnight")
	public void theLightInsetAndOutlineDarkenSlightly() {
		Context ctx = themed();
		assertEquals(0x14000000, color(ctx, R.attr.zerionColorBubbleInInset));
		assertEquals(0xFFD5D8DE, color(ctx, R.attr.zerionColorOutline));
	}

	@Test
	@Config(qualifiers = "night")
	public void theDarkInsetDarkensAndTheOutlineIsHidden() {
		Context ctx = themed();
		assertEquals(0x59000000, color(ctx, R.attr.zerionColorBubbleInInset));
		assertEquals(0, color(ctx, R.attr.zerionColorOutline) >>> 24);
	}
}
