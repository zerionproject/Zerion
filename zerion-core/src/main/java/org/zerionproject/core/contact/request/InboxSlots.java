package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SOLVED_SLOTS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.UNSOLVED_HOLD_MS;

@ThreadSafe
@NotNullByDefault
final class InboxSlots {

	@Nullable
	private Object unsolved = null;
	private long unsolvedSince = 0;
	private final Set<Object> solved = new HashSet<>();

	@Nullable
	synchronized Object connected(Object c, long now) {
		Object evicted = unsolved;
		unsolved = c;
		unsolvedSince = now;
		return evicted;
	}

	@Nullable
	synchronized Object expireUnsolved(long now) {
		if (unsolved != null && now - unsolvedSince >= UNSOLVED_HOLD_MS) {
			Object expired = unsolved;
			unsolved = null;
			return expired;
		}
		return null;
	}

	synchronized boolean solutionReceived(Object c) {
		if (unsolved != c) return false;
		unsolved = null;
		return true;
	}

	synchronized boolean acquireSolved(Object c) {
		if (solved.size() >= SOLVED_SLOTS) return false;
		solved.add(c);
		return true;
	}

	synchronized void closed(Object c) {
		if (unsolved == c) unsolved = null;
		solved.remove(c);
	}

	synchronized int solvedCount() {
		return solved.size();
	}

	synchronized boolean isSolved(Object c) {
		return solved.contains(c);
	}

	synchronized boolean hasUnsolved() {
		return unsolved != null;
	}
}
