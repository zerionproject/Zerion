package com.professor.zerion.android.maxfont;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplicationAccess;

import org.junit.After;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import androidx.appcompat.app.AppCompatActivity;

@Config(sdk = 29, qualifiers = "nl-w360dp-h740dp-xhdpi", shadows = {
		RealText.PaintShadow.class, RealText.BuilderShadow.class,
		RealText.MeasuredTextShadow.class, RealText.LineBreakerShadow.class,
		RealText.ResultShadow.class})
public abstract class MaxFontTest {

	public static final float MAX_FONT_SCALE = 2f;

	public static class Largest extends AppCompatActivity {

		@Override
		protected void attachBaseContext(Context base) {
			Configuration c = new Configuration(
					base.getResources().getConfiguration());
			c.fontScale = MAX_FONT_SCALE;
			attachBaseContextAtScale(base.createConfigurationContext(c));
		}

		void attachBaseContextAtScale(Context context) {
			super.attachBaseContext(context);
		}

		@Override
		protected void onCreate(Bundle state) {
			setTheme(R.style.ZerionTheme_NoActionBar);
			super.onCreate(state);
			setContentView(new FrameLayout(this));
		}
	}

	public static class Normal extends Largest {

		@Override
		protected void attachBaseContext(Context base) {
			Configuration c = new Configuration(
					base.getResources().getConfiguration());
			c.fontScale = 1f;
			super.attachBaseContextAtScale(base.createConfigurationContext(c));
		}
	}

	@After
	public void shutDownThisApplication() {
		ZerionApplicationAccess.shutDown(RuntimeEnvironment.getApplication());
	}

	protected static Normal normalActivity() {
		return Robolectric.buildActivity(Normal.class).setup().get();
	}

	protected static Largest activity() {
		Largest a = Robolectric.buildActivity(Largest.class).setup().get();
		if (a.getResources().getConfiguration().fontScale != MAX_FONT_SCALE) {
			throw new AssertionError("font scale not applied");
		}
		return a;
	}

	protected static float dp(Context c) {
		return c.getResources().getDisplayMetrics().density;
	}

	protected static int screenWidth(Context c) {
		return c.getResources().getDisplayMetrics().widthPixels;
	}

	protected static void layoutAtWidth(View v, int width) {
		v.measure(View.MeasureSpec.makeMeasureSpec(width,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0,
				View.MeasureSpec.UNSPECIFIED));
		v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
	}

	protected static void layoutIn(View v, int width, int height) {
		v.measure(View.MeasureSpec.makeMeasureSpec(width,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				height, View.MeasureSpec.EXACTLY));
		v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
	}

	protected static String brokenWord(TextView t) {
		Layout l = t.getLayout();
		if (l == null) throw new AssertionError("no layout for " + t.getText());
		CharSequence s = l.getText();
		for (int i = 0; i < l.getLineCount() - 1; i++) {
			int end = l.getLineEnd(i);
			if (end <= 0 || end >= s.length()) continue;
			char before = s.charAt(end - 1);
			char after = s.charAt(end);
			if (Character.isLetterOrDigit(before)
					&& Character.isLetterOrDigit(after)) {
				return s.subSequence(l.getLineStart(i), end) + "|"
						+ s.subSequence(end, l.getLineEnd(i + 1));
			}
		}
		return null;
	}

	protected static boolean shownWhole(TextView t) {
		Layout l = t.getLayout();
		if (l == null) throw new AssertionError("no layout for " + t.getText());
		int lines = l.getLineCount();
		int max = t.getMaxLines();
		return !ellipsized(t) && (max <= 0 || lines <= max)
				&& l.getLineEnd(lines - 1) == l.getText().length();
	}

	protected static boolean ellipsized(TextView t) {
		Layout l = t.getLayout();
		if (l == null) throw new AssertionError("no layout for " + t.getText());
		for (int i = 0; i < l.getLineCount(); i++) {
			if (l.getEllipsisCount(i) > 0) return true;
		}
		return false;
	}

	protected static int[] boundsIn(View v, View ancestor) {
		int left = 0;
		int top = 0;
		View cur = v;
		while (cur != ancestor) {
			View parent = (View) cur.getParent();
			if (parent == null) throw new AssertionError("not inside ancestor");
			left += cur.getLeft() - parent.getScrollX();
			top += cur.getTop() - parent.getScrollY();
			cur = parent;
		}
		return new int[] {left, top, left + v.getWidth(), top + v.getHeight()};
	}

	protected static FrameLayout host(Context c, View child) {
		FrameLayout host = new FrameLayout(c);
		host.addView(child, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		return host;
	}
}
