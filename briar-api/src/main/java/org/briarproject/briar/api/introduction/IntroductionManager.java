package org.briarproject.briar.api.introduction;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.briar.api.client.SessionId;
import org.briarproject.briar.api.conversation.ConversationManager.ConversationClient;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface IntroductionManager extends ConversationClient {

	ClientId CLIENT_ID = new ClientId("org.briarproject.briar.introduction");

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
