package org.briarproject.bramble.api.crypto;


public interface KeyAgreementCrypto {

	
	String COMMIT_LABEL = "org.briarproject.bramble.keyagreement/COMMIT";

	
	String CONFIRMATION_KEY_LABEL =
			"org.briarproject.bramble.keyagreement/CONFIRMATION_KEY";

	
	String CONFIRMATION_MAC_LABEL =
			"org.briarproject.bramble.keyagreement/CONFIRMATION_MAC";

	
	byte[] deriveKeyCommitment(PublicKey publicKey);

	
	byte[] deriveConfirmationRecord(SecretKey sharedSecret,
			byte[] theirPayload, byte[] ourPayload,
			PublicKey theirPublicKey, KeyPair ourKeyPair,
			boolean alice, boolean aliceRecord);
}
