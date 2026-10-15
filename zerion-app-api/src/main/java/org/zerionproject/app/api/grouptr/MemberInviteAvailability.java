package org.zerionproject.app.api.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public enum MemberInviteAvailability {
	ALLOWED,
	NOT_ENABLED,
	CREATOR_NOT_A_CONTACT,
	CREATOR_ON_OLDER_VERSION,
	UNAVAILABLE
}
