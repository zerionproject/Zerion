package org.zerionproject.app.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.core.util.StringUtils.toHexString;

@ThreadSafe
@NotNullByDefault
class GroupTrOwnCopies {

	@Immutable
	@NotNullByDefault
	static final class Holder {

		final ContactId contact;
		final String member;

		private Holder(ContactId contact, String member) {
			this.contact = contact;
			this.member = member;
		}
	}

	@Immutable
	@NotNullByDefault
	static final class Ref {

		final Holder holder;
		final MessageId id;
		final boolean flooded;

		private Ref(Holder holder, MessageId id, boolean flooded) {
			this.holder = holder;
			this.id = id;
			this.flooded = flooded;
		}
	}

	@NotNullByDefault
	static final class Changed {

		final byte[] groupId;
		final List<byte[]> postIds = new ArrayList<>(1);

		private Changed(byte[] groupId) {
			this.groupId = groupId;
		}
	}

	@NotNullByDefault
	private static final class Post {

		final byte[] groupId;
		final byte[] postId;
		final List<Ref> refs = new ArrayList<>(2);

		private Post(byte[] groupId, byte[] postId) {
			this.groupId = groupId;
			this.postId = postId;
		}
	}

	private final Object lock = new Object();
	private final Map<String, Map<String, Post>> byGroup = new HashMap<>();
	private final Map<MessageId, Post> postOf = new HashMap<>();
	private final Map<ContactId, Holder> holders = new HashMap<>();

	void add(byte[] groupId, byte[] postId, ContactId contact,
			byte[] memberKey, MessageId id, boolean flooded) {
		synchronized (lock) {
			Post held = postOf.get(id);
			if (held != null) {
				if (!flooded) return;
				for (int i = 0; i < held.refs.size(); i++) {
					Ref r = held.refs.get(i);
					if (r.id.equals(id) && !r.flooded) {
						held.refs.set(i, new Ref(r.holder, id, true));
					}
				}
				return;
			}
			Holder holder = holders.get(contact);
			if (holder == null) {
				holder = new Holder(contact, toHexString(memberKey));
				holders.put(contact, holder);
			}
			Map<String, Post> posts = byGroup.computeIfAbsent(
					toHexString(groupId), k -> new LinkedHashMap<>());
			String key = toHexString(postId);
			Post post = posts.get(key);
			if (post == null) {
				post = new Post(groupId, postId);
				posts.put(key, post);
			}
			post.refs.add(new Ref(holder, id, flooded));
			postOf.put(id, post);
		}
	}

	List<Ref> copies(String group, String post) {
		synchronized (lock) {
			Map<String, Post> posts = byGroup.get(group);
			Post p = posts == null ? null : posts.get(post);
			return p == null ? new ArrayList<>() : new ArrayList<>(p.refs);
		}
	}

	void retain(String group, Set<String> posts) {
		synchronized (lock) {
			Map<String, Post> held = byGroup.get(group);
			if (held == null) return;
			Iterator<Map.Entry<String, Post>> it = held.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<String, Post> e = it.next();
				if (posts.contains(e.getKey())) continue;
				for (Ref r : e.getValue().refs) postOf.remove(r.id);
				it.remove();
			}
			if (held.isEmpty()) byGroup.remove(group);
		}
	}

	void drop(MessageId id) {
		synchronized (lock) {
			Post p = postOf.remove(id);
			if (p != null) p.refs.removeIf(r -> r.id.equals(id));
		}
	}

	void forget(String group) {
		synchronized (lock) {
			Map<String, Post> held = byGroup.remove(group);
			if (held == null) return;
			for (Post p : held.values()) {
				for (Ref r : p.refs) postOf.remove(r.id);
			}
		}
	}

	Collection<Changed> changed(Collection<MessageId> ids) {
		Map<Post, Boolean> seen = new java.util.IdentityHashMap<>();
		Map<String, Changed> out = new LinkedHashMap<>();
		synchronized (lock) {
			if (postOf.isEmpty()) return out.values();
			for (MessageId id : ids) {
				Post p = postOf.get(id);
				if (p == null || seen.put(p, Boolean.TRUE) != null) continue;
				String group = toHexString(p.groupId);
				Changed c = out.get(group);
				if (c == null) {
					c = new Changed(p.groupId);
					out.put(group, c);
				}
				c.postIds.add(p.postId);
			}
		}
		return out.values();
	}
}
