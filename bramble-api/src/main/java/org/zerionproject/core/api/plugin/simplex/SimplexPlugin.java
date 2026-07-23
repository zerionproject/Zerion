package org.zerionproject.core.api.plugin.simplex;

import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.system.Wakeful;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface SimplexPlugin extends Plugin {

	boolean isLossyAndCheap();

	@Wakeful
	@Nullable
	TransportConnectionReader createReader(TransportProperties p);

	@Wakeful
	@Nullable
	TransportConnectionWriter createWriter(TransportProperties p);
}
