package org.briarproject.briar.introduction;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey;
import org.briarproject.bramble.api.crypto.HybridSignaturePublicKey;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.MlKemEncapsulation;
import org.briarproject.bramble.api.crypto.pcs.MlKemKeyPair;
import org.briarproject.bramble.api.crypto.pcs.MlKemProvider;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.briar.api.client.SessionId;
import org.briarproject.briar.introduction.IntroduceeSession.Common;
import org.briarproject.briar.introduction.IntroduceeSession.Remote;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;

import static org.briarproject.briar.api.introduction.IntroductionConstants.LABEL_ACTIVATE_MAC;
import static org.briarproject.briar.api.introduction.IntroductionConstants.LABEL_ALICE_MAC_KEY;
import static org.briarproject.briar.api.introduction.IntroductionConstants.LABEL_AUTH_MAC;
import static org.briarproject.briar.api.introduction.IntroductionConstants.LABEL_AUTH_NONCE;
import static org.briarproject.briar.api.introduction.IntroductionConstants.LABEL_AUTH_SIGN;
import static org.briarproject.briar.api.introduction.IntroductionConstants.LABEL_BOB_MAC_KEY;
import static org.briarproject.briar.api.introduction.IntroductionConstants.LABEL_MASTER_KEY;
import static org.briarproject.briar.api.introduction.IntroductionConstants.LABEL_PRE_MASTER_KEY;
import static org.briarproject.briar.api.introduction.IntroductionConstants.LABEL_SESSION_ID;
import static org.briarproject.briar.api.introduction.IntroductionManager.MAJOR_VERSION;
import static org.briarproject.briar.introduction.IntroduceeSession.Local;

@Immutable
@NotNullByDefault
class IntroductionCryptoImpl implements IntroductionCrypto {

	private final CryptoComponent crypto;
	private final ClientHelper clientHelper;
	private final MlKemProvider mlKemProvider;

	@Inject
	IntroductionCryptoImpl(
			CryptoComponent crypto,
			ClientHelper clientHelper,
			MlKemProvider mlKemProvider) {
		this.crypto = crypto;
		this.clientHelper = clientHelper;
		this.mlKemProvider = mlKemProvider;
	}

	@Override
	public SessionId getSessionId(Author introducer, Author local,
			Author remote) {
		boolean isAlice = isAlice(local.getId(), remote.getId());
		byte[] hash = crypto.hash(
				LABEL_SESSION_ID,
				introducer.getId().getBytes(),
				isAlice ? local.getId().getBytes() : remote.getId().getBytes(),
				isAlice ? remote.getId().getBytes() : local.getId().getBytes()
		);
		return new SessionId(hash);
	}

	@Override
	public KeyPair generateAgreementKeyPair() {
		return crypto.generateAgreementKeyPair();
	}

	@Override
	public boolean isAlice(AuthorId local, AuthorId remote) {
		return local.compareTo(remote) < 0;
	}

	@Override
	@SuppressWarnings("ConstantConditions")
	public SecretKey deriveMasterKey(IntroduceeSession s)
			throws GeneralSecurityException {
		return deriveMasterKey(
				s.getLocal().ephemeralPublicKey,
				s.getLocal().ephemeralPrivateKey,
				s.getRemote().ephemeralPublicKey,
				s.getLocal().alice
		);
	}

	SecretKey deriveMasterKey(PublicKey publicKey, PrivateKey privateKey,
			PublicKey remotePublicKey, boolean alice)
			throws GeneralSecurityException {
		KeyPair keyPair = new KeyPair(publicKey, privateKey);
		return crypto.deriveSharedSecret(
				LABEL_MASTER_KEY,
				remotePublicKey,
				keyPair,
				new byte[] {MAJOR_VERSION},
				alice ? publicKey.getEncoded() : remotePublicKey.getEncoded(),
				alice ? remotePublicKey.getEncoded() : publicKey.getEncoded()
		);
	}

	@Override
	public byte[][] generateMlKemEphemeralKeyPair() {
		MlKemKeyPair kp = mlKemProvider.generateKeyPair();
		return new byte[][] {
				kp.getDecapsulationKey(),
				kp.getEncapsulationKey()
		};
	}

