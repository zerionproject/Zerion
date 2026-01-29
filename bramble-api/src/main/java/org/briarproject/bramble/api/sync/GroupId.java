package org.briarproject.bramble.api.sync;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;


@ThreadSafe
@NotNullByDefault
public class GroupId extends UniqueId {

	
	public static final String LABEL = "org.briarproject.bramble/GROUP_ID";

	public GroupId(byte[] id) {
		super(id);
	}
}
