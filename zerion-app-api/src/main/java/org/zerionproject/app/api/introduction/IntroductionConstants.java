package org.zerionproject.app.api.introduction;

import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

public interface IntroductionConstants {

	int MAX_INTRODUCTION_TEXT_LENGTH = MAX_MESSAGE_BODY_LENGTH - 1024;

	String LABEL_SESSION_ID = "org.zerionproject.app.introduction/SESSION_ID";

	String LABEL_MASTER_KEY = "org.zerionproject.app.introduction/MASTER_KEY";

	String LABEL_ALICE_MAC_KEY =
			"org.zerionproject.app.introduction/ALICE_MAC_KEY";

	String LABEL_BOB_MAC_KEY =
			"org.zerionproject.app.introduction/BOB_MAC_KEY";

	String LABEL_AUTH_MAC = "org.zerionproject.app.introduction/AUTH_MAC";

	String LABEL_AUTH_SIGN = "org.zerionproject.app.introduction/AUTH_SIGN";

	String LABEL_AUTH_NONCE = "org.zerionproject.app.introduction/AUTH_NONCE";

	String LABEL_ACTIVATE_MAC =
			"org.zerionproject.app.introduction/ACTIVATE_MAC";

	String LABEL_ALICE_ACTIVATE_KEY =
			"org.zerionproject.app.introduction/ALICE_ACTIVATE_KEY";

	String LABEL_BOB_ACTIVATE_KEY =
			"org.zerionproject.app.introduction/BOB_ACTIVATE_KEY";

	String LABEL_SHARED_SECRET_V2 =
			"org.zerionproject.app.introduction/SHARED_SECRET_V2";

	String LABEL_AUTH_SECRET_V2 =
			"org.zerionproject.app.introduction/AUTH_SECRET_V2";

	String LABEL_ALICE_AUTH_MAC_KEY_V2 =
			"org.zerionproject.app.introduction/ALICE_AUTH_MAC_KEY_V2";

	String LABEL_BOB_AUTH_MAC_KEY_V2 =
			"org.zerionproject.app.introduction/BOB_AUTH_MAC_KEY_V2";

	String LABEL_ALICE_AUTH_NONCE_KEY_V2 =
			"org.zerionproject.app.introduction/ALICE_AUTH_NONCE_KEY_V2";

	String LABEL_BOB_AUTH_NONCE_KEY_V2 =
			"org.zerionproject.app.introduction/BOB_AUTH_NONCE_KEY_V2";

	String LABEL_FINAL_SECRET_V2 =
			"org.zerionproject.app.introduction/FINAL_SECRET_V2";

	String LABEL_ALICE_ACTIVATE_KEY_V2 =
			"org.zerionproject.app.introduction/ALICE_ACTIVATE_KEY_V2";

	String LABEL_BOB_ACTIVATE_KEY_V2 =
			"org.zerionproject.app.introduction/BOB_ACTIVATE_KEY_V2";

	String LABEL_ROOT_KEY_V2 =
			"org.zerionproject.app.introduction/ROOT_KEY_V2";

	boolean INTRODUCTION_HYBRID_KEM_ENABLED = true;

	String LABEL_PRE_MASTER_KEY =
			"org.zerionproject.app.introduction/PRE_MASTER_KEY";

	int HYBRID_EPHEMERAL_PUBLIC_KEY_BYTES = 1216;

	int INTRODUCTION_ML_KEM_PUBLIC_KEY_BYTES = 1184;

	int INTRODUCTION_KEM_CIPHERTEXT_BYTES = 1088;

}
