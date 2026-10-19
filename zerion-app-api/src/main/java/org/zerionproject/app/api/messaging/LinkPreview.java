package org.zerionproject.app.api.messaging;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.Objects;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class LinkPreview {

	private final String url;
	private final String title;
	@Nullable
	private final String description;
	@Nullable
	private final byte[] imageData;

	public LinkPreview(String url, String title,
			@Nullable String description, @Nullable byte[] imageData) {
		this.url = url;
		this.title = title;
		this.description = description;
		this.imageData = imageData;
	}

	public String getUrl() {
		return url;
	}

	public String getTitle() {
		return title;
	}

	@Nullable
	public String getDescription() {
		return description;
	}

	@Nullable
	public byte[] getImageData() {
		return imageData;
	}

	public boolean hasImage() {
		return imageData != null && imageData.length > 0;
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) return true;
		if (!(o instanceof LinkPreview)) return false;
		LinkPreview p = (LinkPreview) o;
		return url.equals(p.url) && title.equals(p.title)
				&& Objects.equals(description, p.description)
				&& Arrays.equals(imageData, p.imageData);
	}

	@Override
	public int hashCode() {
		return Objects.hash(url, title, description)
				+ 31 * Arrays.hashCode(imageData);
	}
}
