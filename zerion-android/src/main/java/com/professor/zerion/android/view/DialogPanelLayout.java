package com.professor.zerion.android.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ScrollView;
import android.widget.TextView;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.widget.NestedScrollView;

@UiThread
public class DialogPanelLayout extends LinearLayoutCompat {

	private static final float BUTTON_SHARE = 0.6f;
	private static final int MESSAGE_MIN_DP = 48;

	public DialogPanelLayout(Context context) {
		this(context, null);
	}

	public DialogPanelLayout(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected void onMeasure(int widthSpec, int heightSpec) {
		if (getOrientation() != VERTICAL
				|| MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
				|| !measurePanels(widthSpec, heightSpec)) {
			super.onMeasure(widthSpec, heightSpec);
		}
	}

	private boolean measurePanels(int widthSpec, int heightSpec) {
		View top = null;
		View content = null;
		View custom = null;
		View buttons = null;
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (child.getVisibility() == GONE) continue;
			int id = child.getId();
			if (id == androidx.appcompat.R.id.topPanel) top = child;
			else if (id == androidx.appcompat.R.id.contentPanel) content = child;
			else if (id == androidx.appcompat.R.id.customPanel) custom = child;
			else if (id == androidx.appcompat.R.id.buttonPanel) buttons = child;
			else return false;
		}
		float density = getResources().getDisplayMetrics().density;
		int limit = MeasureSpec.getSize(heightSpec) - getPaddingTop()
				- getPaddingBottom();
		int messageMin = content == null ? 0
				: Math.round(MESSAGE_MIN_DP * density);
		int topNatural = natural(top, widthSpec, limit);
		int buttonsNatural = natural(buttons, widthSpec, limit);
		int customNatural = natural(custom, widthSpec, limit);
		int reserve = custom == null ? 0 : fieldReserve(custom,
				customNatural);
		int buttonsHeight = Math.min(buttonsNatural, Math.min(
				Math.max(0, limit - reserve),
				Math.max(Math.round(limit * BUTTON_SHARE),
						limit - topNatural - messageMin)));
		int topHeight = Math.min(topNatural,
				Math.max(0, limit - buttonsHeight - reserve));
		int customHeight = Math.min(customNatural, Math.max(reserve,
				limit - topHeight - buttonsHeight - messageMin));
		int contentHeight = Math.max(0,
				limit - topHeight - buttonsHeight - customHeight);
		int used = fit(top, widthSpec, topHeight)
				+ fit(buttons, widthSpec, buttonsHeight)
				+ fit(custom, widthSpec, customHeight);
		if (content != null) {
			natural(content, widthSpec, contentHeight);
			used += fit(content, widthSpec, contentHeight);
		}
		int state = 0;
		int width = 0;
		View[] panels = {top, content, custom, buttons};
		for (View panel : panels) {
			if (panel == null) continue;
			state = combineMeasuredStates(state, panel.getMeasuredState());
			width = Math.max(width, panel.getMeasuredWidth());
		}
		width += getPaddingLeft() + getPaddingRight();
		int measuredWidth = resolveSizeAndState(Math.max(width,
				getSuggestedMinimumWidth()), widthSpec, state);
		int inner = (measuredWidth & MEASURED_SIZE_MASK) - getPaddingLeft()
				- getPaddingRight();
		for (View panel : panels) {
			if (panel != null && panel.getMeasuredWidth() != inner) {
				panel.measure(MeasureSpec.makeMeasureSpec(inner,
						MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(
						panel.getMeasuredHeight(), MeasureSpec.EXACTLY));
			}
		}
		int height = used + getPaddingTop() + getPaddingBottom();
		setMeasuredDimension(measuredWidth, resolveSizeAndState(Math.max(
				height, getSuggestedMinimumHeight()), heightSpec,
				state << MEASURED_HEIGHT_STATE_SHIFT));
		return true;
	}

	private static int fieldReserve(View custom, int natural) {
		TextView field = editableIn(custom.findFocus());
		if (field == null) field = editableIn(custom);
		if (field == null) return 0;
		View box = field;
		for (ViewParent p = field.getParent(); p instanceof ViewGroup
				&& p != custom; p = p.getParent()) {
			ViewGroup g = (ViewGroup) p;
			if (g instanceof ScrollView || g instanceof NestedScrollView
					|| shownChildren(g) != 1) {
				break;
			}
			box = g;
		}
		int height = box.getMeasuredHeight();
		ViewGroup.LayoutParams lp = box.getLayoutParams();
		if (lp instanceof MarginLayoutParams) {
			height += ((MarginLayoutParams) lp).topMargin
					+ ((MarginLayoutParams) lp).bottomMargin;
		}
		return Math.min(natural, height);
	}

	private static int shownChildren(ViewGroup g) {
		int n = 0;
		for (int i = 0; i < g.getChildCount(); i++) {
			if (g.getChildAt(i).getVisibility() != GONE) n++;
		}
		return n;
	}

	@Nullable
	private static TextView editableIn(@Nullable View v) {
		if (v == null || v.getVisibility() != VISIBLE) return null;
		if (v instanceof TextView && ((TextView) v).getKeyListener() != null) {
			return (TextView) v;
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				TextView found = editableIn(g.getChildAt(i));
				if (found != null) return found;
			}
		}
		return null;
	}

	private int natural(@Nullable View panel, int widthSpec, int room) {
		if (panel == null) return 0;
		LayoutParams lp = (LayoutParams) panel.getLayoutParams();
		int childWidth = getChildMeasureSpec(widthSpec, getPaddingLeft()
				+ getPaddingRight() + lp.leftMargin + lp.rightMargin, lp.width);
		int available = Math.max(0, room - lp.topMargin - lp.bottomMargin);
		int childHeight = lp.height >= 0
				? MeasureSpec.makeMeasureSpec(Math.min(lp.height, available),
						MeasureSpec.EXACTLY)
				: MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);
		panel.measure(childWidth, childHeight);
		return panel.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
	}

	private int fit(@Nullable View panel, int widthSpec, int room) {
		if (panel == null) return 0;
		LayoutParams lp = (LayoutParams) panel.getLayoutParams();
		int available = Math.max(0, room - lp.topMargin - lp.bottomMargin);
		if (panel.getMeasuredHeight() > available) {
			panel.measure(MeasureSpec.makeMeasureSpec(panel.getMeasuredWidth(),
					MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(available,
					MeasureSpec.EXACTLY));
		}
		return panel.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
	}
}
