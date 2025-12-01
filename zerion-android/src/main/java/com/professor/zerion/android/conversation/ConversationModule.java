package com.professor.zerion.android.conversation;

import com.professor.zerion.android.activity.ActivityScope;
import com.professor.zerion.android.conversation.glide.ZerionDataFetcherFactory;

import dagger.Module;
import dagger.Provides;

@Module
public class ConversationModule {

	@ActivityScope
	@Provides
	ZerionDataFetcherFactory provideBriarDataFetcherFactory(
			ZerionDataFetcherFactory dataFetcherFactory) {
		return dataFetcherFactory;
	}

}
