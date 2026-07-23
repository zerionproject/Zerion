package org.zerionproject.app.api.channel;

public final class ChannelConstants {

	private ChannelConstants() {
	}

	public static final String CLIENT_ID = "org.zerionproject.channel";
	public static final int MAJOR_VERSION = 0;
	public static final int MINOR_VERSION = 1;

	public static final String WIRE_TYPE_MANIFEST = "ZERION_CHANNEL_MANIFEST_V1";
	public static final String WIRE_TYPE_POST = "ZERION_CHANNEL_POST_V1";
	public static final String WIRE_TYPE_SUBSCRIPTION_HINT =
			"ZERION_CHANNEL_SUBSCRIPTION_HINT_V1";
	public static final String WIRE_TYPE_PULL_REQUEST =
			"ZERION_CHANNEL_PULL_REQUEST_V1";
	public static final String WIRE_TYPE_PULL_RESPONSE =
			"ZERION_CHANNEL_PULL_RESPONSE_V1";

	public static final int CHANNEL_ID_BYTES = 32;
	public static final int CHANNEL_SALT_BYTES = 16;
	public static final int JOIN_CAPABILITY_BYTES = 32;
	public static final int PREV_HASH_BYTES = 32;

	public static final int MAX_CHANNEL_NAME_CHARS = 64;
	public static final int MAX_CHANNEL_DESCRIPTION_CHARS = 1024;
	public static final int MAX_POST_BODY_CHARS = 4096;
	public static final int MAX_ATTACHMENTS_PER_POST = 8;
	public static final long MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024;
	public static final int MAX_TTL_SECONDS = 30 * 24 * 60 * 60;

	public static final long DEFAULT_RECENT_POSTS_RETAINED = 500L;

	public static final String INVITE_LINK_SCHEME = "zerion";
	public static final String INVITE_LINK_HOST = "channel";
	public static final String INVITE_LINK_CAPABILITY_PARAM = "k";
	public static final String INVITE_LINK_ONION_PARAM = "o";
	public static final String INVITE_LINK_MLDSA_PARAM = "m";
	public static final int INVITE_LINK_MAX_LENGTH = 4096;

	public static final String SETTINGS_NAMESPACE_UNREAD =
			"channel-unread";
	public static final String SETTINGS_NAMESPACE_SUBSCRIPTIONS =
			"channel-subscriptions";
	public static final String SETTINGS_NAMESPACE_MIRROR_OPT_IN =
			"channel-mirror-opt-in";

	public static final String SIGNING_LABEL_MANIFEST =
			"org.zerionproject/CHANNEL_MANIFEST";
	public static final String SIGNING_LABEL_POST =
			"org.zerionproject/CHANNEL_POST";
	public static final String SIGNING_LABEL_DELEGATION =
			"org.zerionproject/CHANNEL_DELEGATION";

	public static final long BOOTSTRAP_HMAC_NONCE_BYTES = 16L;
	public static final long PULL_BATCH_MAX_POSTS = 100L;

	public static final int CONTENT_KEY_BYTES = 32;
	public static final int CONTENT_KEY_HASH_BYTES = 32;
	public static final String CONTENT_KEY_WRAP_INFO =
			"ZERION_CHANNEL_CONTENT_KEY_WRAP";

	public static final int MAX_ACTIVE_DELEGATIONS_PER_CHANNEL = 8;

	public static final String WIRE_TYPE_DELEGATION =
			"ZERION_CHANNEL_DELEGATION_V1";
	public static final String WIRE_TYPE_GET_ATTACHMENT =
			"ZERION_CHANNEL_GET_ATTACHMENT_V1";
	public static final String WIRE_TYPE_ATTACHMENT_BLOB =
			"ZERION_CHANNEL_ATTACHMENT_BLOB_V1";
	public static final String WIRE_TYPE_POST_REACTION =
			"ZERION_CHANNEL_POST_REACTION_V1";
	public static final String WIRE_TYPE_REACTION_ACK =
			"ZERION_CHANNEL_REACTION_ACK_V1";
	public static final String SIGNING_LABEL_REACTION =
			"org.zerionproject/CHANNEL_REACTION";
	public static final int MAX_REACTION_EMOJI_BYTES = 32;
	public static final int MAX_REACTIONS_PER_POST = 256;
	public static final String WIRE_TYPE_ANNOUNCE =
			"ZERION_CHANNEL_ANNOUNCE_V1";
	public static final String WIRE_TYPE_ANNOUNCE_ACK =
			"ZERION_CHANNEL_ANNOUNCE_ACK_V1";
	public static final String SIGNING_LABEL_ANNOUNCE =
			"org.zerionproject/CHANNEL_ANNOUNCE";
	public static final int MAX_DISPLAY_NAME_BYTES = 64;
	public static final int MAX_ANNOUNCED_SUBSCRIBERS = 4096;
	public static final String WIRE_TYPE_POST_COMMENT =
			"ZERION_CHANNEL_POST_COMMENT_V1";
	public static final String WIRE_TYPE_COMMENT_ACK =
			"ZERION_CHANNEL_COMMENT_ACK_V1";
	public static final String SIGNING_LABEL_COMMENT =
			"org.zerionproject/CHANNEL_COMMENT";
	public static final int MAX_COMMENT_BODY_CHARS = 1024;
	public static final int MAX_COMMENTS_PER_CHANNEL = 4096;
	public static final int MAX_COMMENTS_PER_AUTHOR = 256;
	public static final String WIRE_TYPE_APPLY_TO_JOIN =
			"ZERION_CHANNEL_APPLY_TO_JOIN_V1";
	public static final String WIRE_TYPE_APPLY_ACK =
			"ZERION_CHANNEL_APPLY_ACK_V1";
	public static final String WIRE_TYPE_CHECK_APPROVAL =
			"ZERION_CHANNEL_CHECK_APPROVAL_V1";
	public static final String WIRE_TYPE_APPROVAL_RESPONSE =
			"ZERION_CHANNEL_APPROVAL_RESPONSE_V1";
	public static final String SIGNING_LABEL_APPLICATION =
			"org.zerionproject/CHANNEL_APPLICATION";
	public static final String SIGNING_LABEL_CHECK_APPROVAL =
			"org.zerionproject/CHANNEL_CHECK_APPROVAL";
	public static final String APPROVAL_WRAP_LABEL =
			"org.zerionproject/CHANNEL_APPROVAL_WRAP";
	public static final int MAX_PENDING_APPLICATIONS = 256;
	public static final String INVITE_LINK_APPROVAL_PARAM = "p";

	public static final String WIRE_TYPE_CHANNEL_TOMBSTONE =
			"ZERION_CHANNEL_TOMBSTONE_V1";
	public static final String SIGNING_LABEL_CHANNEL_TOMBSTONE =
			"org.zerionproject/CHANNEL_TOMBSTONE";
	public static final String SETTINGS_NAMESPACE_TOMBSTONES =
			"zerion-channels-tombstones";

	public static final long TTL_OFF = 0L;
	public static final long TTL_ONE_HOUR_MS = 60L * 60L * 1000L;
	public static final long TTL_ONE_DAY_MS = 24L * TTL_ONE_HOUR_MS;
	public static final long TTL_ONE_WEEK_MS = 7L * TTL_ONE_DAY_MS;
	public static final long TTL_THIRTY_DAYS_MS = 30L * TTL_ONE_DAY_MS;

	public static final String TOMBSTONE_PREFIX = "ZRN_TOMBSTONE:";

	public static final boolean DISCUSSIONS_IN_MANIFEST = false;
}
