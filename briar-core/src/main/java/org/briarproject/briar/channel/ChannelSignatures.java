package org.briarproject.briar.channel;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.briar.api.channel.ChannelConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.inject.Inject;

@NotNullByDefault
class ChannelSignatures {

	private final CryptoComponent crypto;

	@Inject
	ChannelSignatures(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	@CryptoExecutor
	byte[] signManifest(byte[] signedInput, PrivateKey hybridPrivateKey)
			throws GeneralSecurityException {
		return crypto.hybridSign(
				ChannelConstants.SIGNING_LABEL_MANIFEST,
				signedInput, hybridPrivateKey);
	}

	@CryptoExecutor
	byte[] signPost(byte[] signedInput, PrivateKey hybridPrivateKey)
			throws GeneralSecurityException {
		return crypto.hybridSign(
				ChannelConstants.SIGNING_LABEL_POST,
				signedInput, hybridPrivateKey);
	}

	@CryptoExecutor
	byte[] signDelegation(byte[] signedInput, PrivateKey hybridPrivateKey)
			throws GeneralSecurityException {
		return crypto.hybridSign(
				ChannelConstants.SIGNING_LABEL_DELEGATION,
				signedInput, hybridPrivateKey);
	}

	boolean verifyDelegation(byte[] signature, byte[] signedInput,
			PublicKey hybridPublicKey) {
		try {
			return crypto.verifyHybridSignature(signature,
					ChannelConstants.SIGNING_LABEL_DELEGATION,
					signedInput, hybridPublicKey);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	boolean verifyManifest(byte[] signature, byte[] signedInput,
			PublicKey hybridPublicKey) {
		try {
			return crypto.verifyHybridSignature(signature,
					ChannelConstants.SIGNING_LABEL_MANIFEST,
					signedInput, hybridPublicKey);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	boolean verifyPost(byte[] signature, byte[] signedInput,
			PublicKey hybridPublicKey) {
		try {
			return crypto.verifyHybridSignature(signature,
					ChannelConstants.SIGNING_LABEL_POST,
					signedInput, hybridPublicKey);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	@CryptoExecutor
	byte[] signReaction(byte[] signedInput, PrivateKey hybridPrivateKey)
			throws GeneralSecurityException {
		return crypto.hybridSign(
				ChannelConstants.SIGNING_LABEL_REACTION,
				signedInput, hybridPrivateKey);
	}

	boolean verifyReaction(byte[] signature, byte[] signedInput,
			PublicKey hybridPublicKey) {
		try {
			return crypto.verifyHybridSignature(signature,
					ChannelConstants.SIGNING_LABEL_REACTION,
					signedInput, hybridPublicKey);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	@CryptoExecutor
	byte[] signAnnounce(byte[] signedInput, PrivateKey hybridPrivateKey)
			throws GeneralSecurityException {
		return crypto.hybridSign(
				ChannelConstants.SIGNING_LABEL_ANNOUNCE,
				signedInput, hybridPrivateKey);
	}

	boolean verifyAnnounce(byte[] signature, byte[] signedInput,
			PublicKey hybridPublicKey) {
		try {
			return crypto.verifyHybridSignature(signature,
					ChannelConstants.SIGNING_LABEL_ANNOUNCE,
					signedInput, hybridPublicKey);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	@CryptoExecutor
	byte[] signComment(byte[] signedInput, PrivateKey hybridPrivateKey)
			throws GeneralSecurityException {
		return crypto.hybridSign(
				ChannelConstants.SIGNING_LABEL_COMMENT,
				signedInput, hybridPrivateKey);
	}

	boolean verifyComment(byte[] signature, byte[] signedInput,
			PublicKey hybridPublicKey) {
		try {
			return crypto.verifyHybridSignature(signature,
					ChannelConstants.SIGNING_LABEL_COMMENT,
					signedInput, hybridPublicKey);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	@CryptoExecutor
	byte[] signApplication(byte[] signedInput, PrivateKey hybridPrivateKey)
			throws GeneralSecurityException {
		return crypto.hybridSign(
				ChannelConstants.SIGNING_LABEL_APPLICATION,
				signedInput, hybridPrivateKey);
	}

	boolean verifyApplication(byte[] signature, byte[] signedInput,
			PublicKey hybridPublicKey) {
		try {
			return crypto.verifyHybridSignature(signature,
					ChannelConstants.SIGNING_LABEL_APPLICATION,
					signedInput, hybridPublicKey);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	@CryptoExecutor
	byte[] signCheckApproval(byte[] signedInput,
			PrivateKey hybridPrivateKey)
			throws GeneralSecurityException {
		return crypto.hybridSign(
				ChannelConstants.SIGNING_LABEL_CHECK_APPROVAL,
				signedInput, hybridPrivateKey);
	}

	boolean verifyCheckApproval(byte[] signature, byte[] signedInput,
			PublicKey hybridPublicKey) {
		try {
			return crypto.verifyHybridSignature(signature,
					ChannelConstants.SIGNING_LABEL_CHECK_APPROVAL,
					signedInput, hybridPublicKey);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}
}
