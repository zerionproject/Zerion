package com.professor.zerion.android.sharing;


import dagger.Module;
import dagger.Provides;

@Module
public class SharingModule {

	@Module
	@Deprecated
	public static class SharingLegacyModule {
	}

	@Provides
	SharingController provideSharingController(
			SharingControllerImpl sharingController) {
		return sharingController;
	}

}
