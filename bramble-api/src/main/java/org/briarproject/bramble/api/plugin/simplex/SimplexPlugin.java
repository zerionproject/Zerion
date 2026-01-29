package org.briarproject.bramble.api.plugin.simplex;

import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.TransportConnectionReader;
import org.briarproject.bramble.api.plugin.TransportConnectionWriter;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.system.Wakeful;
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
