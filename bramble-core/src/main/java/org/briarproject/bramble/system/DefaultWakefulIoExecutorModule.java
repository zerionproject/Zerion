package org.briarproject.bramble.system;

import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.system.WakefulIoExecutor;

import java.util.concurrent.Executor;

import dagger.Module;
import dagger.Provides;

@Module
public class DefaultWakefulIoExecutorModule {

	@Provides
	@WakefulIoExecutor
	Executor provideWakefulIoExecutor(@IoExecutor Executor ioExecutor) {
		return ioExecutor;
	}
}
