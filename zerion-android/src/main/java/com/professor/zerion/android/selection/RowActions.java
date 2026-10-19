package com.professor.zerion.android.selection;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import androidx.annotation.IdRes;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;

@UiThread
@NotNullByDefault
public final class RowActions {

	public interface Row {

		Set<SelectionAction> offered();

		boolean perform(SelectionAction action);
	}

	static final List<SelectionAction> ORDER = Arrays.asList(
			SelectionAction.REPLY, SelectionAction.REACT,
			SelectionAction.COPY, SelectionAction.DELETE,
			SelectionAction.MEMBER);

	private static final Delegate DELEGATE = new Delegate();

	private RowActions() {
	}

	public static Set<SelectionAction> forRow(
			Collection<SelectionAction> offered) {
		EnumSet<SelectionAction> out = EnumSet.noneOf(SelectionAction.class);
		for (SelectionAction a : SelectionAction.shown(offered, 1)) {
			if (ORDER.contains(a)) out.add(a);
		}
		return out;
	}

	public static Set<SelectionAction> none() {
		return EnumSet.noneOf(SelectionAction.class);
	}

	public static void bind(View v, @Nullable Row row) {
		v.setTag(R.id.row_actions, row);
		if (ViewCompat.getAccessibilityDelegate(v) != DELEGATE) {
			ViewCompat.setAccessibilityDelegate(v, DELEGATE);
		}
	}

	private static final class Extra {

		final CharSequence label;
		Runnable action;

		Extra(CharSequence label, Runnable action) {
			this.label = label;
			this.action = action;
		}
	}

	public static void extra(View v, @IdRes int id,
			@Nullable CharSequence label, @Nullable Runnable action) {
		Object tag = v.getTag(id);
		Extra shown = tag instanceof Extra ? (Extra) tag : null;
		if (label == null || action == null) {
			if (shown == null) return;
			v.setTag(id, null);
			ViewCompat.removeAccessibilityAction(v, id);
			return;
		}
		if (shown != null && TextUtils.equals(shown.label, label)) {
			shown.action = action;
			return;
		}
		v.setTag(id, new Extra(label, action));
		ViewCompat.replaceAccessibilityAction(v,
				new AccessibilityActionCompat(id, label), label,
				(view, args) -> {
					Object now = view.getTag(id);
					if (!(now instanceof Extra)) return false;
					((Extra) now).action.run();
					return true;
				});
	}

	@StringRes
	static int label(SelectionAction a) {
		switch (a) {
			case REPLY:
				return R.string.btn_reply;
			case REACT:
				return R.string.action_react;
			case COPY:
				return R.string.copy_text;
			case DELETE:
				return R.string.delete;
			default:
				return R.string.selection_member;
		}
	}

	@Nullable
	private static Row row(View v) {
		Object tag = v.getTag(R.id.row_actions);
		return tag instanceof Row ? (Row) tag : null;
	}

	private static final class Delegate extends AccessibilityDelegateCompat {

		@Override
		public void onInitializeAccessibilityNodeInfo(View host,
				AccessibilityNodeInfoCompat info) {
			super.onInitializeAccessibilityNodeInfo(host, info);
			Row row = row(host);
			if (row == null) return;
			Set<SelectionAction> offered = forRow(row.offered());
			for (SelectionAction a : ORDER) {
				if (!offered.contains(a)) continue;
				info.addAction(new AccessibilityActionCompat(a.getId(),
						host.getContext().getString(label(a))));
			}
		}

		@Override
		public boolean performAccessibilityAction(View host, int action,
				@Nullable Bundle args) {
			Row row = row(host);
			SelectionAction a = SelectionAction.byId(action);
			if (row != null && a != null && ORDER.contains(a)) {
				return forRow(row.offered()).contains(a) && row.perform(a);
			}
			return super.performAccessibilityAction(host, action, args);
		}
	}
}
