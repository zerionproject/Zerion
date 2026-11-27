package org.zerionproject.app.api.messaging;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

@NotNullByDefault
public class ViewOnceMedia {

	private final String contentType;
	private final byte[] data;

	public ViewOnceMedia(String contentType, byte[] data) {
		this.contentType = contentType;
		this.data = data;
	}

	public String getContentType() {
		return contentType;
	}

	public byte[] getData() {
		return data;
	}

	public boolean isVideo() {
		return isVideoType(contentType);
	}

	public void wipe() {
		Arrays.fill(data, (byte) 0);
	}

	public static boolean isVideoType(String contentType) {
		return contentType.startsWith("video/");
	}

	public static boolean isAllowedType(String contentType) {
		return contentType.startsWith("image/") || isVideoType(contentType);
	}
}
