package org.briarproject.briar.grouptr;

interface GroupTrConstants {

	String CLIENT_ID = "org.briarproject.zerion.grouptr";
	int MAJOR_VERSION = 0;

	String GROUP_ID_LABEL = "org.briarproject.bramble/GROUP_ID";
	int FORMAT_VERSION = 1;

	String SIGNING_LABEL_GROUP_MEMBERSHIP =
			"org.briarproject.zerion/GROUP_MEMBERSHIP";
	String SIGNING_LABEL_GROUP_EPOCH_COMMIT =
			"org.briarproject.zerion/GROUP_EPOCH_COMMIT";

	String SETTINGS_NS_PREFIX = "grouptr.g.";
	String SETTINGS_NS_INDEX = "grouptr.index";

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

	String S_GROUP_IDS = "groupIds";

	int GROUP_SALT_LENGTH = 32;
	int MAX_GROUP_NAME_LENGTH = 100;
	int MAX_MEMBER_NAME_LENGTH = 256;
}
