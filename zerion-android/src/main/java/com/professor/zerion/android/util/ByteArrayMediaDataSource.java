package com.professor.zerion.android.util;

import android.media.MediaDataSource;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class ByteArrayMediaDataSource extends MediaDataSource {

	private final byte[] data;

	public ByteArrayMediaDataSource(byte[] data) {
		this.data = data;
	}

	@Override
	public int readAt(long position, byte[] buffer, int offset, int size) {
		if (position < 0 || position >= data.length) return -1;
		int n = (int) Math.min(size, data.length - position);
		System.arraycopy(data, (int) position, buffer, offset, n);
		return n;
	}

	@Override
	public long getSize() {
		return data.length;
	}

	@Override
	public void close() {
	}
}
