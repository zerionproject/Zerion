package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Random;

import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FIRST_RETRY_DELAYS_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.HOURLY_RETRY_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.HOURLY_RETRY_UNTIL_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.JITTER_MAX;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.JITTER_MIN;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LATE_RETRY_MS;

@NotNullByDefault
final class RetrySchedule {

	private RetrySchedule() {
	}

	static long delay(long base, int attemptsDone, long now) {
		if (attemptsDone < FIRST_RETRY_DELAYS_MS.length) {
			return FIRST_RETRY_DELAYS_MS[attemptsDone];
		}
		return now - base < HOURLY_RETRY_UNTIL_MS ? HOURLY_RETRY_MS
				: LATE_RETRY_MS;
	}

	static long next(long base, int attemptsDone, long now, Random random) {
		double jitter = JITTER_MIN
				+ (JITTER_MAX - JITTER_MIN) * random.nextDouble();
		return now + Math.round(delay(base, attemptsDone, now) * jitter);
	}
}
