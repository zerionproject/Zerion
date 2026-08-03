package org.zerionproject.core.api.db;

import org.zerionproject.core.api.event.EventExecutor;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public interface TransactionManager {

	Transaction startTransaction(boolean readOnly) throws DbException;

	void commitTransaction(Transaction txn) throws DbException;

	void endTransaction(Transaction txn);

	<E extends Exception> void transaction(boolean readOnly,
			DbRunnable<E> task) throws DbException, E;

	<R, E extends Exception> R transactionWithResult(boolean readOnly,
			DbCallable<R, E> task) throws DbException, E;

	@Nullable
	<R, E extends Exception> R transactionWithNullableResult(boolean readOnly,
			NullableDbCallable<R, E> task) throws DbException, E;

}
