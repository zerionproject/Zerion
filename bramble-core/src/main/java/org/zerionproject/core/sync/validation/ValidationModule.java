package org.zerionproject.core.sync.validation;

import org.zerionproject.core.PoliteExecutor;
import org.zerionproject.core.api.crypto.CryptoExecutor;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.validation.ValidationManager;

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
