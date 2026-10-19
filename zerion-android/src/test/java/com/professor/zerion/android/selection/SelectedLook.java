package com.professor.zerion.android.selection;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.ColorStateListDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.professor.zerion.R;
import com.professor.zerion.android.util.UiUtils;

import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import static com.professor.zerion.android.ContrastMath.over;
import static com.professor.zerion.android.ContrastMath.ratio;
import static org.junit.Assert.assertNotNull;

public final class SelectedLook {

	private static final int ACTIVATED = android.R.attr.state_activated;
	private static final int[] BUBBLES = {R.id.bubble, R.id.mediaBubble,
			R.id.voiceBubble, R.id.commentCard, R.id.layout};
	private static final int[] OVER_PICTURES = {R.id.videoPlayIcon,
			R.id.playOverlay};

	private SelectedLook() {
	}

	public static View bubble(View row) {
		for (int id : BUBBLES) {
			View v = row.findViewById(id);
			if (v != null && v.getVisibility() == View.VISIBLE) return v;
		}
		throw new AssertionError("no bubble in the row");
	}

	public static int[] state(View v, boolean selected) {
		List<Integer> out = new ArrayList<>();
		for (int s : v.getDrawableState()) {
			if (s != ACTIVATED) out.add(s);
		}
		if (selected) out.add(ACTIVATED);
		int[] state = new int[out.size()];
		for (int i = 0; i < state.length; i++) state[i] = out.get(i);
		return state;
	}

	public static int painted(View v) {
		return painted(v, v.getDrawableState());
	}

	public static int painted(View v, boolean selected) {
		return painted(v, state(v, selected));
	}

	public static int painted(View v, int[] state) {
		if (v instanceof MaterialCardView) {
			ColorStateList c = ((MaterialCardView) v).getCardBackgroundColor();
			return c.getColorForState(state, c.getDefaultColor());
		}
		Drawable d = shown(v.getBackground(), state);
		int base = fill(d, state);
		ColorStateList tint = v.getBackgroundTintList();
		if (tint == null || d == null) return base;
		int t = tint.getColorForState(state, tint.getDefaultColor());
		PorterDuff.Mode mode = v.getBackgroundTintMode();
		if (mode == PorterDuff.Mode.SRC_ATOP) {
			return (base >>> 24) == 0 ? base : over(t, base);
		}
		if (mode == null || mode == PorterDuff.Mode.SRC_IN) {
			int alpha = ((base >>> 24) * (t >>> 24)) / 255;
			return (alpha << 24) | (t & 0xFFFFFF);
		}
		throw new AssertionError("tint mode " + mode);
	}

	public static boolean drawsSelected(View row) {
		View b = bubble(row);
		return b.isActivated() && painted(b) != painted(b, false);
	}

	public static int strokeWidth(View v, boolean selected) throws Exception {
		Drawable d = shown(v.getBackground(), state(v, selected));
		if (!(d instanceof GradientDrawable)) return 0;
		Object s = d.getConstantState();
		assertNotNull(s);
		Field f = s.getClass().getDeclaredField("mStrokeWidth");
		f.setAccessible(true);
		return Math.max(0, f.getInt(s));
	}

	public static int strokeColor(View v, boolean selected) throws Exception {
		int[] state = state(v, selected);
		Drawable d = shown(v.getBackground(), state);
		if (!(d instanceof GradientDrawable)) return 0;
		Object s = d.getConstantState();
		assertNotNull(s);
		Field f = s.getClass().getDeclaredField("mStrokeColors");
		f.setAccessible(true);
		ColorStateList c = (ColorStateList) f.get(s);
		if (c == null) return 0;
		return c.getColorForState(state, c.getDefaultColor());
	}

	public static <T extends AppCompatActivity> T start(
			ActivityController<T> c, int night) {
		if (night != AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
			c.get().getDelegate().setLocalNightMode(night);
		}
		return c.setup().get();
	}

	public static int iconColor(ImageView v) throws Exception {
		Drawable d = v.getDrawable();
		assertNotNull(d);
		int res = Shadows.shadowOf(d).getCreatedFromResId();
		Context ctx = v.getContext();
		File f = new File("src/main/res/drawable/"
				+ ctx.getResources().getResourceEntryName(res) + ".xml");
		Element root = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder().parse(f).getDocumentElement();
		int fill = 0;
		NodeList paths = root.getElementsByTagName("path");
		for (int i = 0; i < paths.getLength() && fill == 0; i++) {
			String c = ((Element) paths.item(i)).getAttribute(
					"android:fillColor");
			if (!c.isEmpty()) fill = resolve(ctx, c);
		}
		int[] state = v.getDrawableState();
		ColorStateList tint = ImageViewCompat.getImageTintList(v);
		if (tint != null) {
			int t = tint.getColorForState(state, tint.getDefaultColor());
			PorterDuff.Mode mode = ImageViewCompat.getImageTintMode(v);
			if (mode == PorterDuff.Mode.SRC_ATOP) return over(t, fill);
			return t;
		}
		String own = root.getAttribute("android:tint");
		return own.isEmpty() ? fill : resolve(ctx, own);
	}

