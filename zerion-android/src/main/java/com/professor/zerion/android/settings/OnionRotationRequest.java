package com.professor.zerion.android.settings;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.plugin.tor.B4OnionRotation;

import javax.annotation.Nullable;

@NotNullByDefault
final class OnionRotationRequest {

	enum Outcome {
		STARTED,
		ALREADY_RUNNING,
		FAILED
	}

	static final class Result {

		final Outcome outcome;
		@Nullable
		final String onion;

		Result(Outcome outcome, @Nullable String onion) {
			this.outcome = outcome;
			this.onion = onion;
		}
	}

	private OnionRotationRequest() {
	}

	static Result run(B4OnionRotation rotation) {
		try {
			if (rotation.forceRotate()) {
				String next = rotation.getAliceNextOnion();
				return new Result(next == null ? Outcome.FAILED
						: Outcome.STARTED, next);
			}
			if (rotation.getPhase() == B4OnionRotation.RotationPhase.ANNOUNCING) {
				return new Result(Outcome.ALREADY_RUNNING, null);
			}
		} catch (DbException ignored) {
		}
		return new Result(Outcome.FAILED, null);
	}
}
