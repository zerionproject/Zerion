package org.briarproject.bramble.crypto;

import org.briarproject.bramble.TimeLoggingExecutor;
import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static java.util.concurrent.TimeUnit.SECONDS;

@Module
public class CryptoExecutorModule {

	public static class EagerSingletons {
		@Inject
		@CryptoExecutor
		ExecutorService cryptoExecutor;
	}

	
	private static final int MAX_EXECUTOR_THREADS =
			Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

	public CryptoExecutorModule() {
	}

	@Provides
	@Singleton
	@CryptoExecutor
	ExecutorService provideCryptoExecutorService(
			LifecycleManager lifecycleManager, ThreadFactory threadFactory) {
		BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
		RejectedExecutionHandler policy =
				new ThreadPoolExecutor.DiscardPolicy();
		ExecutorService cryptoExecutor = new TimeLoggingExecutor(
				"CryptoExecutor", 0, MAX_EXECUTOR_THREADS, 60, SECONDS, queue,
				threadFactory, policy);
		lifecycleManager.registerForShutdown(cryptoExecutor);
		return cryptoExecutor;
	}

	@Provides
	@CryptoExecutor
	Executor provideCryptoExecutor(
			@CryptoExecutor ExecutorService cryptoExecutor) {
		return cryptoExecutor;
	}
}
