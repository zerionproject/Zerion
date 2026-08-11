package com.professor.zerion.android;

import org.zerionproject.core.BrambleAndroidEagerSingletons;
import org.zerionproject.core.BrambleCoreEagerSingletons;
import org.zerionproject.app.BriarCoreEagerSingletons;

public class BriarTestComponentApplication extends ZerionApplicationImpl {

	@Override
	protected AndroidComponent createApplicationComponent() {
		AndroidComponent component = DaggerBriarUiTestComponent.builder()
				.appModule(new AppModule(this)).build();

		BrambleCoreEagerSingletons.Helper.injectEagerSingletons(component);
		BrambleAndroidEagerSingletons.Helper.injectEagerSingletons(component);
		BriarCoreEagerSingletons.Helper.injectEagerSingletons(component);
		AndroidEagerSingletons.Helper.injectEagerSingletons(component);
		return component;
	}

	@Override
	public boolean isInstrumentationTest() {
		return true;
	}

}
