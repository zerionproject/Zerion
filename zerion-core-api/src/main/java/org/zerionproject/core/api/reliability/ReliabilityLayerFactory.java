package org.zerionproject.core.api.reliability;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ReliabilityLayerFactory {

	ReliabilityLayer createReliabilityLayer(WriteHandler writeHandler);
}
