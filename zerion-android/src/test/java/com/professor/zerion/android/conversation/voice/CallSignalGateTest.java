package com.professor.zerion.android.conversation.voice;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * PROTO-08: incoming CALL_OFFER admission must reject stale offers, rate-limit
 * per contact, and de-duplicate call ids; CALL_ANSWER must be admissible only
 * when we placed the call and are still ringing or connecting.
 */
public class CallSignalGateTest {

	private final AtomicLong now = new AtomicLong(1_000_000_000L);

	private CallSignalGate gate() {
		return new CallSignalGate(now::get, 60_000L, 3_000L, 5, 60_000L);
	}

	@Test
	public void freshOfferIsAdmitted() {
		assertTrue(gate().admitOffer("1", "call-a", now.get()));
	}

	@Test
	public void staleOfferIsRejected() {
		assertFalse(gate().admitOffer("1", "call-a", now.get() - 120_000L));
	}

	@Test
	public void futureOfferIsRejected() {
		assertFalse(gate().admitOffer("1", "call-a", now.get() + 120_000L));
	}

	@Test
	public void duplicateCallIdIsRejected() {
		CallSignalGate g = gate();
		assertTrue(g.admitOffer("1", "call-a", now.get()));
		now.addAndGet(4_000L);
		assertFalse("the same call id must not ring twice",
				g.admitOffer("1", "call-a", now.get()));
	}

	@Test
	public void offersTooSoonFromOneContactAreRejected() {
		CallSignalGate g = gate();
		assertTrue(g.admitOffer("1", "call-a", now.get()));
		now.addAndGet(1_000L);
		assertFalse("a second offer within the interval must be dropped",
				g.admitOffer("1", "call-b", now.get()));
	}

	@Test
	public void offerStormIsBoundedPerWindow() {
		CallSignalGate g = gate();
		int admitted = 0;
		for (int i = 0; i < 15; i++) {
			if (g.admitOffer("1", "call-" + i, now.get())) admitted++;
			now.addAndGet(3_000L);
		}
		assertTrue("offers per contact per window must be capped",
				admitted <= 5);
	}

	@Test
	public void differentContactsAreIndependent() {
		CallSignalGate g = gate();
		assertTrue(g.admitOffer("1", "call-a", now.get()));
		assertTrue(g.admitOffer("2", "call-b", now.get()));
	}

	@Test
	public void answerAcceptedOnlyWhenWePlacedTheCall() {
		assertTrue(CallSignalGate.answerAccepted(true, true));
		assertFalse("an answer while idle must be ignored",
				CallSignalGate.answerAccepted(true, false));
		assertFalse("the callee must not accept an answer",
				CallSignalGate.answerAccepted(false, true));
	}
}
