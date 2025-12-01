package com.professor.zerion.android.privategroup.invitation;

import com.professor.zerion.android.sharing.InvitationController;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationItem;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
interface GroupInvitationController
		extends InvitationController<GroupInvitationItem> {
}
