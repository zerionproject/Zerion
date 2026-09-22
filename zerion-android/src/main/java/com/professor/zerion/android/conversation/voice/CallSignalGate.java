package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.LongSupplier;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Admission control for incoming call signalling. An unauthenticated-in-time
 * CALL_OFFER previously rang the full-screen call activity with no freshness,
 * rate or duplicate check, so a contact could replay old offers queued while
 * offline, storm the callee with rings, or force repeated call launches. This
 * gate rejects stale offers, rate-limits offers per contact, and de-duplicates
 * call ids, and it decides whether a CALL_ANSWER is admissible for our current
 * role and state.
 */
@ThreadSafe
@NotNullByDefault
public class CallSignalGate {

	private static final long DEFAULT_FRESHNESS_MS = 60_000L;
	private static final long DEFAULT_MIN_OFFER_INTERVAL_MS = 3_000L;
	private static final int DEFAULT_MAX_OFFERS_PER_WINDOW = 5;
	private static final long DEFAULT_OFFER_WINDOW_MS = 60_000L;
	private static final int MAX_TRACKED_CONTACTS = 256;
	private static final int MAX_SEEN_CALL_IDS = 512;

	private final long freshnessMs;
	private final long minOfferIntervalMs;
	private final int maxOffersPerWindow;
	private final long offerWindowMs;
	private final LongSupplier clock;

	@GuardedBy("this")
	private final Map<String, long[]> perContact =
			new LinkedHashMap<String, long[]>(16, 0.75f, false) {
				@Override
				protected boolean removeEldestEntry(
						Map.Entry<String, long[]> e) {
					return size() > MAX_TRACKED_CONTACTS;
				}
			};

	@GuardedBy("this")
	private final LinkedHashSet<String> seenCallIds = new LinkedHashSet<>();

	public CallSignalGate(LongSupplier clock) {
		this(clock, DEFAULT_FRESHNESS_MS, DEFAULT_MIN_OFFER_INTERVAL_MS,
				DEFAULT_MAX_OFFERS_PER_WINDOW, DEFAULT_OFFER_WINDOW_MS);
	}

	public CallSignalGate(LongSupplier clock, long freshnessMs,
			long minOfferIntervalMs, int maxOffersPerWindow,
			long offerWindowMs) {
		this.clock = clock;
		this.freshnessMs = freshnessMs;
		this.minOfferIntervalMs = minOfferIntervalMs;
		this.maxOffersPerWindow = maxOffersPerWindow;
		this.offerWindowMs = offerWindowMs;
	}

	/**
	 * Decides whether an incoming CALL_OFFER should ring. Rejects an offer that
	 * is stale or far in the future, arrives too soon after or too often
	 * relative to earlier offers from the same contact, or repeats a call id
	 * that has already been admitted.
	 */
	public synchronized boolean admitOffer(String contactKey, String callId,
			long offerTimestamp) {
		long now = clock.getAsLong();
		if (Math.abs(now - offerTimestamp) > freshnessMs) return false;
		if (seenCallIds.contains(callId)) return false;
		long[] state = perContact.get(contactKey);
		if (state == null) {
			state = new long[] {Long.MIN_VALUE, now, 0};
			perContact.put(contactKey, state);
		}
		if (now - state[1] > offerWindowMs) {
			state[1] = now;
			state[2] = 0;
		}
		if (state[0] != Long.MIN_VALUE
				&& now - state[0] < minOfferIntervalMs) {
			return false;
		}
		if (state[2] >= maxOffersPerWindow) return false;
		state[0] = now;
		state[2]++;
		seenCallIds.add(callId);
		while (seenCallIds.size() > MAX_SEEN_CALL_IDS) {
			seenCallIds.remove(seenCallIds.iterator().next());
		}
		return true;
	}

	/**
	 * A CALL_ANSWER is admissible only when we placed the call and are still
	 * ringing or connecting. Accepting an answer while idle, or while we are
	 * the callee, let a contact drive an unsolicited outbound dial.
	 */
	public static boolean answerAccepted(boolean weAreCaller,
			boolean ringingOrConnecting) {
		return weAreCaller && ringingOrConnecting;
	}
}