	@Override
	public byte[][] encapsulateMlKem(byte[] peerMlKemPub) {
		MlKemEncapsulation enc = mlKemProvider.encapsulate(peerMlKemPub);
		byte[] ct = enc.getCiphertext();
		byte[] ss = enc.getSharedSecret().clone();
		java.util.Arrays.fill(enc.getSharedSecret(), (byte) 0);
		return new byte[][] {ct, ss};
	}

	@Override
	public byte[] decapsulateMlKem(byte[] localMlKemPriv, byte[] ciphertext) {
		return mlKemProvider.decapsulate(localMlKemPriv, ciphertext);
	}

	@Override
	@SuppressWarnings("ConstantConditions")
	public SecretKey derivePreMasterKey(IntroduceeSession s, byte[] kemSecret)
			throws GeneralSecurityException {
		SecretKey dhMasterKey = deriveMasterKey(s);
		return crypto.deriveKey(LABEL_PRE_MASTER_KEY, dhMasterKey, kemSecret);
	}

	@Override
	@SuppressWarnings("ConstantConditions")
	public SecretKey deriveFinalMasterKey(IntroduceeSession s,
			byte[] aliceKemSecret, byte[] bobKemSecret)
			throws GeneralSecurityException {
		SecretKey dhMasterKey = deriveMasterKey(s);
		return crypto.deriveKey(LABEL_MASTER_KEY, dhMasterKey,
				aliceKemSecret, bobKemSecret);
	}

	@Override
	public SecretKey deriveMacKey(SecretKey masterKey, boolean alice) {
		return crypto.deriveKey(
				alice ? LABEL_ALICE_MAC_KEY : LABEL_BOB_MAC_KEY,
				masterKey
		);
	}

	@Override
	@SuppressWarnings("ConstantConditions")
	public byte[] authMac(SecretKey macKey, IntroduceeSession s,
			AuthorId localAuthorId) {
		return authMac(macKey, s.getIntroducer().getId(), localAuthorId,
				s.getLocal(), s.getRemote());
	}

	byte[] authMac(SecretKey macKey, AuthorId introducerId,
			AuthorId localAuthorId, Local local, Remote remote) {
		byte[] inputs = getAuthMacInputs(introducerId, localAuthorId, local,
				remote.author.getId(), remote);
		return crypto.mac(
				LABEL_AUTH_MAC,
				macKey,
				inputs
		);
	}

	@Override
	@SuppressWarnings("ConstantConditions")
	public void verifyAuthMac(byte[] mac, IntroduceeSession s,
			AuthorId localAuthorId) throws GeneralSecurityException {
		verifyAuthMac(mac, new SecretKey(s.getRemote().macKey),
				s.getIntroducer().getId(), localAuthorId, s.getLocal(),
				s.getRemote().author.getId(), s.getRemote());
	}

	@Override
	@SuppressWarnings("ConstantConditions")
	public void verifyAuthMacWithKey(byte[] mac, IntroduceeSession s,
			AuthorId localAuthorId, SecretKey peerMacKey)
			throws GeneralSecurityException {
		verifyAuthMac(mac, peerMacKey, s.getIntroducer().getId(),
				localAuthorId, s.getLocal(),
				s.getRemote().author.getId(), s.getRemote());
	}

	void verifyAuthMac(byte[] mac, SecretKey macKey, AuthorId introducerId,
			AuthorId localAuthorId, Common local, AuthorId remoteAuthorId,
			Common remote) throws GeneralSecurityException {
		byte[] inputs = getAuthMacInputs(introducerId, remoteAuthorId, remote,
				localAuthorId, local);
		if (!crypto.verifyMac(mac, LABEL_AUTH_MAC, macKey, inputs)) {
			throw new GeneralSecurityException();
		}
	}

