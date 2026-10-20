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

	interface GroupSource {
		@Nullable
		byte[] lookup(byte[] groupId, byte[] ed25519PubKey) throws DbException;
	}

	private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
	private final Map<String, byte[]> groupCache = new ConcurrentHashMap<>();
	@Nullable
	private final GroupSource groupSource;
	private final Source[] sources;

	MlDsaKeyDirectory(Source... sources) {
		this(null, sources);
	}

	MlDsaKeyDirectory(@Nullable GroupSource groupSource, Source... sources) {
		this.groupSource = groupSource;
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

	@Nullable
	byte[] lookupIn(byte[] groupId, byte[] ed25519PubKey) throws DbException {
		if (groupSource == null) return null;
		String key = StringUtils.toHexString(groupId) + ":"
				+ StringUtils.toHexString(ed25519PubKey);
		byte[] cached = groupCache.get(key);
		if (cached != null) return cached;
		byte[] found = groupSource.lookup(groupId, ed25519PubKey);
		if (found == null) return null;
		byte[] existing = groupCache.putIfAbsent(key, found);
		return existing != null ? existing : found;
	}

	void invalidate() {
		cache.clear();
		groupCache.clear();
	}
}
