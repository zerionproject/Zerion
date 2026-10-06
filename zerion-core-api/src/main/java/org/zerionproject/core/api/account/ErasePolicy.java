package org.zerionproject.core.api.account;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ErasePolicy {

	boolean eraseDue(int consecutiveFailures);
}
