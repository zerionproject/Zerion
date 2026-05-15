package org.briarproject.briar.api.privategroup.invitation;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import static org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager.CLIENT_ID;

@NotNullByDefault
public interface GroupInvitationFactory {

	String SIGNING_LABEL_INVITE = CLIENT_ID.getString() + "/INVITE";

	@CryptoExecutor
	byte[] signInvitation(Contact c, GroupId privateGroupId, long timestamp,
			PrivateKey privateKey);

	BdfList createInviteToken(AuthorId creatorId, AuthorId memberId,
			GroupId privateGroupId, long timestamp);

}