	@SuppressWarnings("ConstantConditions")
	private byte[] getAuthMacInputs(AuthorId introducerId,
			AuthorId localAuthorId, Common local, AuthorId remoteAuthorId,
			Common remote) {
		BdfList localInfo = BdfList.of(
				localAuthorId,
				local.acceptTimestamp,
				local.ephemeralPublicKey,
				clientHelper.toDictionary(local.transportProperties)
		);
		BdfList remoteInfo = BdfList.of(
				remoteAuthorId,
				remote.acceptTimestamp,
				remote.ephemeralPublicKey,
				clientHelper.toDictionary(remote.transportProperties)
		);
		BdfList macList = BdfList.of(
				introducerId,
				localInfo,
				remoteInfo
		);
		try {
			return clientHelper.toByteArray(macList);
		} catch (FormatException e) {
			throw new AssertionError();
		}
	}

	@Override
	public byte[] sign(SecretKey macKey, PrivateKey privateKey,
			@Nullable byte[] localMlDsaPriv,
			@Nullable byte[] remoteMlDsaPub)
			throws GeneralSecurityException {
		if (localMlDsaPriv == null || remoteMlDsaPub == null) {
			throw new GeneralSecurityException(
					"Introduction requires hybrid (Ed25519 + ML-DSA-65) " +
							"signature in v1.7+; peer is on a pre-v1.6 " +
							"build without ML-DSA");
		}
		byte[] nonce = getNonce(macKey);
		HybridSignaturePrivateKey hybridKey =
				new HybridSignaturePrivateKey(privateKey.getEncoded(),
						localMlDsaPriv);
		return crypto.hybridSign(LABEL_AUTH_SIGN, nonce, hybridKey);
	}

	@Override
	@SuppressWarnings("ConstantConditions")
	public void verifySignature(byte[] signature, IntroduceeSession s)
			throws GeneralSecurityException {
		SecretKey macKey = new SecretKey(s.getRemote().macKey);
		verifySignature(macKey, s.getRemote().author.getPublicKey(), signature,
				s.getRemote().mlDsaPubKey);
	}

	@Override
	@SuppressWarnings("ConstantConditions")
	public void verifySignatureWithKey(byte[] signature, IntroduceeSession s,
			SecretKey peerMacKey) throws GeneralSecurityException {
		verifySignature(peerMacKey, s.getRemote().author.getPublicKey(),
				signature, s.getRemote().mlDsaPubKey);
	}

	void verifySignature(SecretKey macKey, PublicKey ed25519PublicKey,
			byte[] signature, @Nullable byte[] remoteMlDsaPubKey)
			throws GeneralSecurityException {
		if (remoteMlDsaPubKey == null) {
			throw new GeneralSecurityException(
					"Introduction requires hybrid (Ed25519 + ML-DSA-65) " +
							"signature in v1.7+; peer is on a pre-v1.6 " +
							"build without ML-DSA");
		}
		if (signature.length != HYBRID_SIGNATURE_BYTES) {
			throw new GeneralSecurityException();
		}
		byte[] nonce = getNonce(macKey);
		HybridSignaturePublicKey hybridPub = new HybridSignaturePublicKey(
				ed25519PublicKey.getEncoded(), remoteMlDsaPubKey);
		if (!crypto.verifyHybridSignature(signature, LABEL_AUTH_SIGN,
				nonce, hybridPub)) {
			throw new GeneralSecurityException();
		}
	}

	private byte[] getNonce(SecretKey macKey) {
		return crypto.mac(LABEL_AUTH_NONCE, macKey);
	}

	@Override
	public byte[] activateMac(IntroduceeSession s) {
		if (s.getLocal().macKey == null)
			throw new AssertionError("Local MAC key is null");
		return activateMac(new SecretKey(s.getLocal().macKey));
	}

	byte[] activateMac(SecretKey macKey) {
		return crypto.mac(
				LABEL_ACTIVATE_MAC,
				macKey
		);
	}

	@Override
	public void verifyActivateMac(byte[] mac, IntroduceeSession s)
			throws GeneralSecurityException {
		if (s.getRemote().macKey == null)
			throw new AssertionError("Remote MAC key is null");
		verifyActivateMac(mac, new SecretKey(s.getRemote().macKey));
	}

	void verifyActivateMac(byte[] mac, SecretKey macKey)
			throws GeneralSecurityException {
		if (!crypto.verifyMac(mac, LABEL_ACTIVATE_MAC, macKey)) {
			throw new GeneralSecurityException();
		}
	}

}
