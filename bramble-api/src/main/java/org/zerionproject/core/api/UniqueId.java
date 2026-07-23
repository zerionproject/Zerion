package org.zerionproject.core.api;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class UniqueId extends Bytes {

	public static final int LENGTH = 32;

	public UniqueId(byte[] id) {
		super(id);
		if (id.length != LENGTH) throw new IllegalArgumentException();
	}
}
