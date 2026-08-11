package org.zerionproject.core.api;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface Predicate<T> {

	boolean test(T t);
}
