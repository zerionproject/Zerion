package com.professor.zerion.android.homelist;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class HomeNotice extends HomeItem {

	private final String text;
	private final String payload;

	public HomeNotice(String key, String text, String payload) {
		super(key);
		this.text = text;
		this.payload = payload;
	}

	public String getText() {
		return text;
	}

	public String getPayload() {
		return payload;
	}

	@Override
	int getViewType() {
		return TYPE_NOTICE;
	}

	@Override
	boolean showsTheSameAs(HomeItem other) {
		return other instanceof HomeNotice
				&& getKey().equals(other.getKey())
				&& text.equals(((HomeNotice) other).text);
	}
}
