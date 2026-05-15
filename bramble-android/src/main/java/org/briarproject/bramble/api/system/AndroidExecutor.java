package org.briarproject.bramble.api.system;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public interface AndroidExecutor {

	<V> Future<V> runOnBackgroundThread(Callable<V> c);

	void runOnBackgroundThread(Runnable r);

	<V> Future<V> runOnUiThread(Callable<V> c);

	void runOnUiThread(Runnable r);
}
