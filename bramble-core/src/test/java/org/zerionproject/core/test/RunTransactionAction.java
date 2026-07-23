package org.zerionproject.core.test;

import org.zerionproject.core.api.db.CommitAction;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.TaskAction;
import org.zerionproject.core.api.db.Transaction;
import org.hamcrest.Description;
import org.jmock.api.Action;
import org.jmock.api.Invocation;

class RunTransactionAction implements Action {

	private final Transaction txn;

	RunTransactionAction(Transaction txn) {
		this.txn = txn;
	}

	@Override
	public Object invoke(Invocation invocation) throws Throwable {
		DbRunnable task = (DbRunnable) invocation.getParameter(1);
		task.run(txn);
		for (CommitAction action : txn.getActions()) {
			if (action instanceof TaskAction)
				((TaskAction) action).getTask().run();
		}
		return null;
	}

	@Override
	public void describeTo(Description description) {
		description.appendText("runs a task inside a database transaction");
	}
}
