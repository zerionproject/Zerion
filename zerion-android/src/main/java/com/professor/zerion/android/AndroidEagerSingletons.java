package com.professor.zerion.android;

interface AndroidEagerSingletons {

	void inject(AppModule.EagerSingletons init);

	void inject(org.zerionproject.transport.ZerionTransportModule.EagerSingletons init);

	class Helper {

		static void injectEagerSingletons(AndroidEagerSingletons c) {
			c.inject(new AppModule.EagerSingletons());
			c.inject(new org.zerionproject.transport.ZerionTransportModule.EagerSingletons());
		}
	}
}
