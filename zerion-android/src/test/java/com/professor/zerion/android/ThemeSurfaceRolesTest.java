package com.professor.zerion.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ThemeSurfaceRolesTest {

	private static final int[][] ROLES = {
			{com.google.android.material.R.attr.colorSurfaceVariant,
					R.color.zerion_surface_elevated},
			{com.google.android.material.R.attr.colorOnSurfaceVariant,
					R.color.zerion_text_secondary},
			{com.google.android.material.R.attr.colorOutline,
					R.color.zerion_text_secondary},
			{com.google.android.material.R.attr.colorOutlineVariant,
					R.color.zerion_divider},
			{com.google.android.material.R.attr.colorSurfaceContainerLowest,
					R.color.zerion_dark_bg},
			{com.google.android.material.R.attr.colorSurfaceContainerLow,
					R.color.zerion_card_bg},
			{com.google.android.material.R.attr.colorSurfaceContainer,
					R.color.zerion_card_bg},
			{com.google.android.material.R.attr.colorSurfaceContainerHigh,
					R.color.zerion_surface_elevated},
			{com.google.android.material.R.attr.colorSurfaceContainerHighest,
					R.color.zerion_surface_elevated},
			{com.google.android.material.R.attr.colorSecondaryContainer,
					R.color.zerion_selected_container},
	};
	private static final int[] ACCENTS = {0, R.style.AccentOverlay_Violet,
			R.style.AccentOverlay_Emerald, R.style.AccentOverlay_Amber,
			R.style.AccentOverlay_Rose, R.style.AccentOverlay_Blue};

	private static int resolve(Context ctx, int attr) {
		TypedValue v = new TypedValue();
		assertTrue(ctx.getTheme().resolveAttribute(attr, v, true));
		return v.data;
	}

	private static int resourceOf(Context ctx, int attr) {
		TypedValue v = new TypedValue();
		assertTrue(ctx.getTheme().resolveAttribute(attr, v, true));
		return v.resourceId;
	}

	private static double luminance(int c) {
		double[] ch = {Color.red(c) / 255.0, Color.green(c) / 255.0,
				Color.blue(c) / 255.0};
		for (int i = 0; i < 3; i++) {
			ch[i] = ch[i] <= 0.03928 ? ch[i] / 12.92
					: Math.pow((ch[i] + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
	}

	private static double contrast(int a, int b) {
		double la = luminance(a), lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static ContextThemeWrapper themed(int theme, int... overlays) {
		Context app = ApplicationProvider.getApplicationContext();
		ContextThemeWrapper ctx = new ContextThemeWrapper(app, theme);
		for (int o : overlays) {
			if (o != 0) ctx.getTheme().applyStyle(o, true);
		}
		return ctx;
	}

	private static List<String> mismatches(int theme) {
		Context ctx = themed(theme);
		List<String> out = new ArrayList<>();
		for (int[] role : ROLES) {
			int want = ctx.getColor(role[1]);
			int got = resolve(ctx, role[0]);
			if (want != got) {
				out.add(ctx.getResources().getResourceEntryName(role[0])
						+ String.format(" is #%08X, want #%08X", got, want));
			}
		}
		return out;
	}

	private static List<String> selectedProblems(String name, Context ctx) {
		List<String> out = new ArrayList<>();
		int selected = resolve(ctx,
				com.google.android.material.R.attr.colorSecondaryContainer);
		List<Integer> grounds = new ArrayList<>();
		grounds.add(resolve(ctx,
				com.google.android.material.R.attr.colorSurface));
		grounds.add(resolve(ctx,
				com.google.android.material.R.attr.colorSurfaceContainerHigh));
		TypedValue variant = new TypedValue();
		if (ctx.getTheme().resolveAttribute(R.attr.zerionColorSurfaceVariant,
				variant, true)) {
			grounds.add(variant.data);
		}
		for (int g : grounds) {
			if (contrast(selected, g) < 1.25) {
				out.add(name + String.format(": selected #%08X on #%08X",
						selected, g));
			}
		}
		int onSelected = resolve(ctx,
				com.google.android.material.R.attr.colorOnSecondaryContainer);
		if (contrast(onSelected, selected) < 4.5) {
			out.add(name + ": text on the selected fill is hard to read");
		}
		return out;
	}

	@Test
	public void sheetsMenusAndFieldsUseZerionSurfacesInTheLightTheme() {
		List<String> bad = mismatches(R.style.ZerionTheme);
		bad.addAll(mismatches(R.style.ZerionDialogTheme));
		assertEquals(String.join("\n", bad), 0, bad.size());
	}

	@Test
	public void sheetsMenusAndFieldsUseZerionSurfacesInTheDarkTheme() {
		RuntimeEnvironment.setQualifiers("+night");
		List<String> bad = mismatches(R.style.ZerionTheme);
		bad.addAll(mismatches(R.style.ZerionDialogTheme));
		assertEquals(String.join("\n", bad), 0, bad.size());
	}

	@Test
	public void theAmoledThemeKeepsItsSurfacesBlack() {
		RuntimeEnvironment.setQualifiers("+night");
		Context ctx = themed(R.style.ZerionTheme, R.style.AmoledOverlay);
		assertEquals(ctx.getColor(R.color.zerion_amoled_card), resolve(ctx,
				com.google.android.material.R.attr.colorSurfaceContainer));
		assertEquals(ctx.getColor(R.color.zerion_amoled_bg), resolve(ctx,
				com.google.android.material.R.attr.colorSurfaceContainerLowest));
		assertEquals(ctx.getColor(R.color.zerion_amoled_surface_variant),
				resolve(ctx, com.google.android.material.R.attr
						.colorSurfaceContainerHigh));
	}

	@Test
	public void aSelectedOptionStandsOutFromWhatItSitsOn() {
		List<String> bad = new ArrayList<>(selectedProblems("light",
				themed(R.style.ZerionTheme)));
		bad.addAll(selectedProblems("light dialog",
				themed(R.style.ZerionDialogTheme)));
		RuntimeEnvironment.setQualifiers("+night");
		bad.addAll(selectedProblems("dark", themed(R.style.ZerionTheme)));
		bad.addAll(selectedProblems("dark dialog",
				themed(R.style.ZerionDialogTheme)));
		bad.addAll(selectedProblems("amoled",
				themed(R.style.ZerionTheme, R.style.AmoledOverlay)));
		assertEquals(String.join("\n", bad), 0, bad.size());
	}

	private static List<String> dialogButtonProblems(String name) {
		List<String> out = new ArrayList<>();
		for (int accent : ACCENTS) {
			ContextThemeWrapper ctx = themed(R.style.ZerionTheme, accent);
			int overlay = resourceOf(ctx, com.google.android.material.R.attr
					.materialAlertDialogTheme);
			ContextThemeWrapper dialog = new ContextThemeWrapper(ctx, overlay);
			int ground = resolve(dialog, com.google.android.material.R.attr
					.colorSurfaceContainerHigh);
			for (int button : new int[] {androidx.appcompat.R.attr
					.buttonBarPositiveButtonStyle, androidx.appcompat.R.attr
					.buttonBarNegativeButtonStyle, androidx.appcompat.R.attr
					.buttonBarNeutralButtonStyle}) {
				int style = resourceOf(dialog, button);
				TypedArray a = dialog.obtainStyledAttributes(style,
						new int[] {android.R.attr.textColor});
				ColorStateList text = a.getColorStateList(0);
				a.recycle();
				int color = text.getColorForState(
						new int[] {android.R.attr.state_enabled},
						text.getDefaultColor());
				int accentText = resolve(ctx, R.attr.zerionColorAccentText);
				if (color != accentText || contrast(color, ground) < 4.5) {
					out.add(name + " accent " + accent + String.format(
							": #%08X on #%08X", color, ground));
				}
			}
		}
		return out;
	}

	private static List<String> tintProblems(String name) {
		List<String> out = new ArrayList<>();
		for (int accent : ACCENTS) {
			ContextThemeWrapper ctx = themed(R.style.ZerionTheme, accent);
			int overlay = resourceOf(ctx, com.google.android.material.R.attr
					.materialAlertDialogTheme);
			for (Context c : new Context[] {ctx,
					new ContextThemeWrapper(ctx, overlay),
					themed(R.style.ZerionDialogTheme, accent)}) {
				int surface = resolve(c,
						com.google.android.material.R.attr.colorSurface);
				int shown = new com.google.android.material.elevation
						.ElevationOverlayProvider(c)
						.compositeOverlayIfNeeded(surface, 24f);
				if (shown != surface) {
					out.add(name + " accent " + accent + String.format(
							": #%08X tinted to #%08X", surface, shown));
				}
			}
		}
		return out;
	}

	@Test
	public void raisedSurfacesKeepTheirOwnColorWithEveryAccent() {
		List<String> bad = new ArrayList<>(tintProblems("light"));
		RuntimeEnvironment.setQualifiers("+night");
		bad.addAll(tintProblems("dark"));
		assertEquals(String.join("\n", bad), 0, bad.size());
	}

	@Test
	public void theNeutralDialogButtonSitsAtTheDialogEdge() {
		ContextThemeWrapper ctx = themed(R.style.ZerionTheme);
		int overlay = resourceOf(ctx, com.google.android.material.R.attr
				.materialAlertDialogTheme);
		ContextThemeWrapper dialog = new ContextThemeWrapper(ctx, overlay);
		int neutral = resourceOf(dialog,
				androidx.appcompat.R.attr.buttonBarNeutralButtonStyle);
		TypedArray a = dialog.obtainStyledAttributes(neutral,
				new int[] {android.R.attr.layout_marginStart});
		int start = a.getDimensionPixelSize(0, -1);
		a.recycle();
		assertEquals(0, start);
	}

	@Test
	public void dialogButtonsAreReadableWithEveryAccent() {
		List<String> bad = new ArrayList<>(dialogButtonProblems("light"));
		RuntimeEnvironment.setQualifiers("+night");
		bad.addAll(dialogButtonProblems("dark"));
		assertEquals(String.join("\n", bad), 0, bad.size());
	}

	@Test
	public void toolbarMenusKeepTheZerionColors() {
		for (boolean night : new boolean[] {false, true}) {
			if (night) RuntimeEnvironment.setQualifiers("+night");
			Context ctx = themed(R.style.ZerionTheme, R.style.PopupMenu);
			assertEquals(ctx.getColor(R.color.zerion_card_bg), resolve(ctx,
					com.google.android.material.R.attr.colorSurfaceContainer));
			assertEquals(ctx.getColor(R.color.zerion_cyan), resolve(ctx,
					androidx.appcompat.R.attr.colorPrimary));
		}
	}
}
