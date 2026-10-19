package com.professor.zerion.android.selection;

import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Paint;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.accessibility.Speech;
import com.professor.zerion.android.util.UiUtils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.ActionBarContextView;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;

@UiThread
@NotNullByDefault
public final class SelectionBar {

	public interface Host {

		Set<SelectionAction> offeredActions();

		void onSelectionAction(SelectionAction action);

		void onSelectionClosed();
	}

	private final AppCompatActivity activity;
	private final Host host;
	@Nullable
	private ActionMode mode;
	@Nullable
	private TextView title;
	@Nullable
	private View moreButton;
	@Nullable
	private PopupMenu more;
	private int count = 0;
	private boolean held = false;
	private int heldCount = 0;
	private boolean labels = true;
	private final EnumSet<SelectionAction> moved =
			EnumSet.noneOf(SelectionAction.class);
	@Nullable
	private Toolbar header;
	private final View.OnLayoutChangeListener headerResized =
			(v, left, top, right, bottom, oldLeft, oldTop, oldRight,
					oldBottom) -> {
				if (bottom - top != oldBottom - oldTop) {
					v.post(this::matchHeader);
				}
			};

	public SelectionBar(AppCompatActivity activity, Host host) {
		this.activity = activity;
		this.host = host;
	}

	public boolean isShowing() {
		return mode != null;
	}

	public int getCount() {
		return mode == null ? 0 : count;
	}

	public void show(int count) {
		if (held) {
			heldCount = Math.max(0, count);
			return;
		}
		if (count <= 0) {
			close();
			return;
		}
		this.count = count;
		boolean appearing = mode == null;
		if (appearing) {
			mode = activity.startSupportActionMode(callback);
			if (mode == null) return;
			coverHeader();
		}
		bindTitle(appearing);
		mode.invalidate();
	}

	public void refresh() {
		if (mode != null) mode.invalidate();
	}

	public void close() {
		if (held) heldCount = 0;
		dismissMore();
		if (mode != null) mode.finish();
	}

	public Set<SelectionAction> shownActions() {
		if (mode == null) return EnumSet.noneOf(SelectionAction.class);
		return SelectionAction.shown(host.offeredActions(), count);
	}

	public boolean perform(SelectionAction action) {
		if (!shownActions().contains(action)) return false;
		dismissMore();
		host.onSelectionAction(action);
		return true;
	}

	public boolean performOnly(Runnable select, SelectionAction action,
			IntSupplier selected) {
		if (isShowing()) return false;
		boolean done = false;
		held = true;
		heldCount = 0;
		try {
			select.run();
			if (SelectionAction.shown(host.offeredActions(), heldCount)
					.contains(action)) {
				host.onSelectionAction(action);
				done = true;
			} else {
				host.onSelectionClosed();
			}
		} finally {
			held = false;
			heldCount = 0;
		}
		int left = selected.getAsInt();
		if (left > 0) show(left);
		return done;
	}

	@Nullable
	public TextView getTitleView() {
		return title;
	}

	@Nullable
	public View getActionView(SelectionAction action) {
		if (mode == null || !action.isPrimary()) return null;
		MenuItem item = mode.getMenu().findItem(action.getId());
		return item == null || !item.isVisible() ? null : item.getActionView();
	}

	@Nullable
	public View getMoreButton() {
		if (mode == null) return null;
		MenuItem item = mode.getMenu().findItem(R.id.action_more);
		return item == null || !item.isVisible() ? null : moreButton;
	}

	public boolean showsLabels() {
		return labels;
	}

	@Nullable
	public PopupMenu openMore() {
		View anchor = moreButton;
		if (mode == null || anchor == null) return null;
		dismissMore();
		Set<SelectionAction> shown = shownActions();
		PopupMenu popup = new PopupMenu(new ContextThemeWrapper(activity,
				R.style.ThemeOverlay_Zerion_SelectionMenu), anchor);
		popup.inflate(R.menu.selection_more);
		Menu menu = popup.getMenu();
		boolean any = false;
		for (int i = 0; i < menu.size(); i++) {
			MenuItem item = menu.getItem(i);
			SelectionAction a = SelectionAction.byId(item.getItemId());
			boolean visible = a != null && shown.contains(a)
					&& (!a.isPrimary() || moved.contains(a));
			item.setVisible(visible);
			if (visible) any = true;
			if (visible && a == SelectionAction.DELETE) {
				item.setTitle(dangerTitle(item.getTitle()));
			}
		}
		if (!any) return null;
		popup.setOnMenuItemClickListener(item -> {
			SelectionAction a = SelectionAction.byId(item.getItemId());
			return a != null && perform(a);
		});
		popup.setOnDismissListener(p -> {
			if (more == p) more = null;
		});
		more = popup;
		popup.show();
		return popup;
	}

