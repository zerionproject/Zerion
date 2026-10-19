package com.professor.zerion.android.maxfont;

import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.text.LineBreaker;
import android.graphics.text.MeasuredText;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPaint;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class RealText {

	private static final Map<MeasuredText, Measure> MEASURES =
			new WeakHashMap<>();
	public static int measureTextCalls;

	private RealText() {
	}

	static int weight(Paint p) {
		int w = 400;
		Typeface tf = p.getTypeface();
		if (tf != null) {
			int s = tf.getStyle();
			if (s >= 100) w = s;
			else if ((s & Typeface.BOLD) != 0) w = 700;
		}
		if (p.isFakeBoldText()) w = Math.max(w, 700);
		return w;
	}

	static float advances(Paint p, CharSequence text, int start, int end,
			float[] out, int outIndex) {
		RealFont font = RealFont.get();
		float size = p.getTextSize();
		float spacing = p.getLetterSpacing() * size;
		int weight = weight(p);
		float total = 0f;
		for (int i = start; i < end; i++) {
			char c = text.charAt(i);
			float w;
			if (Character.isLowSurrogate(c) && i > start
					&& Character.isHighSurrogate(text.charAt(i - 1))) {
				w = 0f;
			} else {
				int cp = c;
				if (Character.isHighSurrogate(c) && i + 1 < end
						&& Character.isLowSurrogate(text.charAt(i + 1))) {
					cp = Character.toCodePoint(c, text.charAt(i + 1));
				}
				w = font.advanceEm(cp, weight) * size;
				if (RealFont.visible(cp)) w += spacing;
			}
			if (out != null) out[outIndex + i - start] = w;
			total += w;
		}
		return total;
	}

	static float width(Paint p, CharSequence text, int start, int end) {
		return advances(p, text, start, end, null, 0);
	}

	public static float width(Paint p, CharSequence text) {
		return width(p, text, 0, text.length());
	}

	static float ascent(Paint p) {
		return -RealFont.get().ascentEm * p.getTextSize();
	}

	static float descent(Paint p) {
		return RealFont.get().descentEm * p.getTextSize();
	}

	static float top(Paint p) {
		return -RealFont.get().topEm * p.getTextSize();
	}

	static float bottom(Paint p) {
		return RealFont.get().bottomEm * p.getTextSize();
	}

	private static int breakText(Paint p, CharSequence text, int start,
			int end, boolean forwards, float maxWidth, float[] measured) {
		float total = 0f;
		int count = 0;
		int length = end - start;
		while (count < length) {
			int i = forwards ? start + count : end - 1 - count;
			float w = width(p, text, i, i + 1);
			if (total + w > maxWidth) break;
			total += w;
			count++;
		}
		if (measured != null && measured.length > 0) measured[0] = total;
		return count;
	}

	@Implements(Paint.class)
	public static class PaintShadow extends ShadowPaint {

		@RealObject
		private Paint real;
		private float letterSpacing;

		@Override
		@Implementation
		protected void __constructor__(Paint other) {
			super.__constructor__(other);
			if (other == null) return;
			real.setTypeface(other.getTypeface());
			real.setTextSize(other.getTextSize());
			real.setTextAlign(other.getTextAlign());
			letterSpacing = other.getLetterSpacing();
		}

		@Implementation
		protected void set(Paint src) {
			if (src == null || src == real) return;
			real.setTextSize(src.getTextSize());
			real.setTypeface(src.getTypeface());
			real.setFlags(src.getFlags());
			real.setColor(src.getColor());
			real.setTextAlign(src.getTextAlign());
			letterSpacing = src.getLetterSpacing();
		}

		@Implementation
		protected void setLetterSpacing(float spacing) {
			letterSpacing = spacing;
		}

		@Implementation
		protected float getLetterSpacing() {
			return letterSpacing;
		}

		@Override
		@Implementation
		protected float measureText(String text) {
			measureTextCalls++;
			return width(real, text, 0, text.length());
		}

		@Override
		@Implementation
		protected float measureText(CharSequence text, int start, int end) {
			measureTextCalls++;
			return width(real, text, start, end);
		}

		@Override
		@Implementation
		protected float measureText(String text, int start, int end) {
			measureTextCalls++;
			return width(real, text, start, end);
		}

		@Override
		@Implementation
		protected float measureText(char[] text, int index, int count) {
			measureTextCalls++;
			return width(real, new String(text), index, index + count);
		}

		@Implementation
		protected int breakText(char[] text, int index, int count,
				float maxWidth, float[] measuredWidth) {
			String s = new String(text);
			if (count < 0) {
				return RealText.breakText(real, s, index + count + 1,
						index + 1, false, maxWidth, measuredWidth);
			}
			return RealText.breakText(real, s, index, index + count, true,
					maxWidth, measuredWidth);
		}

		@Implementation
		protected int breakText(CharSequence text, int start, int end,
				boolean measureForwards, float maxWidth, float[] measuredWidth) {
			return RealText.breakText(real, text, start, end, measureForwards,
					maxWidth, measuredWidth);
		}

		@Implementation
		protected int breakText(String text, boolean measureForwards,
				float maxWidth, float[] measuredWidth) {
			return RealText.breakText(real, text, 0, text.length(),
					measureForwards, maxWidth, measuredWidth);
		}

		@Implementation
		protected int getTextWidths(char[] text, int index, int count,
				float[] widths) {
			advances(real, new String(text), index, index + count, widths, 0);
			return count;
		}

		@Implementation
		protected int getTextWidths(CharSequence text, int start, int end,
				float[] widths) {
			advances(real, text, start, end, widths, 0);
			return end - start;
		}

		@Implementation
		protected int getTextWidths(String text, int start, int end,
				float[] widths) {
			advances(real, text, start, end, widths, 0);
			return end - start;
		}

		@Implementation
		protected int getTextWidths(String text, float[] widths) {
			advances(real, text, 0, text.length(), widths, 0);
			return text.length();
		}

		@Implementation
		protected float getTextRunAdvances(char[] chars, int index, int count,
				int contextIndex, int contextCount, boolean isRtl,
				float[] advances, int advancesIndex) {
			return advances(real, new String(chars), index, index + count,
					advances, advancesIndex);
		}

		@Implementation
		protected float getRunAdvance(char[] text, int start, int end,
				int contextStart, int contextEnd, boolean isRtl, int offset) {
			String s = new String(text);
			return isRtl ? width(real, s, offset, end)
					: width(real, s, start, offset);
		}

		@Implementation
		protected float getRunAdvance(CharSequence text, int start, int end,
				int contextStart, int contextEnd, boolean isRtl, int offset) {
			return isRtl ? width(real, text, offset, end)
					: width(real, text, start, offset);
		}

		@Implementation
		protected float ascent() {
			return RealText.ascent(real);
		}

		@Implementation
		protected float descent() {
			return RealText.descent(real);
		}

		@Implementation
		protected float getFontMetrics(Paint.FontMetrics metrics) {
			float ascent = RealText.ascent(real);
			float descent = RealText.descent(real);
			if (metrics != null) {
				metrics.top = top(real);
				metrics.ascent = ascent;
				metrics.descent = descent;
				metrics.bottom = bottom(real);
				metrics.leading = 0f;
			}
			return descent - ascent;
		}

		@Override
		@Implementation
		protected int getFontMetricsInt(Paint.FontMetricsInt fmi) {
			int ascent = Math.round(RealText.ascent(real));
			int descent = Math.round(RealText.descent(real));
			if (fmi != null) {
				fmi.top = (int) Math.floor(top(real));
				fmi.ascent = ascent;
				fmi.descent = descent;
				fmi.bottom = (int) Math.ceil(bottom(real));
				fmi.leading = 0;
			}
			return descent - ascent;
		}

		@Implementation
		protected void getTextBounds(String text, int start, int end,
				Rect bounds) {
			bounds.set(0, Math.round(RealText.ascent(real)),
					(int) Math.ceil(width(real, text, start, end)),
					Math.round(RealText.descent(real)));
		}

		@Implementation
		protected void getTextBounds(CharSequence text, int start, int end,
				Rect bounds) {
			bounds.set(0, Math.round(RealText.ascent(real)),
					(int) Math.ceil(width(real, text, start, end)),
					Math.round(RealText.descent(real)));
		}

		@Implementation
		protected void getTextBounds(char[] text, int index, int count,
				Rect bounds) {
			getTextBounds(new String(text), index, index + count, bounds);
		}
	}

	static final class Measure {

		final char[] text;
		final float[] widths;
		final float[] ascents;
		final float[] descents;

		Measure(char[] text) {
			this.text = text;
			widths = new float[text.length];
			ascents = new float[text.length];
			descents = new float[text.length];
		}

		float width(int start, int end) {
			float w = 0f;
			for (int i = start; i < end; i++) w += widths[i];
			return w;
		}
	}

	private static Object field(Object target, Class<?> type, String name) {
		try {
			Field f = type.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(target);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	@Implements(MeasuredText.Builder.class)
	public static class BuilderShadow {

		@RealObject
		private MeasuredText.Builder real;
		private Measure measure;
		private int offset;

		private Measure measure() {
			if (measure == null) {
				measure = new Measure((char[]) field(real,
						MeasuredText.Builder.class, "mText"));
			}
			return measure;
		}

		private void metrics(Paint paint, int start, int end) {
			Measure m = measure();
			for (int i = start; i < end; i++) {
				m.ascents[i] = RealText.ascent(paint);
				m.descents[i] = RealText.descent(paint);
			}
		}

		@Implementation
		protected MeasuredText.Builder appendStyleRun(Paint paint, int length,
				boolean isRtl) {
			Measure m = measure();
			int end = offset + length;
			advances(paint, new String(m.text), offset, end, m.widths, offset);
			metrics(paint, offset, end);
			offset = end;
			return real;
		}

		@Implementation
		protected MeasuredText.Builder appendReplacementRun(Paint paint,
				int length, float width) {
			Measure m = measure();
			int end = offset + length;
			for (int i = offset; i < end; i++) m.widths[i] = 0f;
			if (length > 0) m.widths[offset] = width;
			metrics(paint, offset, end);
			offset = end;
			return real;
		}

		@Implementation
		protected MeasuredText build() {
			Measure m = measure();
			try {
				Constructor<MeasuredText> c = MeasuredText.class
						.getDeclaredConstructor(long.class, char[].class,
								boolean.class, boolean.class);
				c.setAccessible(true);
				MeasuredText res = c.newInstance(1L, m.text,
						(Boolean) field(real, MeasuredText.Builder.class,
								"mComputeHyphenation"),
						(Boolean) field(real, MeasuredText.Builder.class,
								"mComputeLayout"));
				synchronized (MEASURES) {
					MEASURES.put(res, m);
				}
				return res;
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		}
	}

	static Measure measureOf(MeasuredText t) {
		synchronized (MEASURES) {
			Measure m = MEASURES.get(t);
			if (m == null) throw new AssertionError("unknown measured text");
			return m;
		}
	}

	@Implements(MeasuredText.class)
	public static class MeasuredTextShadow {

		@RealObject
		private MeasuredText real;

		@Implementation
		protected float getWidth(int start, int end) {
			return measureOf(real).width(start, end);
		}

		@Implementation
		protected float getCharWidthAt(int offset) {
			return measureOf(real).widths[offset];
		}

		@Implementation
		protected void getBounds(int start, int end, Rect rect) {
			Measure m = measureOf(real);
			float ascent = 0f;
			float descent = 0f;
			for (int i = start; i < end; i++) {
				ascent = Math.min(ascent, m.ascents[i]);
				descent = Math.max(descent, m.descents[i]);
			}
			rect.set(0, Math.round(ascent), (int) Math.ceil(m.width(start,
					end)), Math.round(descent));
		}

		@Implementation
		protected int getMemoryUsage() {
			return 0;
		}
	}

	private static boolean space(char c) {
		return c == ' ' || c == '\t' || c == '\u3000' || c == '\u2009'
				|| c == '\u200a' || c == '\u2002' || c == '\u2003';
	}

	private static boolean ideographic(char c) {
		return (c >= '\u2e80' && c <= '\u9fff')
				|| (c >= '\uac00' && c <= '\ud7af')
				|| (c >= '\uf900' && c <= '\ufaff');
	}

	static boolean breakAfter(char[] t, int k) {
		char c = t[k];
		char next = k + 1 < t.length ? t[k + 1] : 0;
		if (space(c) || c == '\u200b') return true;
		if ((c == '-' || c == '\u2010') && k > 0
				&& Character.isLetter(t[k - 1]) && Character.isLetter(next)) {
			return true;
		}
		return ideographic(c) || ideographic(next);
	}

	static final class Lines {

		final List<int[]> ranges = new ArrayList<>();
		final List<Float> widths = new ArrayList<>();
		final List<Float> ascents = new ArrayList<>();
		final List<Float> descents = new ArrayList<>();
		final List<Boolean> tabs = new ArrayList<>();
	}

	static Lines breakLines(Measure m, float firstWidth, int firstCount,
			float restWidth) {
		char[] t = m.text;
		int n = t.length;
		Lines lines = new Lines();
		int start = 0;
		while (start < n) {
			float avail = lines.ranges.size() < firstCount ? firstWidth
					: restWidth;
			int lastBreak = -1;
			float width = 0f;
			int end = -1;
			int i = start;
			while (i < n) {
				char c = t[i];
				if (c == '\n') {
					end = i + 1;
					break;
				}
				float w = m.widths[i];
				if (!space(c) && i > start && width + w > avail + 0.01f) {
					end = lastBreak > start ? lastBreak : i;
					break;
				}
				width += w;
				i++;
				if (breakAfter(t, i - 1)) lastBreak = i;
			}
			if (end < 0) end = n;
			int visibleEnd = end;
			while (visibleEnd > start && (t[visibleEnd - 1] == '\n'
					|| space(t[visibleEnd - 1]))) {
				visibleEnd--;
			}
			float ascent = 0f;
			float descent = 0f;
			boolean tab = false;
			for (int k = start; k < end; k++) {
				ascent = Math.min(ascent, m.ascents[k]);
				descent = Math.max(descent, m.descents[k]);
				if (t[k] == '\t') tab = true;
			}
			lines.ranges.add(new int[] {start, end});
			lines.widths.add(m.width(start, visibleEnd));
			lines.ascents.add(ascent);
			lines.descents.add(descent);
			lines.tabs.add(tab);
			start = end;
		}
		return lines;
	}

	@Implements(LineBreaker.class)
	public static class LineBreakerShadow {

		@Implementation
		protected LineBreaker.Result computeLineBreaks(MeasuredText measured,
				LineBreaker.ParagraphConstraints constraints, int lineNumber) {
			Measure m = measureOf(measured);
			Lines lines = breakLines(m, constraints.getFirstWidth(),
					constraints.getFirstWidthLineCount(),
					constraints.getWidth());
			try {
				Constructor<LineBreaker.Result> c = LineBreaker.Result.class
						.getDeclaredConstructor(long.class);
				c.setAccessible(true);
				LineBreaker.Result r = c.newInstance(1L);
				ResultShadow s = Shadow.extract(r);
				s.lines = lines;
				return r;
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		}
	}

	@Implements(LineBreaker.Result.class)
	public static class ResultShadow {

		private Lines lines;

		@Implementation
		protected int getLineCount() {
			return lines.ranges.size();
		}

		@Implementation
		protected int getLineBreakOffset(int line) {
			return lines.ranges.get(line)[1];
		}

		@Implementation
		protected float getLineWidth(int line) {
			return lines.widths.get(line);
		}

		@Implementation
		protected float getLineAscent(int line) {
			return lines.ascents.get(line);
		}

		@Implementation
		protected float getLineDescent(int line) {
			return lines.descents.get(line);
		}

		@Implementation
		protected boolean hasLineTab(int line) {
			return lines.tabs.get(line);
		}

		@Implementation
		protected int getStartLineHyphenEdit(int line) {
			return 0;
		}

		@Implementation
		protected int getEndLineHyphenEdit(int line) {
			return 0;
		}
	}
}
