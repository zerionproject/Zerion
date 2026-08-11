package org.zerionproject.app.grouptr;

interface GroupTrConstants {

	String CLIENT_ID = "org.zerionproject.grouptr";
	int MAJOR_VERSION = 0;

	String GROUP_ID_LABEL = "org.zerionproject.core/GROUP_ID";
	int FORMAT_VERSION = 1;

	String SIGNING_LABEL_GROUP_MEMBERSHIP =
			"org.zerionproject/GROUP_MEMBERSHIP";
	String SIGNING_LABEL_GROUP_EPOCH_COMMIT =
			"org.zerionproject/GROUP_EPOCH_COMMIT";

	String SIGNING_LABEL_GROUPTR_INVITE_OFFER =
			"org.zerionproject/GROUPTR_INVITE_OFFER";
	String SIGNING_LABEL_GROUPTR_INVITE_ACCEPT =
			"org.zerionproject/GROUPTR_INVITE_ACCEPT";
	String SIGNING_LABEL_GROUPTR_INVITE_DECLINE =
			"org.zerionproject/GROUPTR_INVITE_DECLINE";

	String SETTINGS_NS_PREFIX = "grouptr.g.";
	String SETTINGS_NS_INDEX = "grouptr.index";
	String SETTINGS_NS_LOCAL_PREFIX = "grouptr.local.";
	String S_SCREENSHOT_BLOCKED = "screenshotBlocked";

	String S_NAME = "name";
	String S_SALT = "salt";
	String S_CREATOR_PUBKEY = "creatorPubKey";
	String S_CREATOR_NAME = "creatorName";
	String S_CREATED = "created";
	String S_EPOCH = "epoch";
	String S_DISSOLVED = "dissolved";
	String S_MEMBERS = "members";
	String S_DEFAULT_TTL = "defaultAutoDeleteTimerMs";
	String S_STEALTH_NAME = "stealthName";
	String S_REMOVED = "removed";

	String SETTINGS_NS_INVITES_SENT = "grouptr.invites_sent";
	String SETTINGS_NS_OFFERS_PENDING = "grouptr.offers_pending";

	String S_GROUP_IDS = "groupIds";

	int GROUP_SALT_LENGTH = 32;
	int MAX_GROUP_NAME_LENGTH = 100;
	int MAX_MEMBER_NAME_LENGTH = 256;
	int MAX_GROUP_MEMBERS = 256;

	boolean FEATURE_GROUP_RELAY_PRIVACY_ENABLED = false;
}
