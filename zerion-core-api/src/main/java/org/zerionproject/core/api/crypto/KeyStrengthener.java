package org.zerionproject.core.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface KeyStrengthener {

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean isInitialised();

	SecretKey strengthenKey(SecretKey k);

	/**
	 * Discards whatever the platform key store holds under the
	 * strengthener's alias so that the next {@link #strengthenKey} generates
	 * a fresh key even if the store cannot read or enumerate the alias. Only
	 * valid while no stored database key depends on the alias, that is,
	 * before the first account is created on this installation; afterwards
	 * a lookup failure must stay a failure, since a new key would make every
	 * stored key undecryptable.
	 */
	void discardKeyBeforeFirstAccount();
}
