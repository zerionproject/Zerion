package com.professor.zerion.android;

import org.zerionproject.core.BrambleAndroidModule;
import org.zerionproject.core.BrambleCoreModule;
import org.zerionproject.core.db.AndroidDatabaseModule;
import org.zerionproject.core.account.BriarAccountModule;
import org.zerionproject.core.plugin.file.RemovableDriveModule;
import org.zerionproject.core.system.ClockModule;
import org.zerionproject.app.BriarCoreModule;
import com.professor.zerion.android.attachment.AttachmentModule;
import com.professor.zerion.android.attachment.media.MediaModule;
import com.professor.zerion.android.conversation.ConversationActivityScreenshotTest;
import com.professor.zerion.android.settings.SettingsActivityScreenshotTest;

import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component(modules = {
		AppModule.class,
		AttachmentModule.class,
		ClockModule.class,
		MediaModule.class,
		RemovableDriveModule.class,
		BriarCoreModule.class,
		BrambleAndroidModule.class,
		BriarAccountModule.class,
		BrambleCoreModule.class,
		AndroidDatabaseModule.class
})
public interface BriarUiTestComponent extends AndroidComponent {

	void inject(SetupDataTest test);

	void inject(ConversationActivityScreenshotTest test);

	void inject(SettingsActivityScreenshotTest test);

	void inject(PromoVideoTest test);

}
