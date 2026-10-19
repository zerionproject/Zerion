package com.professor.zerion.android.selection;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.accessibility.RowReading;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.function.BooleanSupplier;

import androidx.annotation.UiThread;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.recyclerview.widget.RecyclerView;

@UiThread
@NotNullByDefault
public final class SelectionRows {

	private SelectionRows() {
	}

	public static void mark(View row, boolean selected) {
		row.setActivated(selected);
		row.setSelected(selected);
		follow(row, selected);
	}

	private static void follow(View v, boolean selected) {
		if (!(v instanceof ViewGroup) || v instanceof RecyclerView) return;
		ViewGroup group = (ViewGroup) v;
		for (int i = 0; i < group.getChildCount(); i++) {
			View child = group.getChildAt(i);
			if (child.isActivated() != selected) child.setActivated(selected);
			if (child.isSelected() != selected) child.setSelected(selected);
			follow(child, selected);
		}
	}

	public static void nameLongPress(View v) {
		if (RowReading.isPart(v)) return;
		String label = v.getContext().getString(R.string.selection_select);
		if (label.equals(v.getTag(R.id.selection_named))) return;
		ViewCompat.replaceAccessibilityAction(v,
				AccessibilityActionCompat.ACTION_LONG_CLICK, label, null);
		v.setTag(R.id.selection_named, label);
	}

	private static final class Presses implements View.OnLongClickListener,
			View.OnTouchListener {

		private View to;
		private BooleanSupplier selecting;
		private boolean held;

		private Presses(View to, BooleanSupplier selecting) {
			this.to = to;
			this.selecting = selecting;
		}

		@Override
		public boolean onLongClick(View v) {
			held = to.performLongClick();
			return held;
		}

		@Override
		public boolean onTouch(View v, MotionEvent e) {
			int action = e.getActionMasked();
			if (action == MotionEvent.ACTION_DOWN) {
				held = false;
			} else if (action == MotionEvent.ACTION_UP) {
				if (held) {
					if (!dropsReleaseAfterLongPress(v)) {
						e.setAction(MotionEvent.ACTION_CANCEL);
					}
				} else if (selecting.getAsBoolean()) {
					e.setAction(MotionEvent.ACTION_CANCEL);
					to.performClick();
				}
			} else if (action == MotionEvent.ACTION_CANCEL) {
				if (held && dropsReleaseAfterLongPress(v)) {
					e.setAction(MotionEvent.ACTION_UP);
				}
			}
			return false;
		}

		private static boolean dropsReleaseAfterLongPress(View v) {
			return v instanceof TextView && ((TextView) v).isTextSelectable();
		}
	}

	public static void passPresses(View from, View to,
			BooleanSupplier selecting) {
		Object tag = from.getTag(R.id.selection_presses);
		if (tag instanceof Presses) {
			Presses presses = (Presses) tag;
			presses.to = to;
			presses.selecting = selecting;
		} else {
			Presses presses = new Presses(to, selecting);
			from.setTag(R.id.selection_presses, presses);
			from.setOnLongClickListener(presses);
			from.setOnTouchListener(presses);
		}
		nameLongPress(from);
	}

	public static void notSelectable(View v) {
		v.setTag(R.id.selection_presses, null);
		v.setOnTouchListener(null);
		if (v.isLongClickable()) {
			v.setOnLongClickListener(null);
			v.setLongClickable(false);
		}
		if (v.getTag(R.id.selection_named) == null) return;
		v.setTag(R.id.selection_named, null);
		ViewCompat.removeAccessibilityAction(v,
				AccessibilityActionCompat.ACTION_LONG_CLICK.getId());
	}

	public static void clearOnEmptyTap(RecyclerView list, SelectionBar bar) {
		GestureDetector taps = new GestureDetector(list.getContext(),
				new GestureDetector.SimpleOnGestureListener() {
					@Override
					public boolean onSingleTapUp(MotionEvent e) {
						if (bar.isShowing() && list.findChildViewUnder(
								e.getX(), e.getY()) == null) {
							bar.close();
							return true;
						}
						return false;
					}
				});
		list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
			@Override
			public boolean onInterceptTouchEvent(RecyclerView rv,
					MotionEvent e) {
				taps.onTouchEvent(e);
				return false;
			}
		});
	}
}
