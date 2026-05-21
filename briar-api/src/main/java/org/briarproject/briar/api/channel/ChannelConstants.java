package org.briarproject.briar.api.channel;

public final class ChannelConstants {

	private ChannelConstants() {
	}

	public static final String CLIENT_ID = "org.briarproject.zerion.channel";
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

	public static final String SETTINGS_NAMESPACE_UNREAD =
			"channel-unread";
	public static final String SETTINGS_NAMESPACE_SUBSCRIPTIONS =
			"channel-subscriptions";
	public static final String SETTINGS_NAMESPACE_MIRROR_OPT_IN =
			"channel-mirror-opt-in";

	public static final String SIGNING_LABEL_MANIFEST =
			"org.briarproject.zerion/CHANNEL_MANIFEST";
	public static final String SIGNING_LABEL_POST =
			"org.briarproject.zerion/CHANNEL_POST";
	public static final String SIGNING_LABEL_DELEGATION =
			"org.briarproject.zerion/CHANNEL_DELEGATION";

	public static final long BOOTSTRAP_HMAC_NONCE_BYTES = 16L;
	public static final long PULL_BATCH_MAX_POSTS = 100L;

	public static final int CONTENT_KEY_BYTES = 32;
	public static final int CONTENT_KEY_HASH_BYTES = 32;
	public static final String CONTENT_KEY_WRAP_INFO =
			"ZERION_CHANNEL_CONTENT_KEY_WRAP";

	public static final int MAX_ACTIVE_DELEGATIONS_PER_CHANNEL = 8;

	public static final String WIRE_TYPE_DELEGATION =
			"ZERION_CHANNEL_DELEGATION_V1";

	public static final long TTL_OFF = 0L;
	public static final long TTL_ONE_HOUR_MS = 60L * 60L * 1000L;
	public static final long TTL_ONE_DAY_MS = 24L * TTL_ONE_HOUR_MS;
	public static final long TTL_ONE_WEEK_MS = 7L * TTL_ONE_DAY_MS;
	public static final long TTL_THIRTY_DAYS_MS = 30L * TTL_ONE_DAY_MS;
}
