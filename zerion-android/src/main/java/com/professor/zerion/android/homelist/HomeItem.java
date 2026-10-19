package com.professor.zerion.android.homelist;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public abstract class HomeItem {

	static final int TYPE_ROW = 1;
	static final int TYPE_NOTICE = 2;
	static final int TYPE_INVITE = 3;
	static final int TYPE_STATE = 4;

	private final String key;
	@Nullable
	private String idKey;

	HomeItem(String key) {
		this.key = key;
	}

	public String getKey() {
		return key;
	}

	String idKey() {
		String k = idKey;
		if (k == null) {
			k = getViewType() + ":" + key;
			idKey = k;
		}
		return k;
	}

	abstract int getViewType();

	abstract boolean showsTheSameAs(HomeItem other);
}
