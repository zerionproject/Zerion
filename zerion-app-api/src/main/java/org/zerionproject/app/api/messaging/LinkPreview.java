package org.zerionproject.app.api.messaging;

import org.briarproject.nullsafety.NotNullByDefault;

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
}
