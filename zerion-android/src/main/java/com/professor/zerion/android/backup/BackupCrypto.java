package com.professor.zerion.android.backup;

import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;
import static com.professor.zerion.android.backup.BackupException.Reason.NOT_A_BACKUP;
import static com.professor.zerion.android.backup.BackupException.Reason.UNSUPPORTED_VERSION;
import static com.professor.zerion.android.backup.BackupException.Reason.WRONG_PASSPHRASE;

@NotNullByDefault
public class BackupCrypto {

	public static final byte FLAG_VAULT_INCLUDED = 0x01;

	private static final byte[] MAGIC = {'Z', 'B', 'K', '1'};
	private static final byte VERSION_1 = 1;
	private static final byte VERSION_2 = 2;
	private static final int SALT_LENGTH = Argon2.DEFAULT_SALT_LENGTH;
	private static final int PARAM_LENGTH = 16;
	private static final int MAC_LENGTH = 32;
	private static final int KEY_LENGTH = 32;
	private static final int HEADER_LENGTH =
			MAGIC.length + 2 + SALT_LENGTH + PARAM_LENGTH;
	private static final String AES_GCM = "AES/GCM/NoPadding";
	private static final int NONCE_LENGTH = 12;
	private static final int TAG_LENGTH = 16;
	private static final int SEALED_PREFIX_LENGTH = 4 + NONCE_LENGTH;
	static final int OVERHEAD = HEADER_LENGTH + MAC_LENGTH + 4
			+ SEALED_PREFIX_LENGTH + TAG_LENGTH;

	private static final byte[] AAD_BASE =
			"Zerion-Account-Backup-ZBK1".getBytes(StandardCharsets.UTF_8);
	private static final String INFO_ENC = "Zerion-Account-Backup-ZBK2-enc";
	private static final String INFO_VERIFY =
			"Zerion-Account-Backup-ZBK2-verify";

	private static byte[] aadV1(byte version, byte flags) {
		byte[] a = new byte[AAD_BASE.length + 2];
		System.arraycopy(AAD_BASE, 0, a, 0, AAD_BASE.length);
		a[AAD_BASE.length] = version;
		a[AAD_BASE.length + 1] = flags;
		return a;
	}

	private final Argon2 argon2 = new Argon2();
	private final VaultCrypto crypto = new VaultCrypto();
	private final SecureRandom random = new SecureRandom();

	public byte[] seal(byte[] bundle, char[] passphrase, byte flags) {
		return seal(bundle, passphrase, flags,
				Argon2.Argon2Params.getDefault());
	}

