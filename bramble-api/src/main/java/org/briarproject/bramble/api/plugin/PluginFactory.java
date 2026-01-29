package org.briarproject.bramble.api.plugin;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface PluginFactory<P extends Plugin> {

	
	TransportId getId();

	
	long getMaxLatency();

	
	@Nullable
	P createPlugin(PluginCallback callback);
}
