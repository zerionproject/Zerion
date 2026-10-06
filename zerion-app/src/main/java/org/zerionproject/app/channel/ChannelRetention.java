package org.zerionproject.app.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
final class ChannelRetention {

	interface Shape<T> {

		long post(T item);

		String owner(T item);

		long bytes(T item);
	}

	static final class Limits {

		final int perOwner, perPost, total;
		final long bytes;

		Limits(int perOwner, int perPost, int total, long bytes) {
			this.perOwner = perOwner;
			this.perPost = perPost;
			this.total = total;
			this.bytes = bytes;
		}
	}

	private ChannelRetention() {
	}

	@Nullable
	static <T> List<T> admit(List<T> existing, T candidate, Limits limits,
			Shape<T> shape) {
		if (shape.bytes(candidate) > limits.bytes) return null;
		List<T> out = new ArrayList<>(existing.size() + 1);
		out.addAll(existing);
		out.add(candidate);
		String owner = shape.owner(candidate);
		long post = shape.post(candidate);
		while (true) {
			int victim = -1;
			if (count(out, shape, owner, null) > limits.perOwner) {
				victim = oldest(out, shape, owner, null, candidate);
			} else if (count(out, shape, null, post) > limits.perPost) {
				victim = oldest(out, shape, null, post, candidate);
			} else if (out.size() > limits.total
					|| bytes(out, shape) > limits.bytes) {
				Long fullest = fullestPost(out, shape, candidate);
				if (fullest != null) {
					victim = oldest(out, shape, null, fullest, candidate);
				}
			} else {
				return out;
			}
			if (victim < 0) return null;
			out.remove(victim);
		}
	}

	@Nullable
	static <T> List<T> admit(List<T> existing, T candidate, Limits all,
			Limits anonymous, Shape<T> shape,
			java.util.function.Predicate<T> known) {
		if (shape.bytes(candidate) > all.bytes) return null;
		boolean candidateKnown = known.test(candidate);
		if (!candidateKnown && shape.bytes(candidate) > anonymous.bytes) {
			return null;
		}
		List<T> out = new ArrayList<>(existing.size() + 1);
		out.addAll(existing);
		out.add(candidate);
		String owner = shape.owner(candidate);
		long post = shape.post(candidate);
		int ownerLimit = candidateKnown ? all.perOwner : anonymous.perOwner;
		while (true) {
			int victim;
			if (count(out, shape, owner, null) > ownerLimit) {
				victim = oldest(out, shape, owner, null, candidate);
			} else if (!candidateKnown && countClass(out, shape, known, false,
					post) > anonymous.perPost) {
				victim = oldestOfClass(out, shape, known, false, post,
						candidate);
			} else if (!candidateKnown && (countClass(out, shape, known,
					false, null) > anonymous.total
					|| bytesOfClass(out, shape, known, false)
					> anonymous.bytes)) {
				victim = oldestInFullest(out, shape, known, false, candidate);
			} else if (count(out, shape, null, post) > all.perPost) {
				victim = oldestOfClass(out, shape, known, false, post,
						candidate);
				if (victim < 0 && candidateKnown) {
					victim = oldestOfClass(out, shape, known, true, post,
							candidate);
				}
			} else if (out.size() > all.total
					|| bytes(out, shape) > all.bytes) {
				victim = oldestInFullest(out, shape, known, false, candidate);
				if (victim < 0 && candidateKnown) {
					victim = oldestInFullest(out, shape, known, true,
							candidate);
				}
			} else {
				return out;
			}
			if (victim < 0) return null;
			out.remove(victim);
		}
	}

	private static <T> int countClass(List<T> items, Shape<T> shape,
			java.util.function.Predicate<T> known, boolean knownClass,
			@Nullable Long post) {
		int n = 0;
		for (T item : items) {
			if (known.test(item) != knownClass) continue;
			if (post != null && post != shape.post(item)) continue;
			n++;
		}
		return n;
	}

	private static <T> long bytesOfClass(List<T> items, Shape<T> shape,
			java.util.function.Predicate<T> known, boolean knownClass) {
		long total = 0;
		for (T item : items) {
			if (known.test(item) == knownClass) total += shape.bytes(item);
		}
		return total;
	}

	private static <T> int oldestOfClass(List<T> items, Shape<T> shape,
			java.util.function.Predicate<T> known, boolean knownClass,
			@Nullable Long post, T keep) {
		for (int i = 0; i < items.size(); i++) {
			T item = items.get(i);
			if (item == keep || known.test(item) != knownClass) continue;
			if (post != null && post != shape.post(item)) continue;
			return i;
		}
		return -1;
	}

