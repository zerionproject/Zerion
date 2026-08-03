package org.zerionproject.core.api.system;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.Provider;
import java.security.SecureRandom;

import javax.annotation.Nullable;

@NotNullByDefault
public interface SecureRandomProvider {

	@Nullable
	Provider getProvider();
}
