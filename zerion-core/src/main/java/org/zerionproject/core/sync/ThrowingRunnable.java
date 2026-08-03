package org.zerionproject.core.sync;

interface ThrowingRunnable<T extends Throwable> {

	void run() throws T;
}
