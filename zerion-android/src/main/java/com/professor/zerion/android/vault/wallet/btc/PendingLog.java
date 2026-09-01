package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

@NotNullByDefault
public interface PendingLog {

	List<PendingTx> all();

	void put(PendingTx tx);

	default List<PendingTx> unresolved() {
		List<PendingTx> out = new ArrayList<>();
		for (PendingTx t : all()) {
			if (t.isUnresolved()) {
				out.add(t);
			}
		}
		return out;
	}

	PendingLog NONE = new PendingLog() {
		@Override
		public List<PendingTx> all() {
			return new ArrayList<>();
		}

		@Override
		public void put(PendingTx tx) {
		}
	};
}
