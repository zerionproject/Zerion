package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.CryptoExecutor;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.Nullable;
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

	@CryptoExecutor
	byte[] signTombstone(byte[] signedInput, PrivateKey hybridPrivateKey)
			throws GeneralSecurityException {
		return crypto.hybridSign(
				ChannelConstants.SIGNING_LABEL_CHANNEL_TOMBSTONE,
				signedInput, hybridPrivateKey);
	}

	boolean verifyTombstone(byte[] signature, byte[] signedInput,
			PublicKey hybridPublicKey) {
		try {
			return crypto.verifyHybridSignature(signature,
					ChannelConstants.SIGNING_LABEL_CHANNEL_TOMBSTONE,
					signedInput, hybridPublicKey);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}

	@CryptoExecutor
	byte[] signUserApplication(byte[] signedInput,
			PrivateKey ed25519PrivateKey, @Nullable byte[] mlDsaPriv)
			throws GeneralSecurityException {
		return signUser(ChannelConstants.SIGNING_LABEL_APPLICATION,
				signedInput, ed25519PrivateKey, mlDsaPriv);
	}

	boolean verifyUserApplication(byte[] signature, byte[] signedInput,
			PublicKey ed25519PublicKey, @Nullable byte[] mlDsaPub) {
		return verifyUser(ChannelConstants.SIGNING_LABEL_APPLICATION,
				signature, signedInput, ed25519PublicKey, mlDsaPub);
	}

	@CryptoExecutor
	byte[] signUserCheckApproval(byte[] signedInput,
			PrivateKey ed25519PrivateKey, @Nullable byte[] mlDsaPriv)
			throws GeneralSecurityException {
		return signUser(ChannelConstants.SIGNING_LABEL_CHECK_APPROVAL,
				signedInput, ed25519PrivateKey, mlDsaPriv);
	}

	boolean verifyUserCheckApproval(byte[] signature, byte[] signedInput,
			PublicKey ed25519PublicKey, @Nullable byte[] mlDsaPub) {
		return verifyUser(ChannelConstants.SIGNING_LABEL_CHECK_APPROVAL,
				signature, signedInput, ed25519PublicKey, mlDsaPub);
	}

	@CryptoExecutor
	byte[] signUserReaction(byte[] signedInput,
			PrivateKey ed25519PrivateKey, @Nullable byte[] mlDsaPriv)
			throws GeneralSecurityException {
		return signUser(ChannelConstants.SIGNING_LABEL_REACTION,
				signedInput, ed25519PrivateKey, mlDsaPriv);
	}

	boolean verifyUserReaction(byte[] signature, byte[] signedInput,
			PublicKey ed25519PublicKey, @Nullable byte[] mlDsaPub) {
		return verifyUser(ChannelConstants.SIGNING_LABEL_REACTION,
				signature, signedInput, ed25519PublicKey, mlDsaPub);
	}

	@CryptoExecutor
	byte[] signUserComment(byte[] signedInput,
			PrivateKey ed25519PrivateKey, @Nullable byte[] mlDsaPriv)
			throws GeneralSecurityException {
		return signUser(ChannelConstants.SIGNING_LABEL_COMMENT,
				signedInput, ed25519PrivateKey, mlDsaPriv);
	}

	boolean verifyUserComment(byte[] signature, byte[] signedInput,
			PublicKey ed25519PublicKey, @Nullable byte[] mlDsaPub) {
		return verifyUser(ChannelConstants.SIGNING_LABEL_COMMENT,
				signature, signedInput, ed25519PublicKey, mlDsaPub);
	}

	@CryptoExecutor
	byte[] signUserAnnounce(byte[] signedInput,
			PrivateKey ed25519PrivateKey, @Nullable byte[] mlDsaPriv)
			throws GeneralSecurityException {
		return signUser(ChannelConstants.SIGNING_LABEL_ANNOUNCE,
				signedInput, ed25519PrivateKey, mlDsaPriv);
	}

	boolean verifyUserAnnounce(byte[] signature, byte[] signedInput,
			PublicKey ed25519PublicKey, @Nullable byte[] mlDsaPub) {
		return verifyUser(ChannelConstants.SIGNING_LABEL_ANNOUNCE,
				signature, signedInput, ed25519PublicKey, mlDsaPub);
	}

	private byte[] signUser(String label, byte[] signedInput,
			PrivateKey ed25519PrivateKey, @Nullable byte[] mlDsaPriv)
			throws GeneralSecurityException {
		if (mlDsaPriv == null || mlDsaPriv.length == 0) {
			throw new GeneralSecurityException(
					"Local ML-DSA private key missing — refusing "
							+ "classical-only channel user signature");
		}
		HybridSignaturePrivateKey hybrid =
				new HybridSignaturePrivateKey(
						ed25519PrivateKey.getEncoded(), mlDsaPriv);
		return crypto.hybridSign(label, signedInput, hybrid);
	}

	private boolean verifyUser(String label, byte[] signature,
			byte[] signedInput, PublicKey ed25519PublicKey,
			@Nullable byte[] mlDsaPub) {
		if (mlDsaPub == null || mlDsaPub.length == 0) {
			return false;
		}
		try {
			HybridSignaturePublicKey hybrid =
					new HybridSignaturePublicKey(
							ed25519PublicKey.getEncoded(), mlDsaPub);
			return crypto.verifyHybridSignature(signature, label,
					signedInput, hybrid);
		} catch (GeneralSecurityException e) {
			return false;
		}
	}
}
