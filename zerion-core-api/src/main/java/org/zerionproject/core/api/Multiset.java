package org.zerionproject.core.api;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
@NotNullByDefault
public class Multiset<T> {

	private final Map<T, Integer> map = new HashMap<>();

	private int total = 0;

	public int getTotal() {
		return total;
	}

	public int getUnique() {
		return map.size();
	}

	public int getCount(T t) {
		Integer count = map.get(t);
		return count == null ? 0 : count;
	}

	public int add(T t) {
		Integer count = map.get(t);
		if (count == null) count = 0;
		map.put(t, count + 1);
		total++;
		return count + 1;
	}

	public int remove(T t) {
		Integer count = map.get(t);
		if (count == null) throw new NoSuchElementException();
		if (count == 1) map.remove(t);
		else map.put(t, count - 1);
		total--;
		return count - 1;
	}

	public int removeAll(T t) {
		Integer count = map.remove(t);
		if (count == null) return 0;
		total -= count;
		return count;
	}

	public boolean contains(T t) {
		return map.containsKey(t);
	}

	public void clear() {
		map.clear();
		total = 0;
	}

	public Set<T> keySet() {
		return Collections.unmodifiableSet(map.keySet());
	}
}
