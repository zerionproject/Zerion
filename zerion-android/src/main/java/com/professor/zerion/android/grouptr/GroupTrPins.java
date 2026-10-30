package com.professor.zerion.android.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;

@NotNullByDefault
final class GroupTrPins {

	private GroupTrPins() {
	}

	static boolean mayPin(@Nullable GroupTrState s, @Nullable byte[] localPub) {
		return s != null && !s.isDissolved() && localPub != null
				&& Arrays.equals(localPub, s.getCreatorPubKey());
	}
}
