package org.zerionproject.core.db;

interface BenchmarkTask<T> {

	void run(T context) throws Exception;
}
