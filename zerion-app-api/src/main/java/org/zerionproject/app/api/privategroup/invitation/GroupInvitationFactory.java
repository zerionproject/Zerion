package org.zerionproject.app.api.privategroup.invitation;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.crypto.CryptoExecutor;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import static org.zerionproject.app.api.privategroup.invitation.GroupInvitationManager.CLIENT_ID;

@NotNullByDefault
public interface GroupInvitationFactory {

	String SIGNING_LABEL_INVITE = CLIENT_ID.getString() + "/INVITE";

	@CryptoExecutor
	byte[] signInvitation(Contact c, GroupId privateGroupId, long timestamp,
			PrivateKey privateKey);

	BdfList createInviteToken(AuthorId creatorId, AuthorId memberId,
			GroupId privateGroupId, long timestamp);

}
