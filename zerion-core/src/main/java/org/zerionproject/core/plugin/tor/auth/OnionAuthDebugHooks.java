package org.zerionproject.core.plugin.tor.auth;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;

@NotNullByDefault
public interface OnionAuthDebugHooks {

	OnionAuthDebugHooks NONE = new OnionAuthDebugHooks() {
		@Override
		public boolean skipsNewNegotiations(Transaction txn) {
			return false;
		}

		@Override
		public boolean holdsConfirmations(Transaction txn) {
			return false;
		}
	};

	boolean skipsNewNegotiations(Transaction txn) throws DbException;

	boolean holdsConfirmations(Transaction txn) throws DbException;
}
