package org.zerionproject.core.api.db;

import org.zerionproject.core.api.event.EventExecutor;

public interface CommitAction {

	void accept(Visitor visitor);

	interface Visitor {

		@EventExecutor
		void visit(EventAction a);

		@EventExecutor
		void visit(TaskAction a);
	}
}
