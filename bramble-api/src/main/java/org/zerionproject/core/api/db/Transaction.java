package org.zerionproject.core.api.db;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventExecutor;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.concurrent.NotThreadSafe;

import static java.util.Collections.emptyList;

@NotThreadSafe
public class Transaction {

	private final Object txn;
	private final boolean readOnly;

	private List<CommitAction> actions = null;
	private boolean committed = false;

	public Transaction(Object txn, boolean readOnly) {
		this.txn = txn;
		this.readOnly = readOnly;
	}

	public Object unbox() {
		return txn;
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void attach(Event e) {
		if (actions == null) actions = new ArrayList<>();
		actions.add(new EventAction(e));
	}

	public void attach(Runnable r) {
		if (actions == null) actions = new ArrayList<>();
		actions.add(new TaskAction(r));
	}

	public List<CommitAction> getActions() {
		return actions == null ? emptyList() : actions;
	}

	public boolean isCommitted() {
		return committed;
	}

	public void setCommitted() {
		if (committed) throw new IllegalStateException();
		committed = true;
	}
}
