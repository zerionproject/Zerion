package com.professor.zerion.android.conversation;

import com.professor.zerion.android.activity.ActivityScope;
import com.professor.zerion.android.conversation.glide.BriarDataFetcherFactory;

import dagger.Module;
import dagger.Provides;

@Module
public class ConversationModule {

	@ActivityScope
	@Provides
	BriarDataFetcherFactory provideBriarDataFetcherFactory(
			BriarDataFetcherFactory dataFetcherFactory) {
		return dataFetcherFactory;
	}

}
