package org.zerionproject.core.crypto;

import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.zerionproject.core.api.UniqueId;
import org.zerionproject.core.api.crypto.AgreementPrivateKey;
import org.zerionproject.core.api.crypto.AgreementPublicKey;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.HybridEncapsulationResult;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.system.SecureRandomProvider;
import org.zerionproject.core.util.Base32;
import org.zerionproject.core.util.ByteUtils;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;
import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static java.lang.System.arraycopy;
import static org.zerionproject.core.api.crypto.CryptoConstants.KEY_TYPE_AGREEMENT;
import static org.zerionproject.core.api.crypto.CryptoConstants.KEY_TYPE_SIGNATURE;
import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_CIPHERTEXT;
import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_PASSWORD;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_STRENGTHENER_ERROR;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_AGREEMENT;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_SIGNATURE;
import static org.zerionproject.core.util.ByteUtils.INT_32_BYTES;
import static org.zerionproject.core.util.StringUtils.US_ASCII;

@NotNullByDefault
class CryptoComponentImpl implements CryptoComponent {
	private static final int STORAGE_IV_BYTES = 24;
	private static final int PBKDF_SALT_BYTES = 32;
	private static final byte PBKDF_FORMAT_SCRYPT = 0;
	private static final byte PBKDF_FORMAT_SCRYPT_STRENGTHENED = 1;
	private static final byte PBKDF_FORMAT_ARGON2ID = 2;
	private static final byte PBKDF_FORMAT_ARGON2ID_STRENGTHENED = 3;
	private static final byte ONION_HS_PROTOCOL_VERSION = 3;
	private static final int ONION_CHECKSUM_BYTES = 2;

	private final SecureRandom secureRandom;
	private final PasswordBasedKdf scryptKdf;
	private final PasswordBasedKdf argon2idKdf;
	private final Curve25519 curve25519;
	private final KeyParser agreementKeyParser, signatureKeyParser;
	private final HybridKeyAgreement hybridKeyAgreement;
	private final HybridSignature hybridSignature;
	private final KeyParser hybridAgreementKeyParser;
	private final KeyParser hybridSignatureKeyParser;

	CryptoComponentImpl(SecureRandomProvider secureRandomProvider,
			PasswordBasedKdf passwordBasedKdf) {
		this(secureRandomProvider,
				passwordBasedKdf instanceof ScryptKdf ?
						(ScryptKdf) passwordBasedKdf : null,
				passwordBasedKdf instanceof Argon2idKdf ?
						(Argon2idKdf) passwordBasedKdf : null);
	}

	@Inject
	CryptoComponentImpl(SecureRandomProvider secureRandomProvider,
			ScryptKdf scryptKdf, Argon2idKdf argon2idKdf) {
		Provider provider = secureRandomProvider.getProvider();
		if (provider != null) {
			installSecureRandomProvider(provider);
		}
		secureRandom = new SecureRandom();
		this.scryptKdf = scryptKdf;
		this.argon2idKdf = argon2idKdf;
		curve25519 = Curve25519.getInstance("java");
		agreementKeyParser = new AgreementKeyParser();
		signatureKeyParser = new SignatureKeyParser();

		hybridKeyAgreement = new HybridKeyAgreement(secureRandom);
		hybridSignature = new HybridSignature(secureRandom);
		MlKem768 mlKem768 = new MlKem768(secureRandom);
		MlDsa65 mlDsa65 = new MlDsa65(secureRandom);
		hybridAgreementKeyParser = new HybridAgreementKeyParser(mlKem768);
		hybridSignatureKeyParser = new HybridSignatureKeyParser(mlDsa65);
	}
	private void installSecureRandomProvider(Provider provider) {
		Provider[] providers = Security.getProviders("SecureRandom.SHA1PRNG");
		if (providers == null || providers.length == 0
				|| !provider.getClass().equals(providers[0].getClass())) {
			Security.insertProviderAt(provider, 1);
		}
		SecureRandom random = new SecureRandom();
		if (!provider.getClass().equals(random.getProvider().getClass())) {
			throw new SecurityException("Wrong SecureRandom provider: "
					+ random.getProvider().getClass());
		}
		try {
			random = SecureRandom.getInstance("SHA1PRNG");
		} catch (NoSuchAlgorithmException e) {
			throw new SecurityException(e);
		}
		if (!provider.getClass().equals(random.getProvider().getClass())) {
			throw new SecurityException("Wrong SHA1PRNG provider: "
					+ random.getProvider().getClass());
		}
	}