	private static int resolve(Context ctx, String c) {
		if (c.startsWith("#")) {
			long v = Long.parseLong(c.substring(1), 16);
			if (c.length() == 7) v |= 0xFF000000L;
			return (int) v;
		}
		if (c.equals("@android:color/white")) return 0xFFFFFFFF;
		if (c.equals("@android:color/black")) return 0xFF000000;
		if (c.startsWith("?")) {
			String name = c.substring(c.indexOf('/') + 1);
			int attr = ctx.getResources().getIdentifier(name, "attr",
					ctx.getPackageName());
			return UiUtils.resolveColorAttribute(ctx, attr);
		}
		if (c.startsWith("@color/")) {
			return ctx.getColor(ctx.getResources().getIdentifier(
					c.substring(7), "color", ctx.getPackageName()));
		}
		throw new AssertionError("colour " + c);
	}

	public static boolean near(int a, int b) {
		for (int shift : new int[] {24, 16, 8, 0}) {
			if (Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF)) > 1) {
				return false;
			}
		}
		return true;
	}

	public static boolean toldSelected(View v) {
		return v.createAccessibilityNodeInfo().isSelected();
	}

	public static List<String> boxes(View v) {
		List<String> out = new ArrayList<>();
		boxes(v, "", out);
		return out;
	}

	private static void boxes(View v, String path, List<String> out) {
		if (v.getVisibility() == View.GONE) return;
		out.add(String.format(Locale.ROOT, "%s %d,%d %dx%d pad %d,%d,%d,%d",
				path, v.getLeft(), v.getTop(), v.getWidth(), v.getHeight(),
				v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
				v.getPaddingBottom()));
		if (!(v instanceof ViewGroup)) return;
		ViewGroup g = (ViewGroup) v;
		for (int i = 0; i < g.getChildCount(); i++) {
			boxes(g.getChildAt(i), path + "/" + i, out);
		}
	}

	public static List<String> unreadable(View row, double text,
			double icons) {
		List<String> out = new ArrayList<>();
		int screen = UiUtils.resolveColorAttribute(row.getContext(),
				R.attr.zerionColorBackground);
		int[] checked = {0};
		walk(row, screen, text, icons, out, checked);
		if (checked[0] == 0) out.add("no words were checked");
		return out;
	}

	private static void walk(View v, int under, double text, double icons,
			List<String> out, int[] checked) {
		if (v.getVisibility() != View.VISIBLE) return;
		for (int id : OVER_PICTURES) {
			if (v.getId() == id) return;
		}
		int here = layer(v, under);
		int[] state = v.getDrawableState();
		if (v instanceof TextView && ((TextView) v).length() > 0) {
			TextView t = (TextView) v;
			checked[0]++;
			need(out, v, "text", t.getCurrentTextColor(), here, text);
			if (hasLinks(t)) {
				ColorStateList links = t.getLinkTextColors();
				if (links != null) {
					need(out, v, "link", links.getColorForState(state,
							links.getDefaultColor()), here, text);
				}
			}
		}
		if (v instanceof MaterialButton
				&& ((MaterialButton) v).getIcon() != null) {
			ColorStateList tint = ((MaterialButton) v).getIconTint();
			if (tint != null) {
				need(out, v, "icon", tint.getColorForState(state,
						tint.getDefaultColor()), here, icons);
			}
		}
		if (v instanceof ImageView && ((ImageView) v).getDrawable() != null) {
			ColorStateList tint =
					ImageViewCompat.getImageTintList((ImageView) v);
			if (tint != null) {
				int c = tint.getColorForState(state, tint.getDefaultColor());
				if ((c >>> 24) == 0xFF) need(out, v, "icon", c, here, icons);
			}
		}
		if (v instanceof ViewGroup && !(v instanceof RecyclerView)) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				walk(g.getChildAt(i), here, text, icons, out, checked);
			}
		}
	}

	private static int layer(View v, int under) {
		int[] state = v.getDrawableState();
		int top;
		if (v instanceof MaterialButton) {
			ColorStateList tint = v.getBackgroundTintList();
			if (tint == null) return under;
			top = tint.getColorForState(state, tint.getDefaultColor());
		} else {
			top = painted(v, state);
		}
		return (top >>> 24) == 0 ? under : over(top, under);
	}

	private static Drawable shown(Drawable d, int[] state) {
		if (d instanceof StateListDrawable) {
			StateListDrawable list = (StateListDrawable) d;
			int i = list.findStateDrawableIndex(state);
			return i < 0 ? null : list.getStateDrawable(i);
		}
		return d;
	}

	private static int fill(Drawable d, int[] state) {
		ColorStateList c = null;
		if (d instanceof GradientDrawable) {
			c = ((GradientDrawable) d).getColor();
		} else if (d instanceof ColorStateListDrawable) {
			c = ((ColorStateListDrawable) d).getColorStateList();
		} else if (d instanceof ColorDrawable) {
			return ((ColorDrawable) d).getColor();
		}
		return c == null ? 0 : c.getColorForState(state, c.getDefaultColor());
	}

	private static boolean hasLinks(TextView t) {
		CharSequence s = t.getText();
		return s instanceof Spanned && ((Spanned) s).getSpans(0, s.length(),
				ClickableSpan.class).length > 0;
	}

	private static void need(List<String> out, View v, String what, int fg,
			int bg, double min) {
		double r = ratio(fg, bg);
		if (r >= min) return;
		Context ctx = v.getContext();
		String name = v.getId() == View.NO_ID ? v.getClass().getSimpleName()
				: ctx.getResources().getResourceEntryName(v.getId());
		out.add(String.format(Locale.ROOT, "%s %s #%08X on #%08X is %.2f:1",
				name, what, fg, bg, r));
	}
}
