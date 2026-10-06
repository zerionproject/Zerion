package org.zerionproject.core.account;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class PasswordUnavailableException extends IllegalArgumentException {

	public PasswordUnavailableException() {
		super("Password unavailable");
	}
}
