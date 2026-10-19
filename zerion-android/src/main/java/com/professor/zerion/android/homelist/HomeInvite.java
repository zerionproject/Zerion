package com.professor.zerion.android.homelist;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class HomeInvite extends HomeItem {

	private final String title;
	private final String name;
	private final byte[] id;

	public HomeInvite(String key, String title, String name, byte[] id) {
		super(key);
		this.title = title;
		this.name = name;
		this.id = id.clone();
	}

	public String getTitle() {
		return title;
	}

	public String getName() {
		return name;
	}

	public byte[] getId() {
		return id.clone();
	}

	@Override
	int getViewType() {
		return TYPE_INVITE;
	}

	@Override
	boolean showsTheSameAs(HomeItem other) {
		return other instanceof HomeInvite
				&& getKey().equals(other.getKey())
				&& title.equals(((HomeInvite) other).title);
	}
}
