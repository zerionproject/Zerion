package org.zerionproject.app;

import org.zerionproject.app.autodelete.AutoDeleteModule;
import org.zerionproject.app.avatar.AvatarModule;
import org.zerionproject.app.channel.ChannelModule;
import org.zerionproject.app.conversation.ConversationModule;
import org.zerionproject.app.grouptr.GroupTrModule;
import org.zerionproject.app.identity.IdentityModule;
import org.zerionproject.app.introduction.IntroductionModule;
import org.zerionproject.app.messaging.MessagingModule;

public interface BriarCoreEagerSingletons {

	void inject(AutoDeleteModule.EagerSingletons init);

	void inject(AvatarModule.EagerSingletons init);

	void inject(ChannelModule.EagerSingletons init);

	void inject(ConversationModule.EagerSingletons init);

	void inject(GroupTrModule.EagerSingletons init);

	void inject(IdentityModule.EagerSingletons init);

	void inject(IntroductionModule.EagerSingletons init);

	void inject(MessagingModule.EagerSingletons init);

	class Helper {

		public static void injectEagerSingletons(BriarCoreEagerSingletons c) {
			c.inject(new AutoDeleteModule.EagerSingletons());
			c.inject(new AvatarModule.EagerSingletons());
			c.inject(new ChannelModule.EagerSingletons());
			c.inject(new ConversationModule.EagerSingletons());
			c.inject(new GroupTrModule.EagerSingletons());
			c.inject(new MessagingModule.EagerSingletons());
			c.inject(new IdentityModule.EagerSingletons());
			c.inject(new IntroductionModule.EagerSingletons());
		}
	}
}
