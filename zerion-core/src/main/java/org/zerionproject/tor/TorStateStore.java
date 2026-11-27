package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface TorStateStore {

	@Nullable
	StoredState load();

	boolean save(StoredState state);

	void clear();

	final class StoredState {

		public final byte[] bytes;
		public final long modifiedMs;

		public StoredState(byte[] bytes, long modifiedMs) {
			this.bytes = bytes;
			this.modifiedMs = modifiedMs;
		}
	}
}
