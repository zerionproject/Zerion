package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.util.StringUtils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * Resolves a peer's ML-DSA public key from its Ed25519 identity key. Hits
 * are cached until {@link #invalidate()}; a miss is never cached, because
 * the key may arrive with the next member list or contact and a remembered
 * miss would keep refusing that peer's signatures until an unrelated
 * contact event happened to clear it.
 */
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
