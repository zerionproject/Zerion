package com.professor.zerion.android.settings;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.contact.request.ContactRequestCrypto;

import java.util.Random;
import java.util.function.LongSupplier;

import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DIFFICULTY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_LEVEL;

@NotNullByDefault
final class InvitePuzzleBenchmark {

	static final long NOT_SOLVED = -1;

	private InvitePuzzleBenchmark() {
	}

	static int[] paidDifficulties() {
		int[] bits = new int[MAX_LEVEL];
		System.arraycopy(DIFFICULTY, 1, bits, 0, MAX_LEVEL);
		return bits;
	}

	static long[] measure(ContactRequestCrypto crypto, int[] bits,
			Random random, LongSupplier nanos) {
		long[] millis = new long[bits.length];
		for (int i = 0; i < bits.length; i++) {
			byte[] nonce = new byte[32];
			byte[] linkId = new byte[32];
			random.nextBytes(nonce);
			random.nextBytes(linkId);
			long start = nanos.getAsLong();
			byte[] x = crypto.solve(nonce, linkId, bits[i], random,
					() -> false, Long.MAX_VALUE);
			long took = (nanos.getAsLong() - start) / 1_000_000L;
			millis[i] = x == null ? NOT_SOLVED : took;
		}
		return millis;
	}
}
