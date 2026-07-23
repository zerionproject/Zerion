package org.zerionproject.app.introduction;

import org.zerionproject.core.BrambleCoreModule;
import org.zerionproject.core.db.DatabaseModule;
import org.zerionproject.core.test.BrambleCoreIntegrationTestModule;
import org.zerionproject.core.test.TestDnsModule;
import org.zerionproject.core.test.TestPluginConfigModule;
import org.zerionproject.core.test.TestSocksModule;
import org.zerionproject.app.attachment.AttachmentModule;
import org.zerionproject.app.autodelete.AutoDeleteModule;
import org.zerionproject.app.avatar.AvatarModule;
import org.zerionproject.app.client.BriarClientModule;
import org.zerionproject.app.conversation.ConversationModule;
import org.zerionproject.app.identity.IdentityModule;
import org.zerionproject.app.messaging.MessagingModule;
import org.zerionproject.app.test.BriarIntegrationTestComponent;

import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component(modules = {
		BrambleCoreIntegrationTestModule.class,
		BrambleCoreModule.class,
		DatabaseModule.class,
		AttachmentModule.class,
		AutoDeleteModule.class,
		AvatarModule.class,
		BriarClientModule.class,
		ConversationModule.class,
		IdentityModule.class,
		IntroductionModule.class,
		MessagingModule.class,
		TestDnsModule.class,
		TestSocksModule.class,
		TestPluginConfigModule.class,
})
interface IntroductionIntegrationTestComponent
		extends BriarIntegrationTestComponent {

	void inject(IntroductionIntegrationTest init);

	void inject(MessageEncoderParserIntegrationTest init);

	void inject(SessionEncoderParserIntegrationTest init);

	void inject(IntroductionCryptoIntegrationTest init);

	void inject(AutoDeleteIntegrationTest init);

	MessageEncoder getMessageEncoder();

	MessageParser getMessageParser();

	SessionParser getSessionParser();

	IntroductionCrypto getIntroductionCrypto();

}
