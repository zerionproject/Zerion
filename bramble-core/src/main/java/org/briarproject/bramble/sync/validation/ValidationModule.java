package org.briarproject.bramble.sync.validation;

import org.briarproject.bramble.PoliteExecutor;
import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.validation.ValidationManager;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class ValidationModule {

	public static class EagerSingletons {
		@Inject
		ValidationManager validationManager;
	}

	private static final int MAX_CONCURRENT_VALIDATION_TASKS =
			Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

	@Provides
	@Singleton
	ValidationManager provideValidationManager(
			LifecycleManager lifecycleManager, EventBus eventBus,
			ValidationManagerImpl validationManager) {
		lifecycleManager.registerService(validationManager);
		eventBus.addListener(validationManager);
		return validationManager;
	}

	@Provides
	@Singleton
	@ValidationExecutor
	Executor provideValidationExecutor(
			@CryptoExecutor Executor cryptoExecutor) {
		return new PoliteExecutor("ValidationExecutor", cryptoExecutor,
				MAX_CONCURRENT_VALIDATION_TASKS);
	}
}