	private static <T> int oldestInFullest(List<T> items, Shape<T> shape,
			java.util.function.Predicate<T> known, boolean knownClass,
			T keep) {
		Map<Long, Integer> perPost = new HashMap<>();
		for (T item : items) {
			if (item == keep || known.test(item) != knownClass) continue;
			perPost.merge(shape.post(item), 1, Integer::sum);
		}
		Long best = null;
		int most = 0;
		for (Map.Entry<Long, Integer> e : perPost.entrySet()) {
			if (e.getValue() > most || (e.getValue() == most && best != null
					&& e.getKey() < best)) {
				best = e.getKey();
				most = e.getValue();
			}
		}
		if (best == null) return -1;
		return oldestOfClass(items, shape, known, knownClass, best, keep);
	}

	static <T> List<T> fit(List<T> items, Limits limits, Shape<T> shape) {
		if (items.size() <= limits.total && bytes(items, shape) <= limits.bytes
				&& withinPerKey(items, limits, shape)) {
			return items;
		}
		Map<Long, Integer> perPost = new HashMap<>();
		Map<String, Integer> perOwner = new HashMap<>();
		Map<T, Boolean> kept = new IdentityHashMap<>();
		long used = 0;
		for (int i = items.size() - 1; i >= 0; i--) {
			if (kept.size() >= limits.total) break;
			T item = items.get(i);
			int p = perPost.getOrDefault(shape.post(item), 0);
			int o = perOwner.getOrDefault(shape.owner(item), 0);
			long b = shape.bytes(item);
			if (p >= limits.perPost || o >= limits.perOwner
					|| used + b > limits.bytes) {
				continue;
			}
			perPost.put(shape.post(item), p + 1);
			perOwner.put(shape.owner(item), o + 1);
			used += b;
			kept.put(item, Boolean.TRUE);
		}
		List<T> out = new ArrayList<>(kept.size());
		for (T item : items) {
			if (kept.containsKey(item)) out.add(item);
		}
		return out;
	}

	static <T> List<T> retainPosts(List<T> items, Set<Long> posts,
			Shape<T> shape) {
		List<T> out = new ArrayList<>(items.size());
		for (T item : items) {
			if (posts.contains(shape.post(item))) out.add(item);
		}
		return out.size() == items.size() ? items : out;
	}

	static <T> long bytes(List<T> items, Shape<T> shape) {
		long total = 0;
		for (T item : items) total += shape.bytes(item);
		return total;
	}

	private static <T> boolean withinPerKey(List<T> items, Limits limits,
			Shape<T> shape) {
		Map<Long, Integer> perPost = new HashMap<>();
		Map<String, Integer> perOwner = new HashMap<>();
		for (T item : items) {
			int p = perPost.merge(shape.post(item), 1, Integer::sum);
			int o = perOwner.merge(shape.owner(item), 1, Integer::sum);
			if (p > limits.perPost || o > limits.perOwner) return false;
		}
		return true;
	}

	private static <T> int count(List<T> items, Shape<T> shape,
			@Nullable String owner, @Nullable Long post) {
		int n = 0;
		for (T item : items) {
			if (owner != null && owner.equals(shape.owner(item))) n++;
			if (post != null && post == shape.post(item)) n++;
		}
		return n;
	}

	private static <T> int oldest(List<T> items, Shape<T> shape,
			@Nullable String owner, @Nullable Long post, T keep) {
		for (int i = 0; i < items.size(); i++) {
			T item = items.get(i);
			if (item == keep) continue;
			if (owner != null && !owner.equals(shape.owner(item))) continue;
			if (post != null && post != shape.post(item)) continue;
			return i;
		}
		return -1;
	}

	@Nullable
	private static <T> Long fullestPost(List<T> items, Shape<T> shape,
			T keep) {
		Map<Long, Integer> perPost = new HashMap<>();
		for (T item : items) {
			if (item == keep) continue;
			perPost.merge(shape.post(item), 1, Integer::sum);
		}
		Long best = null;
		int most = 0;
		for (Map.Entry<Long, Integer> e : perPost.entrySet()) {
			if (e.getValue() > most
					|| (e.getValue() == most && best != null
					&& e.getKey() < best)) {
				best = e.getKey();
				most = e.getValue();
			}
		}
		return best;
	}
}
