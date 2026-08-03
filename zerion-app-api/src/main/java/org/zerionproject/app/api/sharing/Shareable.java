package org.zerionproject.app.api.sharing;

import org.zerionproject.core.api.Nameable;
import org.zerionproject.core.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface Shareable extends Nameable {

	GroupId getId();

}
