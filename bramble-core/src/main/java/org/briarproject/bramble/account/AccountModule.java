package org.briarproject.bramble.account;

import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;

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
