package org.zerionproject.app.api.channel;

public enum ChannelRejoinResult {
	UPDATED, APPLY_AGAIN, APPLY_AGAIN_UNREACHABLE, UNCHANGED, CHECK_LINK,
	NOT_RESTORED, TRY_LATER, TRYING, DELETED,
	TOO_MANY_LINKS
}
