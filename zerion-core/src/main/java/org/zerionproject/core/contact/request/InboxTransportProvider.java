package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.plugin.tor.InboxOnionAdapter;

import javax.annotation.Nullable;

@NotNullByDefault
public interface InboxTransportProvider {

	@Nullable
	InboxOnionAdapter get();
}