	public byte[] seal(byte[] bundle, char[] passphrase, byte flags,
			Argon2.Argon2Params params) {
		byte[] salt = argon2.generateSalt();
		byte[] paramBytes = params.toBytes();
		byte[] header = buildHeader(VERSION_2, flags, salt, paramBytes);
		byte[] master = argon2.deriveKey(passphrase, salt, params);
		byte[] encKey = crypto.hkdfSha256(master, salt, INFO_ENC, KEY_LENGTH);
		byte[] verifyKey =
				crypto.hkdfSha256(master, salt, INFO_VERIFY, KEY_LENGTH);
		try {
			byte[] mac = crypto.computePasswordVerificationMac(verifyKey);
			long sealedLength =
					(long) SEALED_PREFIX_LENGTH + bundle.length + TAG_LENGTH;
			long total = (long) header.length + mac.length + 4 + sealedLength;
			if (total > Integer.MAX_VALUE - 8) {
				throw new IllegalArgumentException("Backup too large");
			}
			byte[] out = new byte[(int) total];
			byte[] nonce = new byte[NONCE_LENGTH];
			random.nextBytes(nonce);
			ByteBuffer buf = ByteBuffer.wrap(out);
			buf.put(header);
			buf.put(mac);
			buf.putInt((int) sealedLength);
			buf.putInt(NONCE_LENGTH);
			buf.put(nonce);
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"),
					new GCMParameterSpec(TAG_LENGTH * 8, nonce));
			cipher.updateAAD(header);
			int written = cipher.doFinal(bundle, 0, bundle.length, out,
					buf.position());
			if (written != bundle.length + TAG_LENGTH) {
				throw new IllegalStateException("Short encryption");
			}
			return out;
		} catch (GeneralSecurityException e) {
			throw new RuntimeException("Encryption failed", e);
		} finally {
			Argon2.clearBytes(master);
			Argon2.clearBytes(encKey);
			Argon2.clearBytes(verifyKey);
		}
	}

	private byte[] buildHeader(byte version, byte flags, byte[] salt,
			byte[] paramBytes) {
		ByteBuffer h = ByteBuffer.allocate(HEADER_LENGTH);
		h.put(MAGIC);
		h.put(version);
		h.put(flags);
		h.put(salt);
		h.put(paramBytes);
		return h.array();
	}

	public Opened open(byte[] file, char[] passphrase) throws BackupException {
		byte version;
		byte flags;
		byte[] salt = new byte[SALT_LENGTH];
		byte[] paramBytes = new byte[PARAM_LENGTH];
		Argon2.Argon2Params params;
		byte[] mac = new byte[MAC_LENGTH];
		int sealedOffset;
		int sealedLen;
		byte[] header;
		try {
			ByteBuffer buf = ByteBuffer.wrap(file);
			byte[] magic = new byte[MAGIC.length];
			buf.get(magic);
			if (!Arrays.equals(magic, MAGIC)) {
				throw new BackupException(NOT_A_BACKUP);
			}
			version = buf.get();
			if (version != VERSION_1 && version != VERSION_2) {
				throw new BackupException(UNSUPPORTED_VERSION);
			}
			flags = buf.get();
			buf.get(salt);
			buf.get(paramBytes);
			params = Argon2.Argon2Params.fromBytes(paramBytes);
			if (params.memoryKb < Argon2.LOW_MEMORY_KB
					|| params.memoryKb > Argon2.DEFAULT_MEMORY_KB
					|| params.iterations < 1 || params.iterations > 10
					|| params.parallelism < 1 || params.parallelism > 4
					|| params.hashLength != Argon2.DEFAULT_HASH_LENGTH) {
				throw new BackupException(CORRUPT);
			}
			buf.get(mac);
			sealedLen = buf.getInt();
			if (sealedLen < 0 || sealedLen != buf.remaining()) {
				throw new BackupException(CORRUPT);
			}
			sealedOffset = buf.position();
			header = buildHeader(version, flags, salt, paramBytes);
		} catch (BufferUnderflowException e) {
			throw new BackupException(CORRUPT);
		}

		byte[] master = argon2.deriveKey(passphrase, salt, params);
		byte[] encKey = null;
		byte[] verifyKey = null;
		try {
			byte[] macKey;
			byte[] decKey;
			byte[] aad;
			if (version == VERSION_2) {
				encKey = crypto.hkdfSha256(master, salt, INFO_ENC, KEY_LENGTH);
				verifyKey =
						crypto.hkdfSha256(master, salt, INFO_VERIFY, KEY_LENGTH);
				macKey = verifyKey;
				decKey = encKey;
				aad = header;
			} else {
				macKey = master;
				decKey = master;
				aad = aadV1(VERSION_1, flags);
			}
			if (!crypto.verifyPasswordMac(macKey, mac)) {
				throw new BackupException(WRONG_PASSPHRASE);
			}
			try {
				byte[] bundle =
						decrypt(file, sealedOffset, sealedLen, decKey, aad);
				return new Opened(flags, bundle);
			} catch (RuntimeException | GeneralSecurityException e) {
				throw new BackupException(CORRUPT);
			}
		} finally {
			Argon2.clearBytes(master);
			if (encKey != null) Argon2.clearBytes(encKey);
			if (verifyKey != null) Argon2.clearBytes(verifyKey);
		}
	}

	private static byte[] decrypt(byte[] file, int offset, int length,
			byte[] key, byte[] aad) throws GeneralSecurityException {
		if (length < SEALED_PREFIX_LENGTH) {
			throw new IllegalArgumentException("Invalid encrypted data");
		}
		int nonceLength = ByteBuffer.wrap(file, offset, 4).getInt();
		if (nonceLength != NONCE_LENGTH) {
			throw new IllegalArgumentException("Invalid nonce length");
		}
		Cipher cipher = Cipher.getInstance(AES_GCM);
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
				new GCMParameterSpec(TAG_LENGTH * 8, file, offset + 4,
						NONCE_LENGTH));
		cipher.updateAAD(aad);
		return cipher.doFinal(file, offset + SEALED_PREFIX_LENGTH,
				length - SEALED_PREFIX_LENGTH);
	}

	public static class Opened {
		public final byte flags;
		public final byte[] bundle;

		Opened(byte flags, byte[] bundle) {
			this.flags = flags;
			this.bundle = bundle;
		}

		public boolean isVaultIncluded() {
			return (flags & FLAG_VAULT_INCLUDED) != 0;
		}
	}
}
