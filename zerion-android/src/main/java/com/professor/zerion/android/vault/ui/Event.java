package com.professor.zerion.android.vault.ui;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

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
