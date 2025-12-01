package com.professor.zerion.android;

import org.briarproject.bramble.BrambleAndroidModule;
import org.briarproject.bramble.BrambleCoreModule;
import org.briarproject.bramble.account.AccountModule;
import org.briarproject.bramble.mailbox.ModularMailboxModule;
import org.briarproject.bramble.plugin.file.RemovableDriveModule;
import org.briarproject.bramble.system.ClockModule;
import org.briarproject.briar.BriarCoreModule;
import com.professor.zerion.android.account.SignInTestCreateAccount;
import com.professor.zerion.android.account.SignInTestSignIn;
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
		RemovableDriveModule.class,
		BriarCoreModule.class,
		BrambleAndroidModule.class,
		AccountModule.class,
		BrambleCoreModule.class,
		ModularMailboxModule.class
})
public interface BriarUiTestComponent extends AndroidComponent {

	void inject(NavDrawerActivityTest test);

	void inject(SignInTestCreateAccount test);

	void inject(SignInTestSignIn test);

}
