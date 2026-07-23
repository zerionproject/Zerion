package com.professor.zerion.android;

import org.zerionproject.core.BrambleAndroidModule;
import org.zerionproject.core.BrambleCoreModule;
import org.zerionproject.core.db.AndroidDatabaseModule;
import org.zerionproject.core.system.ClockModule;
import org.zerionproject.app.BriarCoreModule;
import com.professor.zerion.android.account.SignInTestCreateAccount;
import com.professor.zerion.android.account.SignInTestSignIn;
import com.professor.zerion.android.backup.BackupRoundTripTest;
import com.professor.zerion.android.attachment.AttachmentModule;
import com.professor.zerion.android.attachment.media.MediaModule;
import com.professor.zerion.android.navdrawer.NavDrawerActivityTest;

import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component(modules = {
		AppModule.class,
		AttachmentModule.class,
		ClockModule.class,
		MediaModule.class,
		BriarCoreModule.class,
		BrambleAndroidModule.class,
		BrambleCoreModule.class,
		AndroidDatabaseModule.class
})
public interface BriarUiTestComponent extends AndroidComponent {

	void inject(NavDrawerActivityTest test);

	void inject(SignInTestCreateAccount test);

	void inject(SignInTestSignIn test);

	void inject(BackupRoundTripTest test);

}
