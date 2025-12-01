package com.professor.zerion.android.sharing;

import com.professor.zerion.android.activity.ActivityScope;
import com.professor.zerion.android.activity.BaseActivity;

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
