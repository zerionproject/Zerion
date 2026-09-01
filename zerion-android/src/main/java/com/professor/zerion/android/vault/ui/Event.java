package com.professor.zerion.android.vault.ui;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * One-shot event holder for LiveData. A sticky LiveData redelivers its last
 * value to every newly registered observer, which turns a single-use action
 * (such as showing the send authentication prompt) into a stale, repeated
 * trigger on view recreation. Wrapping the value here makes it deliverable
 * exactly once: the first observer consumes the content, and any redelivery on
 * recreation returns nothing.
 */
@NotNullByDefault
public final class Event<T> {

	private final T content;
	private boolean handled;

	public Event(T content) {
		this.content = content;
	}

	@Nullable
	public T getIfNotHandled() {
		if (handled) {
			return null;
		}
		handled = true;
		return content;
	}

	public boolean isHandled() {
		return handled;
	}
}
