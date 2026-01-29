package com.professor.zerion.android.util;

import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;

import com.google.android.material.snackbar.Snackbar;

import com.professor.zerion.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import static androidx.core.content.ContextCompat.getColor;

@NotNullByDefault
public class ZerionSnackbarBuilder {

	@ColorRes
	@Nullable
	private Integer backgroundResId = null;
	@StringRes
	private int actionResId;
	@Nullable
	private OnClickListener onClickListener;
	@Nullable
	private View anchorView = null;
	private int bottomMargin = 0;

	public Snackbar make(View view, CharSequence text, int duration) {
		Snackbar s = Snackbar.make(view, text, duration);
		if (backgroundResId != null) {
			s.setBackgroundTint(getColor(view.getContext(), backgroundResId));
			s.setTextColor(
					getColor(view.getContext(), R.color.md_theme_onSecondary));
		}
		if (onClickListener != null) {
			s.setActionTextColor(getColor(view.getContext(),
					R.color.zerion_button_text_positive));
			s.setAction(actionResId, onClickListener);
		}
		if (anchorView != null) {
			s.setAnchorView(anchorView);
		}
		if (bottomMargin > 0) {
			View snackbarView = s.getView();
			ViewGroup.MarginLayoutParams params =
					(ViewGroup.MarginLayoutParams) snackbarView.getLayoutParams();
			params.bottomMargin = bottomMargin;
			snackbarView.setLayoutParams(params);
		}
		return s;
	}

	public Snackbar make(View view, @StringRes int resId, int duration) {
		return make(view, view.getResources().getText(resId), duration);
	}

	public ZerionSnackbarBuilder setBackgroundColor(
			@ColorRes int backgroundResId) {
		this.backgroundResId = backgroundResId;
		return this;
	}

	public ZerionSnackbarBuilder setAction(@StringRes int actionResId,
			OnClickListener onClickListener) {
		this.actionResId = actionResId;
		this.onClickListener = onClickListener;
		return this;
	}

	public ZerionSnackbarBuilder setAnchorView(View anchorView) {
		this.anchorView = anchorView;
		return this;
	}

	public ZerionSnackbarBuilder setBottomMargin(int margin) {
		this.bottomMargin = margin;
		return this;
	}

}
