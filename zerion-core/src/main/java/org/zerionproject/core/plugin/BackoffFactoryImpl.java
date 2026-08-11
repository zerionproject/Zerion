package org.zerionproject.core.plugin;

import org.zerionproject.core.api.plugin.Backoff;
import org.zerionproject.core.api.plugin.BackoffFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
class BackoffFactoryImpl implements BackoffFactory {

	@Override
	public Backoff createBackoff(int minInterval, int maxInterval,
			double base) {
		return new BackoffImpl(minInterval, maxInterval, base);
	}
}