	@Override
	public UniqueId generateUniqueId() {
		byte[] b = new byte[UniqueId.LENGTH];
		secureRandom.nextBytes(b);
		return new UniqueId(b);
	}

	@Override
	public SecretKey generateSecretKey() {
		byte[] b = new byte[SecretKey.LENGTH];
		secureRandom.nextBytes(b);
		return new SecretKey(b);
	}

	@Override
	public SecureRandom getSecureRandom() {
		return secureRandom;
	}
	byte[] performRawKeyAgreement(PrivateKey priv, PublicKey pub)
			throws GeneralSecurityException {
		if (!priv.getKeyType().equals(KEY_TYPE_AGREEMENT))
			throw new IllegalArgumentException();
		if (!pub.getKeyType().equals(KEY_TYPE_AGREEMENT))
			throw new IllegalArgumentException();
		byte[] secret = curve25519.calculateAgreement(pub.getEncoded(),
				priv.getEncoded());
		byte allZero = 0;
		for (byte b : secret) allZero |= b;
		if (allZero == 0) throw new GeneralSecurityException();
		return secret;
	}

	@Override
	public KeyPair generateAgreementKeyPair() {
		Curve25519KeyPair keyPair = curve25519.generateKeyPair();
		PublicKey pub = new AgreementPublicKey(keyPair.getPublicKey());
		PrivateKey priv = new AgreementPrivateKey(keyPair.getPrivateKey());
		return new KeyPair(pub, priv);
	}

	@Override
	public KeyParser getAgreementKeyParser() {
		return agreementKeyParser;
	}

	@Override
	public KeyPair generateSignatureKeyPair() {
		Ed25519PrivateKeyParameters priv =
				new Ed25519PrivateKeyParameters(secureRandom);
		Ed25519PublicKeyParameters pub = priv.generatePublicKey();
		PublicKey publicKey = new SignaturePublicKey(pub.getEncoded());
		PrivateKey privateKey = new SignaturePrivateKey(priv.getEncoded());
		return new KeyPair(publicKey, privateKey);
	}

	@Override
	public KeyParser getSignatureKeyParser() {
		return signatureKeyParser;
	}

	@Override
	public SecretKey deriveKey(String label, SecretKey k, byte[]... inputs) {
		byte[] mac = mac(label, k, inputs);
		if (mac.length != SecretKey.LENGTH) throw new IllegalStateException();
		return new SecretKey(mac);
	}

	@Override
	public SecretKey deriveSharedSecret(String label, PublicKey theirPublicKey,
			KeyPair ourKeyPair, byte[]... inputs)
			throws GeneralSecurityException {
		PrivateKey ourPrivateKey = ourKeyPair.getPrivate();
		byte[][] hashInputs = new byte[inputs.length + 1][];
		hashInputs[0] = performRawKeyAgreement(ourPrivateKey, theirPublicKey);
		arraycopy(inputs, 0, hashInputs, 1, inputs.length);
		try {
			byte[] hash = hash(label, hashInputs);
			if (hash.length != SecretKey.LENGTH)
				throw new IllegalStateException();
			return new SecretKey(hash);
		} finally {
			java.util.Arrays.fill(hashInputs[0], (byte) 0);
		}
	}

