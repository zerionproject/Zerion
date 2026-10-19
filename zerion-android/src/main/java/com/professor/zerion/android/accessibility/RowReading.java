package com.professor.zerion.android.accessibility;

import android.os.Bundle;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;

@UiThread
@NotNullByDefault
public final class RowReading {

	private static final Part PART = new Part();

	private RowReading() {
	}

	public static void oneItem(View row) {
		ViewCompat.setScreenReaderFocusable(row, true);
	}

	public static void partOf(@Nullable View part) {
		if (part == null || isPart(part)) return;
		part.setTag(R.id.selection_named, null);
		ViewCompat.removeAccessibilityAction(part,
				AccessibilityActionCompat.ACTION_CLICK.getId());
		ViewCompat.removeAccessibilityAction(part,
				AccessibilityActionCompat.ACTION_LONG_CLICK.getId());
		ViewCompat.setAccessibilityDelegate(part, PART);
	}

	public static boolean isPart(View v) {
		return ViewCompat.getAccessibilityDelegate(v) == PART;
	}

	static boolean hasLinks(View v) {
		if (!(v instanceof TextView)) return false;
		CharSequence text = ((TextView) v).getText();
		if (!(text instanceof Spanned)) return false;
		Spanned s = (Spanned) text;
		return s.getSpans(0, s.length(), ClickableSpan.class).length > 0;
	}

	private static final class Part extends AccessibilityDelegateCompat {

		@Override
		public void onInitializeAccessibilityNodeInfo(View host,
				AccessibilityNodeInfoCompat info) {
			super.onInitializeAccessibilityNodeInfo(host, info);
			if (hasLinks(host)) {
				if (host.isLongClickable()) {
					info.removeAction(
							AccessibilityActionCompat.ACTION_LONG_CLICK);
					info.addAction(new AccessibilityActionCompat(
							AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
							host.getContext().getString(
									R.string.selection_select)));
				}
				return;
			}
			info.setClickable(false);
			info.setLongClickable(false);
			info.setFocusable(false);
			info.removeAction(AccessibilityActionCompat.ACTION_CLICK);
			info.removeAction(AccessibilityActionCompat.ACTION_LONG_CLICK);
			info.removeAction(AccessibilityActionCompat.ACTION_FOCUS);
		}

		@Override
		public boolean performAccessibilityAction(View host, int action,
				@Nullable Bundle args) {
			if (!hasLinks(host) && (action
					== AccessibilityNodeInfoCompat.ACTION_CLICK
					|| action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)) {
				return false;
			}
			return super.performAccessibilityAction(host, action, args);
		}
	}
}
