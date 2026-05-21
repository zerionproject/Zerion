package org.briarproject.briar;

import org.briarproject.briar.autodelete.AutoDeleteModule;
import org.briarproject.briar.avatar.AvatarModule;
import org.briarproject.briar.channel.ChannelModule;
import org.briarproject.briar.conversation.ConversationModule;
import org.briarproject.briar.grouptr.GroupTrModule;
import org.briarproject.briar.identity.IdentityModule;
import org.briarproject.briar.introduction.IntroductionModule;
import org.briarproject.briar.messaging.MessagingModule;
import org.briarproject.briar.privategroup.PrivateGroupModule;
import org.briarproject.briar.privategroup.invitation.GroupInvitationModule;
import org.briarproject.briar.sharing.SharingModule;

public interface BriarCoreEagerSingletons {

	void inject(AutoDeleteModule.EagerSingletons init);

	void inject(AvatarModule.EagerSingletons init);

	void inject(ChannelModule.EagerSingletons init);

	void inject(ConversationModule.EagerSingletons init);

	void inject(GroupInvitationModule.EagerSingletons init);

	void inject(GroupTrModule.EagerSingletons init);

	void inject(IdentityModule.EagerSingletons init);

	void inject(IntroductionModule.EagerSingletons init);

	void inject(MessagingModule.EagerSingletons init);

	void inject(PrivateGroupModule.EagerSingletons init);

	void inject(SharingModule.EagerSingletons init);

	class Helper {

		public static void injectEagerSingletons(BriarCoreEagerSingletons c) {
			c.inject(new AutoDeleteModule.EagerSingletons());
			c.inject(new AvatarModule.EagerSingletons());
			c.inject(new ChannelModule.EagerSingletons());
			c.inject(new ConversationModule.EagerSingletons());
			c.inject(new GroupInvitationModule.EagerSingletons());
			c.inject(new GroupTrModule.EagerSingletons());
			c.inject(new MessagingModule.EagerSingletons());
			c.inject(new PrivateGroupModule.EagerSingletons());
			c.inject(new SharingModule.EagerSingletons());
			c.inject(new IdentityModule.EagerSingletons());
			c.inject(new IntroductionModule.EagerSingletons());
		}
	}
}
