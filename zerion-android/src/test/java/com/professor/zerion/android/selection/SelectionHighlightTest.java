package com.professor.zerion.android.selection;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;

import com.professor.zerion.R;
import com.professor.zerion.android.util.UiUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

import androidx.core.content.ContextCompat;

import static com.professor.zerion.android.ContrastMath.over;
import static com.professor.zerion.android.ContrastMath.ratio;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SelectionHighlightTest {

	private static final double DISTINCT = 1.25;
	private static final double LIGHT_DISTINCT = 1.45;
	private static final double RESTRAINED = 1.6;
	private static final double TEXT = 4.5;
	private static final int[] ACCENTS = {0, R.style.AccentOverlay_Violet,
			R.style.AccentOverlay_Emerald, R.style.AccentOverlay_Amber,
			R.style.AccentOverlay_Rose, R.style.AccentOverlay_Blue};
	private static final int[] OWN_COLORS = {R.color.bubble_blue,
			R.color.bubble_purple, R.color.bubble_green, R.color.bubble_orange,
			R.color.bubble_pink, R.color.bubble_cyan};
	private static final int[] SELECTED = {android.R.attr.state_activated};
	private static final int[][] SELECTABLE = {
			{R.color.selectable_on_bubble_in_muted,
					R.attr.zerionColorOnBubbleInMuted},
			{R.color.selectable_on_surface_muted,
					R.attr.zerionColorOnSurfaceMuted},
			{R.color.selectable_accent_text, R.attr.zerionColorAccentText},
			{R.color.selectable_accent_icon, R.attr.zerionColorAccentIcon}};

	private static Context themed(boolean amoled, int accent) {
		ContextThemeWrapper ctx = new ContextThemeWrapper(
				RuntimeEnvironment.getApplication(), R.style.ZerionTheme);
		if (amoled) ctx.getTheme().applyStyle(R.style.AmoledOverlay, true);
		if (accent != 0) ctx.getTheme().applyStyle(accent, true);
		return ctx;
	}

	private static int color(Context ctx, int attr) {
		return UiUtils.resolveColorAttribute(ctx, attr);
	}

	private static void quiet(List<String> problems, String where,
			String what, int selected, int normal) {
		double r = ratio(selected, normal);
		if (r > RESTRAINED) {
			problems.add(String.format(Locale.ROOT,
					"%s %s #%08X on #%08X is %.2f:1, louder than %.1f:1", where,
					what, selected, normal, r, RESTRAINED));
		}
	}

	private static void need(List<String> problems, String where, String what,
			int fg, int bg, double min) {
		double r = ratio(fg, bg);
		if (r < min) {
			problems.add(String.format(Locale.ROOT,
					"%s %s #%08X on #%08X is %.2f:1", where, what, fg, bg, r));
		}
	}

	private static List<String> check(String mode, boolean amoled) {
		List<String> problems = new ArrayList<>();
		double distinct = mode.equals("light") ? LIGHT_DISTINCT : DISTINCT;
		for (int accent : ACCENTS) {
			Context ctx = themed(amoled, accent);
			String where = mode + " " + accent;
			int fg = color(ctx, R.attr.zerionColorSelectionForeground);
			int received = color(ctx, R.attr.zerionColorBubbleIn);
			int receivedLayer = color(ctx,
					R.attr.zerionColorMessageSelectedReceived);
			int card = color(ctx, R.attr.zerionColorSurface);
			int cardLayer = color(ctx, R.attr.zerionColorChannelCardSelected);
			int ownLayer = color(ctx, R.attr.zerionColorMessageSelectedOwn);
			for (int layer : new int[] {receivedLayer, cardLayer, ownLayer}) {
				int alpha = layer >>> 24;
				if (alpha == 0 || alpha > 0x50) {
					problems.add(where + String.format(Locale.ROOT,
							" layer #%08X is not a restrained tint", layer));
				}
			}
			int selectedIn = over(receivedLayer, received);
			need(problems, where, "received fill", selectedIn, received,
					distinct);
			quiet(problems, where, "received fill", selectedIn, received);
			int selectedCard = over(cardLayer, card);
			need(problems, where, "card fill", selectedCard, card, distinct);
			quiet(problems, where, "card fill", selectedCard, card);
			for (int fill : new int[] {selectedIn, selectedCard}) {
				int blue = fill & 0xFF;
				int red = (fill >>> 16) & 0xFF;
				if (mode.equals("light") && blue - red < 24) {
					problems.add(where + String.format(Locale.ROOT,
							" selected fill #%08X is grey, not a cool tint",
							fill));
				}
			}
			int edge = over(receivedLayer, color(ctx,
					R.attr.zerionColorOutline));
			if ((color(ctx, R.attr.zerionColorOutline) >>> 24) != 0) {
				need(problems, where, "bubble edge", edge, selectedIn, 1.25);
			}
			int chipFill = color(ctx, R.attr.zerionColorSurfaceVariant);
			int selectedChip = over(cardLayer, chipFill);
			need(problems, where, "selected chip", selectedChip, chipFill,
					DISTINCT);
			quiet(problems, where, "selected chip", selectedChip, chipFill);
			need(problems, where, "selection text on a chip", fg,
					selectedChip, TEXT);
			need(problems, where, "chip text",
					color(ctx, R.attr.zerionColorOnSurface), selectedChip,
					TEXT);
			int insetIn = over(color(ctx, R.attr.zerionColorBubbleInInset),
					selectedIn);
			for (int bg : new int[] {selectedIn, insetIn}) {
				need(problems, where, "selection text", fg, bg, TEXT);
				need(problems, where, "message text",
						color(ctx, R.attr.zerionColorOnBubbleIn), bg, TEXT);
			}
			int chip = color(ctx, R.attr.zerionColorSurfaceVariant);
			need(problems, where, "selection text", fg, selectedCard, TEXT);
			need(problems, where, "card text",
					color(ctx, R.attr.zerionColorOnSurface), selectedCard,
					TEXT);
			need(problems, where, "chip text",
					color(ctx, R.attr.zerionColorOnSurfaceMuted), chip, TEXT);
			List<Integer> own = new ArrayList<>();
			own.add(color(ctx, R.attr.zerionColorBubbleOut));
			for (int c : OWN_COLORS) own.add(ContextCompat.getColor(ctx, c));
			int onOwn = color(ctx, R.attr.zerionColorOnBubbleOut);
			int onOwnMuted = color(ctx, R.attr.zerionColorOnBubbleOutMuted);
			int ownInset = color(ctx, R.attr.zerionColorBubbleOutInset);
			for (int c : own) {
				int selected = over(ownLayer, c);
				need(problems, where, String.format(Locale.ROOT,
						"own fill of #%08X", c), selected, c, DISTINCT);
				quiet(problems, where, String.format(Locale.ROOT,
						"own fill of #%08X", c), selected, c);
				for (int bg : new int[] {selected, over(ownInset, selected)}) {
					need(problems, where, "own text", onOwn, bg, TEXT);
					need(problems, where, "own muted text", onOwnMuted, bg,
							TEXT);
				}
			}
			for (int[] pair : SELECTABLE) {
				ColorStateList list =
						ContextCompat.getColorStateList(ctx, pair[0]);
				assertNotNull(list);
				assertEquals(where + " unselected",
						color(ctx, pair[1]), list.getDefaultColor());
				assertEquals(where + " selected", fg,
						list.getColorForState(SELECTED, 0));
			}
		}
		return problems;
	}

	@Test
	@Config(qualifiers = "notnight")
	public void aSelectedMessageOrCardStandsOutAndStaysReadableInLight() {
		List<String> problems = check("light", false);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	@Config(qualifiers = "night")
	public void aSelectedMessageOrCardStandsOutAndStaysReadableInDark() {
		List<String> problems = check("dark", false);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	@Config(qualifiers = "night")
	public void aSelectedMessageOrCardStandsOutAndStaysReadableInAmoled() {
		List<String> problems = check("amoled", true);
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void noDrawableDrawsAnOutlineForTheSelectedState()
			throws Exception {
		File dir = new File("src/main/res/drawable");
		File[] files = dir.listFiles((d, n) -> n.endsWith(".xml"));
		assertTrue(files != null && files.length > 50);
		List<String> problems = new ArrayList<>();
		for (File f : files) {
			Element root = DocumentBuilderFactory.newInstance()
					.newDocumentBuilder().parse(f).getDocumentElement();
			NodeList items = root.getElementsByTagName("item");
			for (int i = 0; i < items.getLength(); i++) {
				Element item = (Element) items.item(i);
				boolean selected = item.hasAttribute("android:state_activated")
						|| item.hasAttribute("android:state_selected");
				if (selected && item.getElementsByTagName("stroke")
						.getLength() > 0) {
					problems.add(f.getName());
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	private static final class CountingTheme extends ContextWrapper {

		int lookups = 0;

		CountingTheme(Context base) {
			super(base);
		}

		@Override
		public Resources.Theme getTheme() {
			lookups++;
			return super.getTheme();
		}
	}

	@Test
	public void aBubbleWorksOutItsSelectedColourOnlyWhenItChanges() {
		CountingTheme ctx = new CountingTheme(themed(false, 0));
		View own = new View(ctx);
		own.setBackgroundResource(R.drawable.msg_out);
		View received = new View(ctx);
		received.setBackgroundResource(R.drawable.msg_in);
		SelectedFill.own(own, 0xFF167DA8);
		SelectedFill.received(received);
		int once = ctx.lookups;
		for (int i = 0; i < 5; i++) {
			SelectedFill.own(own, 0xFF167DA8);
			SelectedFill.received(received);
		}
		assertEquals("binding the same bubble again looks nothing up", once,
				ctx.lookups);

		SelectedFill.own(own, 0xFF7C3AED);
		assertEquals(0xFF7C3AED, SelectedLook.painted(own, false));
		assertTrue(SelectedLook.near(over(color(ctx,
				R.attr.zerionColorMessageSelectedOwn), 0xFF7C3AED),
				SelectedLook.painted(own, true)));
		SelectedFill.bare(own);
		own.setBackgroundResource(R.drawable.msg_out);
		SelectedFill.own(own, 0xFF7C3AED);
		assertEquals("a bubble that lost its tint gets it back", 0xFF7C3AED,
				SelectedLook.painted(own, false));
	}

	@Test
	public void aStickerWithoutABubbleGetsAQuietSelectedShape() {
		Context ctx = themed(false, 0);
		View sticker = new View(ctx);
		sticker.setPadding(7, 8, 9, 10);
		new FrameLayout(ctx).addView(sticker);
		SelectedFill.bare(sticker);

		assertEquals(0, SelectedLook.painted(sticker, false));
		assertEquals(color(ctx, R.attr.zerionColorMessageSelectedReceived),
				SelectedLook.painted(sticker, true));
		assertEquals("the sticker does not move", 7, sticker.getPaddingLeft());
		assertEquals(8, sticker.getPaddingTop());
		assertEquals(9, sticker.getPaddingRight());
		assertEquals(10, sticker.getPaddingBottom());
	}
}
