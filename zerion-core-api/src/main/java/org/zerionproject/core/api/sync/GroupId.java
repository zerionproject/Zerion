package org.zerionproject.core.api.sync;

import org.zerionproject.core.api.UniqueId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class GroupId extends UniqueId {

	public static final String LABEL = "org.zerionproject.core/GROUP_ID";

	public GroupId(byte[] id) {
		super(id);
	}
}
