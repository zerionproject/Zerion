package org.zerionproject.app.api.introduction;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.conversation.ConversationManager.ConversationClient;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface IntroductionManager extends ConversationClient {

	ClientId CLIENT_ID = new ClientId("org.zerionproject.app.introduction");

	int MAJOR_VERSION = 1;

	boolean canIntroduce(Contact c1, Contact c2) throws DbException;

	boolean canIntroduce(Transaction txn, Contact c1, Contact c2)
			throws DbException;

	int MINOR_VERSION = 1;

	void makeIntroduction(Contact c1, Contact c2, @Nullable String text)
			throws DbException;

	void makeIntroduction(Transaction txn, Contact c1, Contact c2,
			@Nullable String text) throws DbException;

	void respondToIntroduction(ContactId contactId, SessionId sessionId,
			boolean accept) throws DbException;

	void respondToIntroduction(Transaction txn, ContactId contactId,
			SessionId sessionId, boolean accept) throws DbException;

}
