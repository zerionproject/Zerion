package org.zerionproject.core.api.plugin;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

@NotNullByDefault
public interface OnionTargetFactory {

	OnionTargetListener open() throws IOException;
}
