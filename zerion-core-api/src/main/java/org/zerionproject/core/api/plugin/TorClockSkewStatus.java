package org.zerionproject.core.api.plugin;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface TorClockSkewStatus {

	long getCurrentClockSkewSeconds();
}