	@Override
	@Deprecated
	public SecretKey deriveSharedSecretBadly(String label,
			PublicKey theirStaticPublicKey, PublicKey theirEphemeralPublicKey,
			KeyPair ourStaticKeyPair, KeyPair ourEphemeralKeyPair,
			boolean alice, byte[]... inputs) throws GeneralSecurityException {
		PrivateKey ourStaticPrivateKey = ourStaticKeyPair.getPrivate();
		PrivateKey ourEphemeralPrivateKey = ourEphemeralKeyPair.getPrivate();
		byte[][] hashInputs = new byte[inputs.length + 3][];
		hashInputs[0] = performRawKeyAgreement(ourStaticPrivateKey,
				theirStaticPublicKey);
		if (alice) {
			hashInputs[1] = performRawKeyAgreement(ourStaticPrivateKey,
					theirEphemeralPublicKey);
			hashInputs[2] = performRawKeyAgreement(ourEphemeralPrivateKey,
					theirStaticPublicKey);
		} else {
			hashInputs[1] = performRawKeyAgreement(ourEphemeralPrivateKey,
					theirStaticPublicKey);
			hashInputs[2] = performRawKeyAgreement(ourStaticPrivateKey,
					theirEphemeralPublicKey);
		}
		arraycopy(inputs, 0, hashInputs, 3, inputs.length);
		try {
			byte[] hash = hash(label, hashInputs);
			if (hash.length != SecretKey.LENGTH)
				throw new IllegalStateException();
			return new SecretKey(hash);
		} finally {
			java.util.Arrays.fill(hashInputs[0], (byte) 0);
			java.util.Arrays.fill(hashInputs[1], (byte) 0);
			java.util.Arrays.fill(hashInputs[2], (byte) 0);
		}
	}

	@Override
	public SecretKey deriveSharedSecret(String label,
			PublicKey theirStaticPublicKey, PublicKey theirEphemeralPublicKey,
			KeyPair ourStaticKeyPair, KeyPair ourEphemeralKeyPair,
			boolean alice, byte[]... inputs) throws GeneralSecurityException {
		PrivateKey ourStaticPrivateKey = ourStaticKeyPair.getPrivate();
		PrivateKey ourEphemeralPrivateKey = ourEphemeralKeyPair.getPrivate();
		byte[][] hashInputs = new byte[inputs.length + 3][];
		hashInputs[0] = performRawKeyAgreement(ourEphemeralPrivateKey,
				theirEphemeralPublicKey);
		if (alice) {
			hashInputs[1] = performRawKeyAgreement(ourStaticPrivateKey,
					theirEphemeralPublicKey);
			hashInputs[2] = performRawKeyAgreement(ourEphemeralPrivateKey,
					theirStaticPublicKey);
		} else {
			hashInputs[1] = performRawKeyAgreement(ourEphemeralPrivateKey,
					theirStaticPublicKey);
			hashInputs[2] = performRawKeyAgreement(ourStaticPrivateKey,
					theirEphemeralPublicKey);
		}
		arraycopy(inputs, 0, hashInputs, 3, inputs.length);
		try {
			byte[] hash = hash(label, hashInputs);
			if (hash.length != SecretKey.LENGTH)
				throw new IllegalStateException();
			return new SecretKey(hash);
		} finally {
			java.util.Arrays.fill(hashInputs[0], (byte) 0);
			java.util.Arrays.fill(hashInputs[1], (byte) 0);
			java.util.Arrays.fill(hashInputs[2], (byte) 0);
		}
	}

	@Override
	public byte[] sign(String label, byte[] toSign, PrivateKey privateKey)
			throws GeneralSecurityException {
		Signature sig = new EdSignature();
		sig.initSign(privateKey);
		updateSignature(sig, label, toSign);
		return sig.sign();
	}

	@Override
	public boolean verifySignature(byte[] signature, String label,
			byte[] signed, PublicKey publicKey)
			throws GeneralSecurityException {
		if (!publicKey.getKeyType().equals(KEY_TYPE_SIGNATURE))
			throw new IllegalArgumentException();
		Signature sig = new EdSignature();
		sig.initVerify(publicKey);
		updateSignature(sig, label, signed);
		return sig.verify(signature);
	}

