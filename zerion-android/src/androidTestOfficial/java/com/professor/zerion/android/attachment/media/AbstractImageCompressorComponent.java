package com.professor.zerion.android.attachment.media;

import android.content.Context;

import javax.inject.Singleton;

import dagger.BindsInstance;
import dagger.Component;

@Singleton
@Component(modules = {
		MediaModule.class
})
interface AbstractImageCompressorComponent {

	void inject(AbstractImageCompressorTest test);

	@Component.Builder
	interface Builder {
		@BindsInstance
		Builder context(Context context);

		AbstractImageCompressorComponent build();
	}

}
