package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.contact.request.ContactRequestCrypto.Sealed;
import org.zerionproject.core.contact.request.ContactRequestRecords.Hello;
import org.zerionproject.core.contact.request.ContactRequestRecords.Record;
import org.zerionproject.core.contact.request.ContactRequestRecords.Solution;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DIFFICULTY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_LEVEL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SENDER_DIFFICULTY_CAP;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SOLUTION_X_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_ACK;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_FULL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_HELLO;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_KEY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SEALED;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SLOTS_BUSY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SOLUTION;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.X25519_BYTES;

@NotNullByDefault
final class InboxClient {

	enum Result {
		ACKED, FULL, SLOTS_BUSY, NEED_SOLUTION, RESTART, TOO_HARD, REFUSED,
		WRONG_KEY
	}

	@Immutable
	static final class Target {

		final byte[] linkId, x25519, onionPublicKey;

		Target(byte[] linkId, byte[] x25519, byte[] onionPublicKey) {
			this.linkId = linkId;
			this.x25519 = x25519;
			this.onionPublicKey = onionPublicKey;
		}
	}

	@Immutable
	static final class Puzzle {

		final Solution solution;
		final byte[] nonce;
		final int difficulty;
		final long solvedAt;

		Puzzle(Solution solution, byte[] nonce, int difficulty,
				long solvedAt) {
			this.solution = solution;
			this.nonce = nonce;
			this.difficulty = difficulty;
			this.solvedAt = solvedAt;
		}
	}

	interface Payload {

		byte[] plaintext(byte[] helloNonce) throws GeneralSecurityException;

		byte[] requestId();

		int kind();
	}

	static final class Outcome {

		final Result result;
		@Nullable
		final Hello hello;

		Outcome(Result result, @Nullable Hello hello) {
			this.result = result;
			this.hello = hello;
		}
	}

	private final ContactRequestCrypto crypto;
	private final Random random;

	static boolean isAcceptable(Hello hello) {
		return hello.level <= MAX_LEVEL
				&& hello.difficulty <= SENDER_DIFFICULTY_CAP
				&& hello.difficulty == DIFFICULTY[hello.level];
	}

	InboxClient(ContactRequestCrypto crypto, Random random) {
		this.crypto = crypto;
		this.random = random;
	}

	Outcome run(InputStream in, OutputStream out, Target target,
			@Nullable Puzzle solved, Payload payload) throws IOException {
		Puzzle puzzle = solved;
		if (puzzle != null) {
			ContactRequestRecords.write(out, TYPE_SOLUTION,
					puzzle.solution.encode());
		}
		Hello hello = Hello.decode(
				ContactRequestRecords.read(in, TYPE_HELLO).payload);
		if (!isAcceptable(hello)) return new Outcome(Result.TOO_HARD, hello);
		if (puzzle != null) {
			if (hello.epoch == puzzle.solution.epoch
					&& hello.level == puzzle.solution.level
					&& !MessageDigest.isEqual(hello.nonce, puzzle.nonce)) {
				return new Outcome(Result.RESTART, hello);
			}
		} else {
			if (hello.difficulty > 0) {
				return new Outcome(Result.NEED_SOLUTION, hello);
			}
			byte[] x = new byte[SOLUTION_X_BYTES];
			random.nextBytes(x);
			puzzle = new Puzzle(new Solution(hello.epoch, hello.level, x),
					hello.nonce, 0, 0);
			ContactRequestRecords.write(out, TYPE_SOLUTION,
					puzzle.solution.encode());
		}
		Record reply;
		try {
			reply = ContactRequestRecords.read(in, TYPE_KEY, TYPE_FULL,
					TYPE_SLOTS_BUSY);
		} catch (EOFException e) {
			return new Outcome(Result.REFUSED, hello);
		}
		if (reply.type == TYPE_FULL) return new Outcome(Result.FULL, hello);
		if (reply.type == TYPE_SLOTS_BUSY) {
			return new Outcome(Result.SLOTS_BUSY, hello);
		}
		byte[] keyPayload = reply.payload;
		byte[] linkKey = ContactRequestRecords.keyLinkPublicKey(keyPayload);
		byte[] commitment = crypto.getCrypto().hash(HYBRID_COMMITMENT_LABEL,
				linkKey);
		if (!MessageDigest.isEqual(commitment, target.linkId)
				|| !MessageDigest.isEqual(Arrays.copyOf(linkKey, X25519_BYTES),
				target.x25519)) {
			return new Outcome(Result.WRONG_KEY, hello);
		}
		Solution s = puzzle.solution;
		byte[] helloHash = crypto.helloHash(s.epoch, s.level, puzzle.nonce,
				keyPayload);
		Sealed sealed;
		byte[] plaintext = null;
		try {
			plaintext = payload.plaintext(puzzle.nonce);
			sealed = crypto.seal(new HybridAgreementPublicKey(linkKey),
					ContactRequestRecords.keyEphemeral(keyPayload), helloHash,
					target.onionPublicKey, target.linkId, plaintext);
		} catch (GeneralSecurityException e) {
			return new Outcome(Result.REFUSED, hello);
		} finally {
			if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
		}
		try {
			ContactRequestRecords.write(out, TYPE_SEALED, sealed.payload);
			Record ack;
			try {
				ack = ContactRequestRecords.read(in, TYPE_ACK);
			} catch (EOFException | FormatException e) {
				return new Outcome(Result.REFUSED, hello);
			}
			byte[] expected = crypto.ack(sealed.mk, payload.requestId(),
					payload.kind());
			return new Outcome(MessageDigest.isEqual(expected, ack.payload)
					? Result.ACKED : Result.REFUSED, hello);
		} finally {
			sealed.mk.clear();
		}
	}
}
