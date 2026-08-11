package org.zerionproject.app.api.privategroup.invitation;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.app.api.client.ProtocolStateException;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.conversation.ConversationManager.ConversationClient;
import org.zerionproject.app.api.privategroup.PrivateGroup;
import org.zerionproject.app.api.sharing.SharingManager.SharingStatus;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.Nullable;

@NotNullByDefault
public interface GroupInvitationManager extends ConversationClient {

	ClientId CLIENT_ID =
			new ClientId("org.zerionproject.app.privategroup.invitation");

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
