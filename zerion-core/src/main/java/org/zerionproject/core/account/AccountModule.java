package org.zerionproject.core.account;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class AccountModule {

	public static class EagerSingletons {
		@Inject
		AccountManager accountManager;
	}

	@Provides
	@Singleton
	AccountManager provideAccountManager(LifecycleManager lifecycleManager,
			AccountManagerImpl accountManager) {
		lifecycleManager.registerService(accountManager);
		return accountManager;
	}
}
