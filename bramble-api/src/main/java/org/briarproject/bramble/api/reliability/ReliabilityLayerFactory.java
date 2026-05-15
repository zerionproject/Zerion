package org.briarproject.bramble.api.reliability;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ReliabilityLayerFactory {

	ReliabilityLayer createReliabilityLayer(WriteHandler writeHandler);
}
