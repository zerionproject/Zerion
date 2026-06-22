package com.professor.zerion.android.backup;

import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;
import static com.professor.zerion.android.backup.BackupException.Reason.NOT_A_BACKUP;
import static com.professor.zerion.android.backup.BackupException.Reason.UNSUPPORTED_VERSION;
import static com.professor.zerion.android.backup.BackupException.Reason.WRONG_PASSPHRASE;

@NotNullByDefault
public class BackupCrypto {

	public static final byte FLAG_VAULT_INCLUDED = 0x01;

	private static final byte[] MAGIC = {'Z', 'B', 'K', '1'};
	private static final byte VERSION = 1;
	private static final int PARAM_LENGTH = 16;
	private static final int MAC_LENGTH = 32;
	private static final byte[] AAD_BASE =
			"Zerion-Account-Backup-ZBK1".getBytes(StandardCharsets.UTF_8);

	private static byte[] aad(byte version, byte flags) {
		byte[] a = new byte[AAD_BASE.length + 2];
		System.arraycopy(AAD_BASE, 0, a, 0, AAD_BASE.length);
		a[AAD_BASE.length] = version;
		a[AAD_BASE.length + 1] = flags;
		return a;
	}

	private final Argon2 argon2 = new Argon2();
	private final VaultCrypto crypto = new VaultCrypto();

	public byte[] seal(byte[] bundle, char[] passphrase, byte flags) {
		return seal(bundle, passphrase, flags,
				Argon2.Argon2Params.getDefault());
	}

	public byte[] seal(byte[] bundle, char[] passphrase, byte flags,
			Argon2.Argon2Params params) {
		byte[] salt = argon2.generateSalt();
		byte[] key = argon2.deriveKey(passphrase, salt, params);
		try {
			byte[] mac = crypto.computePasswordVerificationMac(key);
			byte[] sealed =
					crypto.encrypt(bundle, key, aad(VERSION, flags)).toBytes();
			byte[] paramBytes = params.toBytes();
			ByteBuffer buf = ByteBuffer.allocate(MAGIC.length + 2
					+ salt.length + paramBytes.length + mac.length + 4
					+ sealed.length);
			buf.put(MAGIC);
			buf.put(VERSION);
			buf.put(flags);
			buf.put(salt);
			buf.put(paramBytes);
			buf.put(mac);
			buf.putInt(sealed.length);
			buf.put(sealed);
			return buf.array();
		} finally {
			Argon2.clearBytes(key);
		}
	}

	public Opened open(byte[] file, char[] passphrase) throws BackupException {
		byte flags;
		byte[] salt = new byte[Argon2.DEFAULT_SALT_LENGTH];
		Argon2.Argon2Params params;
		byte[] mac = new byte[MAC_LENGTH];
		byte[] sealed;
		try {
			ByteBuffer buf = ByteBuffer.wrap(file);
			byte[] magic = new byte[MAGIC.length];
			buf.get(magic);
			if (!Arrays.equals(magic, MAGIC)) {
				throw new BackupException(NOT_A_BACKUP);
			}
			if (buf.get() != VERSION) {
				throw new BackupException(UNSUPPORTED_VERSION);
			}
			flags = buf.get();
			buf.get(salt);
			byte[] paramBytes = new byte[PARAM_LENGTH];
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
			int sealedLen = buf.getInt();
			if (sealedLen < 0 || sealedLen > buf.remaining()) {
				throw new BackupException(CORRUPT);
			}
			sealed = new byte[sealedLen];
			buf.get(sealed);
		} catch (BufferUnderflowException e) {
			throw new BackupException(CORRUPT);
		}

		byte[] key = argon2.deriveKey(passphrase, salt, params);
		try {
			if (!crypto.verifyPasswordMac(key, mac)) {
				throw new BackupException(WRONG_PASSPHRASE);
			}
			try {
				byte[] bundle = crypto.decrypt(
						VaultCrypto.EncryptedData.fromBytes(sealed), key,
						aad(VERSION, flags));
				return new Opened(flags, bundle);
			} catch (RuntimeException e) {
				throw new BackupException(CORRUPT);
			}
		} finally {
			Argon2.clearBytes(key);
		}
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
