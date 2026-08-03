package org.zerionproject.core.api.transport;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.transport.TransportConstants.REORDERING_WINDOW_SIZE;

@Immutable
@NotNullByDefault
public class IncomingKeys {

	private final SecretKey tagKey, headerKey;
	private final long timePeriod, windowBase;
	private final byte[] windowBitmap;

	public IncomingKeys(SecretKey tagKey, SecretKey headerKey,
			long timePeriod) {
		this(tagKey, headerKey, timePeriod, 0,
				new byte[REORDERING_WINDOW_SIZE / 8]);
	}

	public IncomingKeys(SecretKey tagKey, SecretKey headerKey,
			long timePeriod, long windowBase, byte[] windowBitmap) {
		this.tagKey = tagKey;
		this.headerKey = headerKey;
		this.timePeriod = timePeriod;
		this.windowBase = windowBase;
		this.windowBitmap = windowBitmap;
	}

	public SecretKey getTagKey() {
		return tagKey;
	}

	public SecretKey getHeaderKey() {
		return headerKey;
	}

	public long getTimePeriod() {
		return timePeriod;
	}

	public long getWindowBase() {
		return windowBase;
	}

	public byte[] getWindowBitmap() {
		return windowBitmap;
	}
}