package com.professor.zerion.android.activity;

import android.app.Activity;
import android.content.Context;

import com.professor.zerion.android.controller.ZerionController;
import com.professor.zerion.android.controller.ZerionControllerImpl;
import com.professor.zerion.android.controller.DbController;
import com.professor.zerion.android.controller.DbControllerImpl;

import dagger.Module;
import dagger.Provides;

import static com.professor.zerion.android.ZerionService.ZerionServiceConnection;

@Module
public class ActivityModule {

	private final BaseActivity activity;

	public ActivityModule(BaseActivity activity) {
		this.activity = activity;
	}

	@ActivityScope
	@Provides
	BaseActivity provideBaseActivity() {
		return activity;
	}

	@ActivityScope
	@Provides
	Activity provideActivity() {
		return activity;
	}

	@ActivityScope
	@Provides
	protected ZerionController provideBriarController(
			ZerionControllerImpl briarController) {
		activity.addLifecycleController(briarController);
		return briarController;
	}

	@ActivityScope
	@Provides
	DbController provideDBController(DbControllerImpl dbController) {
		return dbController;
	}

	@ActivityScope
	@Provides
	ZerionServiceConnection provideBriarServiceConnection() {
		return new ZerionServiceConnection();
	}
}
