package com.professor.zerion.android.contact.add.nearby.ble;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class PairingCentralGate {

	enum Verdict {ACCEPTED, REFUSED, BACK_OFF}

	static final long PROOF_TIMEOUT_MS = 8_000L;
	static final int MAX_WRONG_PROOFS = 5;
	static final int MAX_PENDING = 3;
	static final long BACK_OFF_MS = 10_000L;
	static final int PROOF_BYTES = 16;
	private static final byte[] PROOF_LABEL =
			"ZPAIRPROOF".getBytes(StandardCharsets.US_ASCII);

	private final byte[] expected;
	private final Set<String> pending = new HashSet<>();
	@Nullable
	private String chosen;
	private int wrong = 0;
	private boolean backingOff = false;

	PairingCentralGate(byte[] commitment) {
		this.expected = proof(commitment);
	}

	static byte[] proof(byte[] commitment) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(commitment);
			return Arrays.copyOf(md.digest(PROOF_LABEL), PROOF_BYTES);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	synchronized boolean onConnected(String address) {
		if (chosen != null || pending.contains(address)
				|| pending.size() >= MAX_PENDING) {
			return false;
		}
		pending.add(address);
		return true;
	}

	synchronized void onTimeout(String address) {
		pending.remove(address);
	}

	synchronized void onDisconnected(String address) {
		pending.remove(address);
	}

	synchronized Verdict onProof(String address, @Nullable byte[] value,
			boolean counted) {
		if (chosen != null) {
			return chosen.equals(address) ? Verdict.ACCEPTED : Verdict.REFUSED;
		}
		if (backingOff) return Verdict.REFUSED;
		pending.remove(address);
		if (value != null && MessageDigest.isEqual(expected, value)) {
			chosen = address;
			pending.clear();
			return Verdict.ACCEPTED;
		}
		if (!counted) return Verdict.REFUSED;
		wrong++;
		if (wrong < MAX_WRONG_PROOFS) return Verdict.REFUSED;
		wrong = 0;
		backingOff = true;
		return Verdict.BACK_OFF;
	}

	synchronized void endBackOff() {
		backingOff = false;
	}

	synchronized boolean isChosen(String address) {
		return address.equals(chosen);
	}

	synchronized boolean hasChosen() {
		return chosen != null;
	}

	synchronized int pendingCount() {
		return pending.size();
	}
}
