package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

/**
 * Crash-safe issuance of Monero receive subaddress indices for one wallet.
 * Index 0 is the primary address and is never issued as a fresh receive
 * address. Fresh indices start at 1 and increase monotonically. The single
 * fund/privacy invariant: an index that has been reserved (about to be shown as
 * a new payment address) is PERSISTED before it is returned, so a crash or
 * restart can never treat a shown index as never-issued and hand it out again.
 *
 * <p>Persistence is behind {@link Store} so the crash-safe logic is testable
 * without a JSON/Android runtime; the production store is JSON-backed in the
 * encrypted vault settings. A persistence failure in {@link #reserveNext}
 * propagates, so the caller must not display or reuse an address on failure.
 */
@NotNullByDefault
public final class XmrSubaddressLedger {

	/** Structured persistence for one wallet's receive state. */
	public interface Store {
		int getIssued() throws Exception;

		/** Persist the new highest issued index. The crash-safe commit point. */
		void setIssued(int index) throws Exception;

		/**
		 * Atomically read the highest issued index, advance it by one and persist
		 * the new value in a single critical section, returning the reserved
		 * index. Implementations that persist under a monitor must hold it across
		 * the read and the write so two concurrent reservations can never hand
		 * out the same index. The default is only safe under external single
		 * threading; the persistent store overrides it.
		 */
		default int reserveNextIndex() throws Exception {
			int next = Math.max(0, getIssued()) + 1;
			setIssued(next);
			return next;
		}

		@Nullable
		String getAddress(int index) throws Exception;

		void putAddress(int index, String address) throws Exception;

		/** Persist many addresses in one write (pool caching at open). */
		void putAddresses(java.util.Map<Integer, String> addresses)
				throws Exception;

		@Nullable
		String getLabel(int index) throws Exception;

		void putLabel(int index, @Nullable String label) throws Exception;

		long getDate(int index) throws Exception;

		void putDate(int index, long millis) throws Exception;
	}

	private final Store store;

	public XmrSubaddressLedger(Store store) {
		this.store = store;
	}

	/** Highest issued fresh index; 0 means only the primary exists. */
	public int issuedCount() throws Exception {
		return Math.max(0, store.getIssued());
	}

	/**
	 * Reserve the next fresh index. Persists the reservation BEFORE returning, so
	 * a shown index is always recorded as issued. Throws on persistence failure
	 * without advancing state the caller can observe as a usable address.
	 */
	public int reserveNext(long nowMillis) throws Exception {
		int next = store.reserveNextIndex();
		try {
			store.putDate(next, nowMillis);
		} catch (Exception ignored) {
		}
		return next;
	}

	public List<Integer> issuedIndices() throws Exception {
		int issued = issuedCount();
		List<Integer> out = new ArrayList<>();
		for (int i = 1; i <= issued; i++) out.add(i);
		return out;
	}

	public boolean isIssued(int index) throws Exception {
		return index >= 1 && index <= issuedCount();
	}

	@Nullable
	public String cachedAddress(int index) throws Exception {
		return store.getAddress(index);
	}

	public void cacheAddress(int index, String address) throws Exception {
		store.putAddress(index, address);
	}

	public void cacheAddresses(java.util.Map<Integer, String> addresses)
			throws Exception {
		store.putAddresses(addresses);
	}

	@Nullable
	public String label(int index) throws Exception {
		return store.getLabel(index);
	}

	public void setLabel(int index, @Nullable String label) throws Exception {
		store.putLabel(index, label);
	}

	public long issuedDate(int index) throws Exception {
		return store.getDate(index);
	}
}
