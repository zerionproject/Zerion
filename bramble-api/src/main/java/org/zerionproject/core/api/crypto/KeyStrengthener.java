package org.zerionproject.core.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface KeyStrengthener {

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean isInitialised();

	SecretKey strengthenKey(SecretKey k);
}
