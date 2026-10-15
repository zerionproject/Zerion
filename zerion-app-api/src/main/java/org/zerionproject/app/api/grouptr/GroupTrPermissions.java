package org.zerionproject.app.api.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface GroupTrPermissions {

	long MEMBERS_MAY_INVITE = 1L;

	long GRANT_MAY_ADD_MEMBERS = 1L;
}
