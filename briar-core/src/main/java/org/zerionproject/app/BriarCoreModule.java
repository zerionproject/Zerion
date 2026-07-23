package org.zerionproject.app;

import org.zerionproject.app.attachment.AttachmentModule;
import org.zerionproject.app.autodelete.AutoDeleteModule;
import org.zerionproject.app.avatar.AvatarModule;
import org.zerionproject.app.channel.ChannelModule;
import org.zerionproject.app.client.BriarClientModule;
import org.zerionproject.app.conversation.ConversationModule;
import org.zerionproject.app.conversation.voice.VoiceCallModule;
import org.zerionproject.app.grouptr.GroupTrModule;
import org.zerionproject.app.identity.IdentityModule;
import org.zerionproject.app.introduction.IntroductionModule;
import org.zerionproject.app.messaging.MessagingModule;
import org.zerionproject.app.test.TestModule;

import dagger.Module;

@Module(includes = {
		AttachmentModule.class,
		AutoDeleteModule.class,
		AvatarModule.class,
		BriarClientModule.class,
		ChannelModule.class,
		ConversationModule.class,
		GroupTrModule.class,
		IdentityModule.class,
		IntroductionModule.class,
		MessagingModule.class,
		TestModule.class,
		VoiceCallModule.class
})
public class BriarCoreModule {
}
