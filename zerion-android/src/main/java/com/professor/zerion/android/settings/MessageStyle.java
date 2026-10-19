package com.professor.zerion.android.settings;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.android.selection.SelectedFill;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.ColorInt;

@NotNullByDefault
public final class MessageStyle {

	private final float textSizeSp;
	@ColorInt
	private final int bubbleColor;

	MessageStyle(float textSizeSp, @ColorInt int bubbleColor) {
		this.textSizeSp = textSizeSp;
		this.bubbleColor = bubbleColor;
	}

	public static MessageStyle read(Context ctx) {
		return new MessageStyle(ChatPreferences.getMessageTextSizeSp(ctx),
				ChatPreferences.getBubbleColor(ctx));
	}

	public float getTextSizeSp() {
		return textSizeSp;
	}

	@ColorInt
	public int getBubbleColor() {
		return bubbleColor;
	}

	public void applyTextSize(TextView text) {
		text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
	}

	public void tintOutgoing(View bubble) {
		SelectedFill.own(bubble, bubbleColor);
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (!(o instanceof MessageStyle)) return false;
		MessageStyle other = (MessageStyle) o;
		return Float.compare(textSizeSp, other.textSizeSp) == 0
				&& bubbleColor == other.bubbleColor;
	}

	@Override
	public int hashCode() {
		return 31 * Float.floatToIntBits(textSizeSp) + bubbleColor;
	}

	@NotNullByDefault
	public static final class Source {

		@Nullable
		private MessageStyle style;

		public MessageStyle get(Context ctx) {
			MessageStyle s = style;
			if (s == null) {
				s = read(ctx);
				style = s;
			}
			return s;
		}

		public boolean update(MessageStyle latest) {
			MessageStyle old = style;
			style = latest;
			return old != null && !old.equals(latest);
		}
	}
}
