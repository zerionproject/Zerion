package com.professor.zerion.android.homelist;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.attachment.AttachmentHeader;

import java.util.Objects;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class HomeRow extends HomeItem {

	public static final int UNREAD_CAP = 99;

	public enum Presence {NONE, ONLINE, OFFLINE}

	private final String name;
	@Nullable
	private final AttachmentHeader avatar;
	private final long time;
	@Nullable
	private final String sender;
	@Nullable
	private final String preview;
	private final int unread;
	private final boolean muted;
	private final boolean pinned;
	private final boolean hourPrecision;
	private final Presence presence;
	private final long expiresAt;
	@Nullable
	private final Object payload;

	private HomeRow(Builder b) {
		super(b.key);
		name = b.name;
		avatar = b.avatar;
		time = b.time;
		sender = b.sender;
		preview = b.preview;
		unread = Math.max(0, b.unread);
		muted = b.muted;
		pinned = b.pinned;
		hourPrecision = b.hourPrecision;
		presence = b.presence;
		expiresAt = b.expiresAt;
		payload = b.payload;
	}

	public static Builder builder(String key, String name) {
		return new Builder(key, name);
	}

	public String getName() {
		return name;
	}

	@Nullable
	public AttachmentHeader getAvatar() {
		return avatar;
	}

	public long getTime() {
		return time;
	}

	public boolean isHourPrecision() {
		return hourPrecision;
	}

	@Nullable
	public String getSender() {
		return sender;
	}

	@Nullable
	public String getPreview() {
		return preview;
	}

	public int getUnread() {
		return unread;
	}

	public boolean isMuted() {
		return muted;
	}

	public boolean isPinned() {
		return pinned;
	}

	public Presence getPresence() {
		return presence;
	}

	public long getExpiresAt() {
		return expiresAt;
	}

	@Nullable
	public Object getPayload() {
		return payload;
	}

	@Override
	int getViewType() {
		return TYPE_ROW;
	}

	@Override
	boolean showsTheSameAs(HomeItem other) {
		if (!(other instanceof HomeRow)) return false;
		HomeRow o = (HomeRow) other;
		return getKey().equals(o.getKey()) && name.equals(o.name)
				&& Objects.equals(avatar, o.avatar) && time == o.time
				&& Objects.equals(sender, o.sender)
				&& Objects.equals(preview, o.preview) && unread == o.unread
				&& muted == o.muted && pinned == o.pinned
				&& hourPrecision == o.hourPrecision
				&& presence == o.presence;
	}

	@NotNullByDefault
	public static final class Builder {

		private final String key;
		private final String name;
		@Nullable
		private AttachmentHeader avatar;
		private long time;
		@Nullable
		private String sender;
		@Nullable
		private String preview;
		private int unread;
		private boolean muted;
		private boolean pinned;
		private boolean hourPrecision;
		private Presence presence = Presence.NONE;
		private long expiresAt = Long.MAX_VALUE;
		@Nullable
		private Object payload;

		private Builder(String key, String name) {
			this.key = key;
			this.name = name;
		}

		public Builder avatar(@Nullable AttachmentHeader avatar) {
			this.avatar = avatar;
			return this;
		}

		public Builder time(long time) {
			this.time = time;
			return this;
		}

		public Builder preview(@Nullable String sender,
				@Nullable String preview) {
			this.sender = sender;
			this.preview = preview;
			return this;
		}

		public Builder unread(int unread) {
			this.unread = unread;
			return this;
		}

		public Builder muted(boolean muted) {
			this.muted = muted;
			return this;
		}

		public Builder pinned(boolean pinned) {
			this.pinned = pinned;
			return this;
		}

		public Builder hourPrecision(boolean hourPrecision) {
			this.hourPrecision = hourPrecision;
			return this;
		}

		public Builder presence(Presence presence) {
			this.presence = presence;
			return this;
		}

		public Builder expiresAt(long expiresAt) {
			this.expiresAt = expiresAt;
			return this;
		}

		public Builder payload(@Nullable Object payload) {
			this.payload = payload;
			return this;
		}

		public HomeRow build() {
			return new HomeRow(this);
		}
	}
}
