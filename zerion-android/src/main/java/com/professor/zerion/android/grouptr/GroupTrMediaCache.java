package com.professor.zerion.android.grouptr;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;
import androidx.collection.LruCache;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.Locale;

import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.core.util.StringUtils.toHexString;

@ThreadSafe
@NotNullByDefault
public final class GroupTrMediaCache {

	static final String IMAGE = "i";
	static final String VIDEO = "v";

	private static final LruCache<String, Bitmap> CACHE =
			new LruCache<String, Bitmap>(16 * 1024 * 1024) {
				@Override
				protected int sizeOf(String key, Bitmap value) {
					return value.getByteCount();
				}
			};

	private GroupTrMediaCache() {
	}

	static String key(GroupTrPost p, String kind) {
		byte[] id = p.getPostId();
		if (id != null) {
			return groupPrefix(p.getGroupId()) + kind + "_" + toHexString(id);
		}
		byte[] pk = p.getSenderPubKey();
		StringBuilder sb = new StringBuilder(groupPrefix(p.getGroupId()));
		sb.append(kind).append('_').append(p.getEpoch()).append('_')
				.append(p.getTimestamp()).append('_');
		int n = Math.min(8, pk == null ? 0 : pk.length);
		for (int i = 0; i < n; i++) {
			sb.append(String.format(Locale.US, "%02x", pk[i]));
		}
		return sb.toString();
	}

	private static String groupPrefix(byte[] groupId) {
		return toHexString(groupId) + "_";
	}

	@Nullable
	static Bitmap get(String key) {
		return CACHE.get(key);
	}

	static void put(String key, Bitmap bitmap) {
		CACHE.put(key, bitmap);
	}

	static int size() {
		return CACHE.snapshot().size();
	}

	static void evictPost(GroupTrPost p) {
		CACHE.remove(key(p, IMAGE));
		CACHE.remove(key(p, VIDEO));
	}

	public static void evictPosts(byte[] groupId, Collection<byte[]> postIds) {
		String prefix = groupPrefix(groupId);
		for (byte[] id : postIds) {
			CACHE.remove(prefix + IMAGE + "_" + toHexString(id));
			CACHE.remove(prefix + VIDEO + "_" + toHexString(id));
		}
	}

	public static void evictGroup(byte[] groupId) {
		String prefix = groupPrefix(groupId);
		for (String key : CACHE.snapshot().keySet()) {
			if (key.startsWith(prefix)) CACHE.remove(key);
		}
	}

	public static void clear() {
		CACHE.evictAll();
	}
}
