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

	public static final int MAX_ATTACHMENT_THUMBNAIL_BYTES = 48 * 1024;
	public static final int MAX_ATTACHMENT_CAPTION_BYTES = 4096;

	public static final int MAX_SUBSCRIBER_POSTS_PER_CHANNEL = 10_000;
	public static final long MAX_SUBSCRIBER_POST_BYTES_PER_CHANNEL =
			32L * 1024L * 1024L;
	public static final long MAX_SUBSCRIBER_BYTES_PER_POST = 1024L * 1024L;
	public static final long MAX_SUBSCRIBER_ATTACHMENT_BYTES_PER_CHANNEL =
			256L * 1024L * 1024L;

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
	public static final String SIGNING_LABEL_POST_V2 =
			"org.zerionproject/CHANNEL_POST_V2";
	public static final int POST_SALT_BYTES = 16;

	public static final int PROTOCOL_VERSION = 2;
	public static final String SIGNING_LABEL_DELEGATION =
			"org.zerionproject/CHANNEL_DELEGATION";

	public static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

	public static final long BOOTSTRAP_HMAC_NONCE_BYTES = 16L;
	public static final long PULL_BATCH_MAX_POSTS = 100L;

	public static final long MAX_SEQUENCE_NUMBER = 1L << 62;
	public static final long RESTORE_SEQUENCE_JUMP = 1L << 32;
	public static final long RESTORE_DELEGATION_SEQUENCE_JUMP = 1L << 20;
	public static final int MAX_REMEMBERED_ONIONS = 12;
	public static final long ONION_FALLBACK_AFTER_MS = 15L * 60L * 1000L;

	public static final int MAX_EDITOR_POSTS_PER_HOUR = 30;
	public static final int MAX_EDITOR_POSTS_PER_DAY = 150;
	public static final long MAX_EDITOR_POST_BYTES_PER_CHANNEL =
			64L * 1024L * 1024L;
	public static final long MAX_PULL_RESPONSE_REACTIONS = 25600L;

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
	public static final int MAX_REACTIONS_PER_POST = 64;
	public static final int MAX_REACTIONS_PER_CHANNEL = 256;
	public static final int MAX_REACTIONS_PER_SIGNER_PER_CHANNEL = 32;
	public static final long MAX_REACTION_BYTES_PER_CHANNEL = 1536L * 1024L;
	public static final int MAX_ANONYMOUS_ITEMS_PER_CHANNEL = 128;
	public static final int MAX_ANONYMOUS_ITEMS_PER_POST = 32;
	public static final int MAX_ANONYMOUS_ITEMS_PER_SIGNER = 8;
	public static final long MAX_ANONYMOUS_ITEM_BYTES_PER_CHANNEL =
			768L * 1024L;
	public static final long ITEM_MAX_AGE_MS = 48L * 60L * 60L * 1000L;
	public static final long ITEM_MAX_FUTURE_MS = 2L * 60L * 60L * 1000L;
	public static final long KNOWN_SIGNER_WRITE_BURST_BYTES =
			16L * 1024L * 1024L;
	public static final long KNOWN_SIGNER_WRITE_BYTES_PER_HOUR =
			128L * 1024L * 1024L;
	public static final int MAX_BANNED_KEYS_PER_CHANNEL = 4096;
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
	public static final int MAX_COMMENT_AUTHOR_NAME_CHARS = 64;
	public static final int MAX_COMMENTS_PER_POST = 64;
	public static final int MAX_COMMENTS_PER_AUTHOR = 32;
	public static final int MAX_COMMENTS_PER_CHANNEL = 256;
	public static final long MAX_COMMENT_BYTES_PER_CHANNEL = 1536L * 1024L;
	public static final long MAX_PULL_RESPONSE_COMMENTS = 4096L;
	public static final long CHANNEL_WRITE_BURST_BYTES = 64L * 1024L * 1024L;
	public static final long CHANNEL_WRITE_BYTES_PER_HOUR =
			512L * 1024L * 1024L;
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

	public static final String WIRE_TYPE_SUBMIT_POST =
			"ZERION_CHANNEL_SUBMIT_POST_V1";
	public static final String WIRE_TYPE_SUBMIT_POST_ACK =
			"ZERION_CHANNEL_SUBMIT_POST_ACK_V1";
	public static final String SUBMIT_STATUS_OK = "OK";
	public static final String SUBMIT_STATUS_STALE = "STALE";
	public static final String SUBMIT_STATUS_REFUSED = "REFUSED";

	public static final int ONION_ROTATION_MIN_DAYS = 28;
	public static final int ONION_ROTATION_MAX_DAYS = 35;
	public static final int ONION_MIGRATION_DAYS = 30;
	public static final int DELETED_CHANNEL_GRACE_DAYS = 14;

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
	public static final String DELETED_POST_PLACEHOLDER = "[deleted]";

	public static final boolean DISCUSSIONS_IN_MANIFEST = false;
}
