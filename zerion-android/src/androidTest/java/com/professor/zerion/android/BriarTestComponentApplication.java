package com.professor.zerion.android;

import org.briarproject.bramble.BrambleAndroidEagerSingletons;
import org.briarproject.bramble.BrambleCoreEagerSingletons;
import org.briarproject.briar.BriarCoreEagerSingletons;

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
