package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.vault.wallet.btc.payjoin.PayjoinSession;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Drives the Payjoin send flow as a strict state machine. Each forward state is
 * entered only when its real operation begins, so the UI never shows a state
 * that is not actually happening. Authentication is possible only in the review
 * state and only against the final transaction fingerprint. Any failure,
 * cancellation, or interruption clears the pending authorization and never
 * signs or broadcasts. A failure is never turned into a normal send here; it
 * yields an explicit fallback choice.
 */
@NotNullByDefault
public final class PayjoinFlowController {

	public enum State {
		IDLE,
		PREPARING,
		CONNECTING_TOR,
		NEGOTIATING,
		VALIDATING,
		READY_FOR_REVIEW,
		FAILED,
		CANCELLED
	}

	private State state = State.IDLE;
	private final PayjoinGate gate = new PayjoinGate();
	@Nullable
	private PayjoinSender.Review review;

	public State state() {
		return state;
	}

	@Nullable
	public PayjoinSender.Review review() {
		return review;
	}

	public boolean canAuthorize() {
		return state == State.READY_FOR_REVIEW;
	}

	public void beginPreparing() {
		require(State.IDLE);
		state = State.PREPARING;
	}

	public void beginConnectingTor() {
		require(State.PREPARING);
		state = State.CONNECTING_TOR;
	}

	public void beginNegotiating() {
		require(State.CONNECTING_TOR);
		state = State.NEGOTIATING;
	}

	public void beginValidating() {
		if (state != State.NEGOTIATING && state != State.READY_FOR_REVIEW) {
			throw new IllegalStateException("cannot validate from " + state);
		}
		gate.clear();
		review = null;
		state = State.VALIDATING;
	}

	public void ready(PayjoinSender.Review r) {
		require(State.VALIDATING);
		if (!r.ok || r.finalTx == null) {
			throw new IllegalStateException("review is not signable");
		}
		this.review = r;
		gate.prepare(r.finalTx);
		state = State.READY_FOR_REVIEW;
	}

	public PayjoinOutcomeRouter.Action fail(PayjoinSession.Status status) {
		gate.clear();
		review = null;
		state = State.FAILED;
		return PayjoinOutcomeRouter.route(status, null);
	}

	public PayjoinOutcomeRouter.Action failRejected(PayjoinSender.Review r) {
		gate.clear();
		review = null;
		state = State.FAILED;
		return PayjoinOutcomeRouter.route(PayjoinSession.Status.VALIDATED, r);
	}

	public void cancel() {
		gate.clear();
		review = null;
		state = State.CANCELLED;
	}

	public void onInterrupted() {
		if (state == State.IDLE || state == State.FAILED
				|| state == State.CANCELLED) {
			return;
		}
		cancel();
	}

	public PayjoinFinalTx authorize(String reviewedFingerprint,
			boolean authenticated) throws PayjoinGate.AuthorizationException {
		if (state != State.READY_FOR_REVIEW) {
			throw new PayjoinGate.AuthorizationException(
					"not ready for authorization");
		}
		return gate.authorize(reviewedFingerprint, authenticated);
	}

	public void reset() {
		gate.clear();
		review = null;
		state = State.IDLE;
	}

	private void require(State expected) {
		if (state != expected) {
			throw new IllegalStateException(
					"expected " + expected + " but was " + state);
		}
	}
}
