package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class ContactRootKeys {

	private final long epoch;
	private final SecretKey current;
	@Nullable
	private final SecretKey pending;
	@Nullable
	private final SecretKey pendingConfirmKey;
	private final boolean pendingConfirmed;

	public ContactRootKeys(long epoch, SecretKey current,
			@Nullable SecretKey pending, boolean pendingConfirmed) {
		this(epoch, current, pending, null, pendingConfirmed);
	}

	public ContactRootKeys(long epoch, SecretKey current,
			@Nullable SecretKey pending, @Nullable SecretKey pendingConfirmKey,
			boolean pendingConfirmed) {
		if (epoch < 0) throw new IllegalArgumentException();
		if (pending == null && pendingConfirmKey != null) {
			throw new IllegalArgumentException();
		}
		this.epoch = epoch;
		this.current = current;
		this.pending = pending;
		this.pendingConfirmKey = pendingConfirmKey;
		this.pendingConfirmed = pending != null && pendingConfirmed;
	}

	public static ContactRootKeys atPairing(SecretKey pairingRoot) {
		return new ContactRootKeys(0, pairingRoot, null, false);
	}

	public long getEpoch() {
		return epoch;
	}

	public SecretKey getCurrent() {
		return current;
	}

	@Nullable
	public SecretKey getPending() {
		return pending;
	}

	@Nullable
	public SecretKey getPendingConfirmKey() {
		return pendingConfirmKey;
	}

	public boolean isPendingSeparated() {
		return pendingConfirmKey != null;
	}

	public long getPendingEpoch() {
		return epoch + 1;
	}

	public boolean isPendingConfirmed() {
		return pendingConfirmed;
	}

	public long getSendEpoch() {
		return pendingConfirmed ? epoch + 1 : epoch;
	}

	@Nullable
	public SecretKey getKey(long e) {
		if (e == epoch) return current;
		if (pending != null && e == epoch + 1) return pending;
		return null;
	}
}
