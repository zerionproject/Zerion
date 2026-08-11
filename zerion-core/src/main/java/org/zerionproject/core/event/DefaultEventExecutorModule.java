package org.zerionproject.core.event;

import org.zerionproject.core.api.event.EventExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static java.util.concurrent.Executors.newSingleThreadExecutor;

@Module
public class DefaultEventExecutorModule {

	@Provides
	@Singleton
	@EventExecutor
	Executor provideEventExecutor(ThreadFactory threadFactory) {
		return newSingleThreadExecutor(r -> {
			Thread t = threadFactory.newThread(r);
			t.setDaemon(true);
			t.setName(t.getName() + "-Event");
			return t;
		});
	}
}
