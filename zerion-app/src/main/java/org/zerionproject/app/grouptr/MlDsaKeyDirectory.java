package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.util.StringUtils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

@NotNullByDefault
final class MlDsaKeyDirectory {

	interface Source {
		@Nullable
		byte[] lookup(byte[] ed25519PubKey) throws DbException;
	}

	private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
	private final Source[] sources;

	MlDsaKeyDirectory(Source... sources) {
		this.sources = sources;
	}

	@Nullable
	byte[] lookup(byte[] ed25519PubKey) throws DbException {
		String key = StringUtils.toHexString(ed25519PubKey);
		byte[] cached = cache.get(key);
		if (cached != null) return cached;
		for (Source s : sources) {
			byte[] found = s.lookup(ed25519PubKey);
			if (found != null) {
				byte[] existing = cache.putIfAbsent(key, found);
				return existing != null ? existing : found;
			}
		}
		return null;
	}

	void invalidate() {
		cache.clear();
	}
}
