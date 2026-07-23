package org.zerionproject.app.api.introduction;

import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.conversation.ConversationMessageVisitor;
import org.zerionproject.app.api.conversation.ConversationRequest;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class IntroductionRequest extends ConversationRequest<Author> {

	private final AuthorInfo authorInfo;

	public IntroductionRequest(MessageId messageId, GroupId groupId, long time,
			boolean local, boolean read, boolean sent, boolean seen,
			SessionId sessionId, Author author, @Nullable String text,
			boolean answered, AuthorInfo authorInfo, long autoDeleteTimer) {
		super(messageId, groupId, time, local, read, sent, seen, sessionId,
				author, text, answered, autoDeleteTimer);
		this.authorInfo = authorInfo;
	}

	@Nullable
	public String getAlias() {
		return authorInfo.getAlias();
	}

	public boolean isContact() {
		return authorInfo.getStatus().isContact();
	}

	@Override
	public <T> T accept(ConversationMessageVisitor<T> v) {
		return v.visitIntroductionRequest(this);
	}
}
