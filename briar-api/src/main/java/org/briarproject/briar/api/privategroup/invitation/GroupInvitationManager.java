package org.briarproject.briar.api.privategroup.invitation;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.client.ProtocolStateException;
import org.briarproject.briar.api.client.SessionId;
import org.briarproject.briar.api.conversation.ConversationManager.ConversationClient;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.sharing.SharingManager.SharingStatus;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.Nullable;

@NotNullByDefault
public interface GroupInvitationManager extends ConversationClient {

	ClientId CLIENT_ID =
			new ClientId("org.briarproject.briar.privategroup.invitation");

	int MAJOR_VERSION = 0;

	int MINOR_VERSION = 1;

	void sendInvitation(GroupId g, ContactId c, @Nullable String text,
			long timestamp, byte[] signature, long autoDeleteTimer)
			throws DbException;

	void sendInvitation(Transaction txn, GroupId g, ContactId c,
			@Nullable String text, long timestamp, byte[] signature,
			long autoDeleteTimer) throws DbException;

	void respondToInvitation(ContactId c, PrivateGroup g, boolean accept)
			throws DbException;

	void respondToInvitation(Transaction txn, ContactId c, PrivateGroup g,
			boolean accept) throws DbException;

	void respondToInvitation(ContactId c, SessionId s, boolean accept)
			throws DbException;

	void respondToInvitation(Transaction txn, ContactId c, SessionId s,
			boolean accept) throws DbException;

	void revealRelationship(ContactId c, GroupId g) throws DbException;

	void revealRelationship(Transaction txn, ContactId c, GroupId g)
			throws DbException;

	Collection<GroupInvitationItem> getInvitations() throws DbException;

	Collection<GroupInvitationItem> getInvitations(Transaction txn)
			throws DbException;

	SharingStatus getSharingStatus(Contact c, GroupId g) throws DbException;

	SharingStatus getSharingStatus(Transaction txn, Contact c, GroupId g)
			throws DbException;
}
