package com.professor.zerion.android.homelist;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
final class HomeState extends HomeItem {

	@DrawableRes
	final int icon;
	@StringRes
	final int title;
	@StringRes
	final int body;
	final boolean retry;

	HomeState(String key, @DrawableRes int icon, @StringRes int title,
			@StringRes int body, boolean retry) {
		super(key);
		this.icon = icon;
		this.title = title;
		this.body = body;
		this.retry = retry;
	}

	@Override
	int getViewType() {
		return TYPE_STATE;
	}

	@Override
	boolean showsTheSameAs(HomeItem other) {
		if (!(other instanceof HomeState)) return false;
		HomeState o = (HomeState) other;
		return getKey().equals(o.getKey()) && icon == o.icon
				&& title == o.title && body == o.body && retry == o.retry;
	}
}
