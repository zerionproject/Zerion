package org.zerionproject.app.api.grouptr;

import org.zerionproject.core.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class GroupTrAuthException extends DbException {

	public enum Reason {
		NOT_CREATOR,
		CANNOT_REMOVE_CREATOR,
		CANNOT_LEAVE_AS_CREATOR,
		GROUP_DISSOLVED,
		GROUP_NOT_FOUND,
		CONTACT_NOT_FOUND,
		EPOCH_OVERFLOW,
		NOT_A_MEMBER
	}

	private final Reason reason;

	public GroupTrAuthException(Reason reason) {
		super();
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}
}
