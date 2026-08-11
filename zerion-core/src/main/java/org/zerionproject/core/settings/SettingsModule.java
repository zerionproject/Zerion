package org.zerionproject.core.settings;

import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.settings.SettingsManager;

import dagger.Module;
import dagger.Provides;

@Module
public class SettingsModule {

	@Provides
	SettingsManager provideSettingsManager(DatabaseComponent db) {
		return new SettingsManagerImpl(db);
	}

}