	private void updateSignature(Signature signature, String label,
			byte[] toSign) throws GeneralSecurityException {
		byte[] labelBytes = StringUtils.toUtf8(label);
		byte[] length = new byte[INT_32_BYTES];
		ByteUtils.writeUint32(labelBytes.length, length, 0);
		signature.update(length);
		signature.update(labelBytes);
		ByteUtils.writeUint32(toSign.length, length, 0);
		signature.update(length);
		signature.update(toSign);
	}

	@Override
	public byte[] hash(String label, byte[]... inputs) {
		byte[] labelBytes = StringUtils.toUtf8(label);
		Digest digest = new Blake2bDigest(256);
		byte[] length = new byte[INT_32_BYTES];
		ByteUtils.writeUint32(labelBytes.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(labelBytes, 0, labelBytes.length);
		for (byte[] input : inputs) {
			ByteUtils.writeUint32(input.length, length, 0);
			digest.update(length, 0, length.length);
			digest.update(input, 0, input.length);
		}
		byte[] output = new byte[digest.getDigestSize()];
		digest.doFinal(output, 0);
		return output;
	}

	@Override
	public byte[] mac(String label, SecretKey macKey, byte[]... inputs) {
		byte[] labelBytes = StringUtils.toUtf8(label);
		Digest mac = new Blake2bDigest(macKey.getBytes(), 32, null, null);
		byte[] length = new byte[INT_32_BYTES];
		ByteUtils.writeUint32(labelBytes.length, length, 0);
		mac.update(length, 0, length.length);
		mac.update(labelBytes, 0, labelBytes.length);
		for (byte[] input : inputs) {
			ByteUtils.writeUint32(input.length, length, 0);
			mac.update(length, 0, length.length);
			mac.update(input, 0, input.length);
		}
		byte[] output = new byte[mac.getDigestSize()];
		mac.doFinal(output, 0);
		return output;
	}

	@Override
	public boolean verifyMac(byte[] mac, String label, SecretKey macKey,
			byte[]... inputs) {
		byte[] expected = mac(label, macKey, inputs);
		if (mac.length != expected.length) return false;
		int cmp = 0;
		for (int i = 0; i < mac.length; i++) cmp |= mac[i] ^ expected[i];
		return cmp == 0;
	}

	@Override
	public byte[] encryptWithPassword(byte[] input, char[] password,
			@Nullable KeyStrengthener keyStrengthener) {
		AuthenticatedCipher cipher = new XSalsa20Poly1305AuthenticatedCipher();
		int macBytes = cipher.getMacBytes();
		byte[] salt = new byte[PBKDF_SALT_BYTES];
		secureRandom.nextBytes(salt);
		int cost = argon2idKdf.chooseCostParameter();
		SecretKey kdfKey = argon2idKdf.deriveKey(password, salt, cost);
		SecretKey key = keyStrengthener != null
				? keyStrengthener.strengthenKey(kdfKey) : kdfKey;
		byte[] iv = new byte[STORAGE_IV_BYTES];
		secureRandom.nextBytes(iv);
		int outputLen = 1 + salt.length + INT_32_BYTES + iv.length
				+ input.length + macBytes;
		byte[] output = new byte[outputLen];
		int outputOff = 0;
		byte formatVersion = keyStrengthener == null
				? PBKDF_FORMAT_ARGON2ID : PBKDF_FORMAT_ARGON2ID_STRENGTHENED;
		output[outputOff] = formatVersion;
		outputOff++;
		arraycopy(salt, 0, output, outputOff, salt.length);
		outputOff += salt.length;
		ByteUtils.writeUint32(cost, output, outputOff);
		outputOff += INT_32_BYTES;
		arraycopy(iv, 0, output, outputOff, iv.length);
		outputOff += iv.length;
		try {
			cipher.init(true, key, iv);
			cipher.process(input, 0, input.length, output, outputOff);
			return output;
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		} finally {
			java.util.Arrays.fill(kdfKey.getBytes(), (byte) 0);
			if (key != kdfKey) {
				java.util.Arrays.fill(key.getBytes(), (byte) 0);
			}
		}
	}

	@Override
	public byte[] decryptWithPassword(byte[] input, char[] password,
			@Nullable KeyStrengthener keyStrengthener)
			throws DecryptionException {
		AuthenticatedCipher cipher = new XSalsa20Poly1305AuthenticatedCipher();
		int macBytes = cipher.getMacBytes();
		if (input.length < 1 + PBKDF_SALT_BYTES + INT_32_BYTES
				+ STORAGE_IV_BYTES + macBytes) {
			throw new DecryptionException(INVALID_CIPHERTEXT);
		}
		int inputOff = 0;
		byte formatVersion = input[inputOff];
		inputOff++;
		if (formatVersion != PBKDF_FORMAT_SCRYPT &&
				formatVersion != PBKDF_FORMAT_SCRYPT_STRENGTHENED &&
				formatVersion != PBKDF_FORMAT_ARGON2ID &&
				formatVersion != PBKDF_FORMAT_ARGON2ID_STRENGTHENED) {
			throw new DecryptionException(INVALID_CIPHERTEXT);
		}
		byte[] salt = new byte[PBKDF_SALT_BYTES];
		arraycopy(input, inputOff, salt, 0, salt.length);
		inputOff += salt.length;
		long cost = ByteUtils.readUint32(input, inputOff);
		inputOff += INT_32_BYTES;
		if (cost < 2 || cost > Integer.MAX_VALUE) {
			throw new DecryptionException(INVALID_CIPHERTEXT);
		}
		byte[] iv = new byte[STORAGE_IV_BYTES];
		arraycopy(input, inputOff, iv, 0, iv.length);
		inputOff += iv.length;
		boolean isArgon2id =
				formatVersion == PBKDF_FORMAT_ARGON2ID ||
				formatVersion == PBKDF_FORMAT_ARGON2ID_STRENGTHENED;
		PasswordBasedKdf kdf = isArgon2id ? argon2idKdf : scryptKdf;
		SecretKey kdfKey = kdf.deriveKey(password, salt, (int) cost);
		SecretKey key = kdfKey;
		if (formatVersion == PBKDF_FORMAT_SCRYPT_STRENGTHENED ||
				formatVersion == PBKDF_FORMAT_ARGON2ID_STRENGTHENED) {
			if (keyStrengthener == null || !keyStrengthener.isInitialised()) {
				java.util.Arrays.fill(kdfKey.getBytes(), (byte) 0);
				throw new DecryptionException(KEY_STRENGTHENER_ERROR);
			}
			key = keyStrengthener.strengthenKey(kdfKey);
		}
		try {
			cipher.init(false, key, iv);
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
		try {
			int inputLen = input.length - inputOff;
			byte[] output = new byte[inputLen - macBytes];
			cipher.process(input, inputOff, inputLen, output, 0);
			return output;
		} catch (GeneralSecurityException e) {
			throw new DecryptionException(INVALID_PASSWORD);
		} finally {
			java.util.Arrays.fill(kdfKey.getBytes(), (byte) 0);
			if (key != kdfKey) {
				java.util.Arrays.fill(key.getBytes(), (byte) 0);
			}
		}
	}

	@Override
	public boolean isEncryptedWithStrengthenedKey(byte[] ciphertext) {
		if (ciphertext.length == 0) return false;
		byte fv = ciphertext[0];
		return fv == PBKDF_FORMAT_SCRYPT_STRENGTHENED
				|| fv == PBKDF_FORMAT_ARGON2ID_STRENGTHENED;
	}

	@Override
	public boolean isEncryptedWithLegacyKdf(byte[] ciphertext) {
		if (ciphertext.length == 0) return false;
		byte fv = ciphertext[0];
		return fv == PBKDF_FORMAT_SCRYPT
				|| fv == PBKDF_FORMAT_SCRYPT_STRENGTHENED;
	}

	@Override
	public String asciiArmour(byte[] b, int lineLength) {
		return AsciiArmour.wrap(b, lineLength);
	}

	@Override
	public String encodeOnion(byte[] publicKey) {
		Digest digest = new SHA3Digest(256);
		byte[] label = ".onion checksum".getBytes(US_ASCII);
		digest.update(label, 0, label.length);
		digest.update(publicKey, 0, publicKey.length);
		digest.update(ONION_HS_PROTOCOL_VERSION);
		byte[] checksum = new byte[digest.getDigestSize()];
		digest.doFinal(checksum, 0);
		byte[] address = new byte[publicKey.length + ONION_CHECKSUM_BYTES + 1];
		arraycopy(publicKey, 0, address, 0, publicKey.length);
		arraycopy(checksum, 0, address, publicKey.length, ONION_CHECKSUM_BYTES);
		address[address.length - 1] = ONION_HS_PROTOCOL_VERSION;
		return Base32.encode(address).toLowerCase(Locale.US);
	}

	@Override
	public KeyPair generateHybridAgreementKeyPair() {
		KeyPair keyPair = hybridKeyAgreement.generateKeyPair();
		return keyPair;
	}

	@Override
	public KeyParser getHybridAgreementKeyParser() {
		return hybridAgreementKeyParser;
	}

	@Override
	public KeyPair generateHybridSignatureKeyPair() {
		KeyPair keyPair = hybridSignature.generateKeyPair();
		return keyPair;
	}

	@Override
	public KeyParser getHybridSignatureKeyParser() {
		return hybridSignatureKeyParser;
	}

	@Override
	public byte[] hybridSign(String label, byte[] toSign, PrivateKey privateKey)
			throws GeneralSecurityException {
		if (!privateKey.getKeyType().equals(KEY_TYPE_HYBRID_SIGNATURE)) {
			throw new IllegalArgumentException(
					"Expected hybrid signature key, got: " + privateKey.getKeyType());
		}
		byte[] labeledMessage = createLabeledMessage(label, toSign);
		byte[] signature = hybridSignature.sign(labeledMessage,
				(HybridSignaturePrivateKey) privateKey);
		return signature;
	}

	@Override
	public boolean verifyHybridSignature(byte[] signature, String label,
			byte[] signed, PublicKey publicKey) throws GeneralSecurityException {
		if (!publicKey.getKeyType().equals(KEY_TYPE_HYBRID_SIGNATURE)) {
			throw new IllegalArgumentException(
					"Expected hybrid signature key, got: " + publicKey.getKeyType());
		}
		byte[] labeledMessage = createLabeledMessage(label, signed);
		boolean valid = hybridSignature.verify(signature, labeledMessage,
				(HybridSignaturePublicKey) publicKey);
		return valid;
	}

	@Override
	public HybridEncapsulationResult hybridEncapsulate(PublicKey theirPublicKey)
			throws GeneralSecurityException {
		if (!theirPublicKey.getKeyType().equals(KEY_TYPE_HYBRID_AGREEMENT)) {
			throw new IllegalArgumentException(
					"Expected hybrid agreement key, got: " + theirPublicKey.getKeyType());
		}
		HybridKeyAgreement.HybridEncapsulation enc = hybridKeyAgreement.encapsulate(
				(HybridAgreementPublicKey) theirPublicKey);
		byte[] secret = enc.getSharedSecret().clone();
		enc.clearSecret();
		return new HybridEncapsulationResult(enc.getCiphertext(), secret);
	}

	@Override
	public SecretKey deriveHybridSharedSecret(String label,
			PublicKey theirPublicKey, KeyPair ourKeyPair, byte[] kemCiphertext,
			byte[]... inputs) throws GeneralSecurityException {
		if (!theirPublicKey.getKeyType().equals(KEY_TYPE_HYBRID_AGREEMENT)) {
			throw new IllegalArgumentException(
					"Expected hybrid agreement public key, got: " + theirPublicKey.getKeyType());
		}
		if (!ourKeyPair.getPublic().getKeyType().equals(KEY_TYPE_HYBRID_AGREEMENT)) {
			throw new IllegalArgumentException(
					"Expected hybrid agreement key pair");
		}
		SecretKey secret = hybridKeyAgreement.deriveSharedSecret(label,
				(HybridAgreementPublicKey) theirPublicKey,
				ourKeyPair, kemCiphertext, inputs);
		return secret;
	}

	@Override
	public SecretKey deriveHybridSharedSecretAsResponder(String label,
			PublicKey theirPublicKey, KeyPair ourKeyPair, byte[] kemSecret,
			byte[]... inputs) throws GeneralSecurityException {
		if (!theirPublicKey.getKeyType().equals(KEY_TYPE_HYBRID_AGREEMENT)) {
			throw new IllegalArgumentException(
					"Expected hybrid agreement public key, got: " + theirPublicKey.getKeyType());
		}
		if (!ourKeyPair.getPublic().getKeyType().equals(KEY_TYPE_HYBRID_AGREEMENT)) {
			throw new IllegalArgumentException(
					"Expected hybrid agreement key pair");
		}
		SecretKey secret = hybridKeyAgreement.deriveSharedSecretAsResponder(label,
				(HybridAgreementPublicKey) theirPublicKey,
				ourKeyPair, kemSecret, inputs);
		return secret;
	}

	private void requireHybridAgreementKey(PublicKey key) {
		if (!key.getKeyType().equals(KEY_TYPE_HYBRID_AGREEMENT)) {
			throw new IllegalArgumentException(
					"Expected hybrid agreement key, got: " + key.getKeyType());
		}
	}

	@Override
	public SecretKey deriveHybridSharedSecretFs(String label,
			PublicKey theirStaticPublicKey, PublicKey theirEphemeralPublicKey,
			KeyPair ourStaticKeyPair, KeyPair ourEphemeralKeyPair,
			byte[] kemCiphertext, byte[]... inputs)
			throws GeneralSecurityException {
		requireHybridAgreementKey(theirStaticPublicKey);
		requireHybridAgreementKey(theirEphemeralPublicKey);
		requireHybridAgreementKey(ourStaticKeyPair.getPublic());
		requireHybridAgreementKey(ourEphemeralKeyPair.getPublic());
		return hybridKeyAgreement.deriveSharedSecretFs(label,
				(HybridAgreementPublicKey) theirStaticPublicKey,
				(HybridAgreementPublicKey) theirEphemeralPublicKey,
				ourStaticKeyPair, ourEphemeralKeyPair, kemCiphertext, inputs);
	}

	@Override
	public SecretKey deriveHybridSharedSecretFsAsResponder(String label,
			PublicKey theirStaticPublicKey, PublicKey theirEphemeralPublicKey,
			KeyPair ourStaticKeyPair, KeyPair ourEphemeralKeyPair,
			byte[] kemSecret, byte[]... inputs)
			throws GeneralSecurityException {
		requireHybridAgreementKey(theirStaticPublicKey);
		requireHybridAgreementKey(theirEphemeralPublicKey);
		requireHybridAgreementKey(ourStaticKeyPair.getPublic());
		requireHybridAgreementKey(ourEphemeralKeyPair.getPublic());
		return hybridKeyAgreement.deriveSharedSecretFsAsResponder(label,
				(HybridAgreementPublicKey) theirStaticPublicKey,
				(HybridAgreementPublicKey) theirEphemeralPublicKey,
				ourStaticKeyPair, ourEphemeralKeyPair, kemSecret, inputs);
	}

	private byte[] createLabeledMessage(String label, byte[] message) {
		byte[] labelBytes = StringUtils.toUtf8(label);
		byte[] result = new byte[INT_32_BYTES + labelBytes.length + INT_32_BYTES + message.length];
		int offset = 0;
		ByteUtils.writeUint32(labelBytes.length, result, offset);
		offset += INT_32_BYTES;
		arraycopy(labelBytes, 0, result, offset, labelBytes.length);
		offset += labelBytes.length;
		ByteUtils.writeUint32(message.length, result, offset);
		offset += INT_32_BYTES;
		arraycopy(message, 0, result, offset, message.length);
		return result;
	}

}