	private CharSequence dangerTitle(CharSequence title) {
		android.text.SpannableString coloured =
				new android.text.SpannableString(title);
		coloured.setSpan(new android.text.style.ForegroundColorSpan(
				UiUtils.resolveColorAttribute(activity,
						R.attr.zerionColorDangerText)), 0, coloured.length(),
				android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		return coloured;
	}

	private void coverHeader() {
		releaseHeader();
		header = header(activity.findViewById(android.R.id.content));
		if (header != null) header.addOnLayoutChangeListener(headerResized);
		matchHeader();
	}

	private void releaseHeader() {
		Toolbar h = header;
		header = null;
		if (h != null) h.removeOnLayoutChangeListener(headerResized);
	}

	@SuppressWarnings("RestrictedApi")
	private void matchHeader() {
		if (mode == null) return;
		View bar = activity.findViewById(
				androidx.appcompat.R.id.action_mode_bar);
		if (!(bar instanceof ActionBarContextView)) return;
		TypedArray a = activity.obtainStyledAttributes(null,
				androidx.appcompat.R.styleable.ActionMode,
				androidx.appcompat.R.attr.actionModeStyle, 0);
		int height = a.getLayoutDimension(
				androidx.appcompat.R.styleable.ActionMode_height, 0);
		a.recycle();
		Toolbar h = header;
		if (h != null) height = Math.max(height, h.getHeight());
		ActionBarContextView view = (ActionBarContextView) bar;
		if (view.getContentHeight() != height) {
			view.setContentHeight(height);
			view.requestLayout();
		}
	}

	@Nullable
	private static Toolbar header(@Nullable View v) {
		if (v == null || v.getVisibility() != View.VISIBLE) return null;
		if (v instanceof Toolbar) return (Toolbar) v;
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				Toolbar found = header(g.getChildAt(i));
				if (found != null) return found;
			}
		}
		return null;
	}

	private void dismissMore() {
		PopupMenu p = more;
		more = null;
		if (p != null) p.dismiss();
	}

	private String countText() {
		return activity.getResources().getQuantityString(
				R.plurals.selection_count, count, count);
	}

	private void bindTitle(boolean appearing) {
		TextView t = title;
		if (t == null) return;
		String text = countText();
		Speech.setText(t, text);
		if (!appearing) return;
		ViewParent parent = t.getParent();
		View pane = parent instanceof View ? (View) parent : t;
		ViewCompat.setAccessibilityPaneTitle(pane, text);
	}

	private boolean onItem(MenuItem item) {
		if (item.getItemId() == R.id.action_more) {
			openMore();
			return true;
		}
		SelectionAction a = SelectionAction.byId(item.getItemId());
		return a != null && perform(a);
	}

	private void bindItem(MenuItem item) {
		View v = item.getActionView();
		if (v == null) return;
		ImageView icon = v.findViewById(R.id.selectionActionIcon);
		TextView label = v.findViewById(R.id.selectionActionLabel);
		icon.setImageDrawable(item.getIcon());
		label.setText(item.getTitle());
		boolean isMore = item.getItemId() == R.id.action_more;
		CharSequence description = isMore
				? activity.getString(R.string.selection_more_description)
				: item.getTitle();
		v.setContentDescription(description);
		ViewCompat.setTooltipText(v, description);
		ViewCompat.setAccessibilityDelegate(v, new AccessibilityDelegateCompat() {
			@Override
			public void onInitializeAccessibilityNodeInfo(View host,
					AccessibilityNodeInfoCompat info) {
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName(Button.class.getName());
			}
		});
		if (item.getItemId() == R.id.action_delete) {
			int danger = UiUtils.resolveColorAttribute(activity,
					R.attr.zerionColorDangerText);
			ImageViewCompat.setImageTintList(icon,
					ColorStateList.valueOf(danger));
			label.setTextColor(danger);
		}
		if (isMore) moreButton = v;
		v.setOnClickListener(x -> onItem(item));
	}

	private static final float MIN_BUTTON_DP = 48f;
	private static final float MAX_LABEL_DP = 64f;
	private static final float BUTTON_PADDING_DP = 4f;
	private static final float CLOSE_DP = 52f;
	private static final float SPARE_DP = 8f;
	private static final float CHAR_WIDTH_EM = 0.55f;

	static float textWidth(TextView view, CharSequence text, float size) {
		Paint paint = new Paint(view.getPaint());
		paint.setTextSize(size);
		return Math.max(paint.measureText(text.toString()),
				text.length() * size * CHAR_WIDTH_EM);
	}

	private void fitInto(Menu menu, Set<SelectionAction> shown,
			boolean overflow) {
		float density = activity.getResources().getDisplayMetrics().density;
		float available = activity.getResources().getConfiguration()
				.screenWidthDp * density;
		List<SelectionAction> primary = new ArrayList<>();
		List<Float> widths = new ArrayList<>();
		float moreWidth = 0f;
		for (int i = 0; i < menu.size(); i++) {
			MenuItem item = menu.getItem(i);
			View v = item.getActionView();
			TextView label = v == null ? null
					: v.findViewById(R.id.selectionActionLabel);
			float w = label == null ? 0f : textWidth(label, item.getTitle(),
					label.getTextSize());
			if (item.getItemId() == R.id.action_more) {
				moreWidth = w;
				continue;
			}
			SelectionAction a = SelectionAction.byId(item.getItemId());
			if (a != null && shown.contains(a)) {
				primary.add(a);
				widths.add(w);
			}
		}
		float titleWidth = 0f;
		TextView t = title;
		if (t != null) {
			titleWidth = textWidth(t, t.getText(), t.getAutoSizeMinTextSize())
					+ t.getPaddingStart() + t.getPaddingEnd();
		}
		Fit fit = Fit.plan(primary, widths, moreWidth, overflow,
				available - SPARE_DP * density, CLOSE_DP * density,
				titleWidth, MIN_BUTTON_DP * density, MAX_LABEL_DP * density,
				BUTTON_PADDING_DP * density);
		labels = fit.labels;
		moved.clear();
		moved.addAll(fit.moved);
	}

	static final class Fit {

		final boolean labels;
		final EnumSet<SelectionAction> moved;

		private Fit(boolean labels, EnumSet<SelectionAction> moved) {
			this.labels = labels;
			this.moved = moved;
		}

		static Fit plan(List<SelectionAction> primary, List<Float> labelWidths,
				float moreLabel, boolean overflow, float available,
				float close, float title, float button, float maxLabel,
				float padding) {
			boolean labelsFit = moreLabel <= maxLabel;
			for (float w : labelWidths) {
				if (w > maxLabel) labelsFit = false;
			}
			EnumSet<SelectionAction> moved =
					EnumSet.noneOf(SelectionAction.class);
			if (labelsFit && width(primary, labelWidths, moreLabel, overflow,
					moved, true, close, title, button, padding) <= available) {
				return new Fit(true, moved);
			}
			for (SelectionAction a : new SelectionAction[] {null,
					SelectionAction.REACT, SelectionAction.DELETE}) {
				if (a != null) {
					if (!primary.contains(a)) continue;
					moved.add(a);
				}
				if (width(primary, labelWidths, moreLabel, overflow, moved,
						false, close, title, button, padding) <= available) {
					break;
				}
			}
			return new Fit(false, moved);
		}

		private static float width(List<SelectionAction> primary,
				List<Float> labelWidths, float moreLabel, boolean overflow,
				Set<SelectionAction> moved, boolean labels, float close,
				float title, float button, float padding) {
			float sum = close + title;
			for (int i = 0; i < primary.size(); i++) {
				if (moved.contains(primary.get(i))) continue;
				sum += labels ? Math.max(button, labelWidths.get(i) + padding)
						: button;
			}
			if (overflow || !moved.isEmpty()) {
				sum += labels ? Math.max(button, moreLabel + padding) : button;
			}
			return sum;
		}
	}

	private final ActionMode.Callback callback = new ActionMode.Callback() {

		@Override
		public boolean onCreateActionMode(ActionMode m, Menu menu) {
			m.getMenuInflater().inflate(R.menu.selection_bar, menu);
			for (int i = 0; i < menu.size(); i++) bindItem(menu.getItem(i));
			title = (TextView) LayoutInflater.from(activity).inflate(
					R.layout.view_selection_title, null, false);
			title.setText(countText());
			Speech.politely(title);
			m.setCustomView(title);
			return true;
		}

		@Override
		public boolean onPrepareActionMode(ActionMode m, Menu menu) {
			Set<SelectionAction> shown =
					SelectionAction.shown(host.offeredActions(), count);
			boolean overflow = false;
			for (SelectionAction a : shown) {
				if (!a.isPrimary()) overflow = true;
			}
			fitInto(menu, shown, overflow);
			boolean more = overflow || !moved.isEmpty();
			for (int i = 0; i < menu.size(); i++) {
				MenuItem item = menu.getItem(i);
				if (item.getItemId() == R.id.action_more) {
					item.setVisible(more);
				} else {
					SelectionAction a = SelectionAction.byId(item.getItemId());
					item.setVisible(a != null && shown.contains(a)
							&& !moved.contains(a));
				}
				View v = item.getActionView();
				View label = v == null ? null
						: v.findViewById(R.id.selectionActionLabel);
				if (label != null) {
					label.setVisibility(labels ? View.VISIBLE : View.GONE);
				}
			}
			if (!more) dismissMore();
			return true;
		}

		@Override
		public boolean onActionItemClicked(ActionMode m, MenuItem item) {
			return onItem(item);
		}

		@Override
		public void onDestroyActionMode(ActionMode m) {
			releaseHeader();
			dismissMore();
			mode = null;
			title = null;
			moreButton = null;
			count = 0;
			host.onSelectionClosed();
		}
	};
}
