package org.zerionproject.core.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface PasswordStrengthEstimator {

	float NONE = 0;
	float WEAK = 0.25f;
	float QUITE_WEAK = 0.5f;
	float QUITE_STRONG = 0.75f;
	float STRONG = 1;

	float estimateStrength(char[] password);
}
