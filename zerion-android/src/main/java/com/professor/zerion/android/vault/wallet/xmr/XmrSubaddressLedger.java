package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

@NotNullByDefault
public final class XmrSubaddressLedger {

	public interface Store {
		int getIssued() throws Exception;

		void setIssued(int index) throws Exception;

		default int reserveNextIndex() throws Exception {
			int next = Math.max(0, getIssued()) + 1;
			setIssued(next);
			return next;
		}

		@Nullable
		String getAddress(int index) throws Exception;

		void putAddress(int index, String address) throws Exception;

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

	public int issuedCount() throws Exception {
		return Math.max(0, store.getIssued());
	}

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
