package com.professor.zerion.android.backup;

import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;

@NotNullByDefault
final class BackupStream {

	static final byte[] MAGIC = {'Z', 'B', 'K', '1'};
	static final byte VERSION = 3;
	static final int DEFAULT_CHUNK = 1 << 20;
	static final int MIN_CHUNK = 1 << 12;
	static final int MAX_CHUNK = 1 << 22;
	static final long MAX_LENGTH = 1L << 32;
	static final int SALT_LENGTH = Argon2.DEFAULT_SALT_LENGTH;
	static final int PARAM_LENGTH = 16;
	static final int HEADER_LENGTH =
			MAGIC.length + 2 + SALT_LENGTH + PARAM_LENGTH + 4 + 8;
	static final int MAC_LENGTH = 32;
	static final int TAG_LENGTH = 16;
	private static final int NONCE_LENGTH = 12;
	private static final int KEY_LENGTH = 32;
	private static final String AES_GCM = "AES/GCM/NoPadding";
	private static final String INFO_ENC = "Zerion-Account-Backup-ZBK3-enc";
	private static final String INFO_VERIFY =
			"Zerion-Account-Backup-ZBK3-verify";

	private BackupStream() {
	}

	static final class Header {

		final byte flags;
		final byte[] salt;
		final Argon2.Argon2Params params;
		final int chunkSize;
		final long length;
		final byte[] encoded;

		private Header(byte flags, byte[] salt, Argon2.Argon2Params params,
				int chunkSize, long length, byte[] encoded) {
			this.flags = flags;
			this.salt = salt;
			this.params = params;
			this.chunkSize = chunkSize;
			this.length = length;
			this.encoded = encoded;
		}

		long chunkCount() {
			return (length + chunkSize - 1) / chunkSize;
		}
	}

	static final class Keys {

		final byte[] enc;
		final byte[] verify;

		Keys(byte[] enc, byte[] verify) {
			this.enc = enc;
			this.verify = verify;
		}

		void clear() {
			Arrays.fill(enc, (byte) 0);
			Arrays.fill(verify, (byte) 0);
		}
	}

	static Header newHeader(byte flags, byte[] salt, Argon2.Argon2Params params,
			int chunkSize, long length) {
		if (salt.length != SALT_LENGTH || chunkSize < MIN_CHUNK
				|| chunkSize > MAX_CHUNK || length < 1 || length > MAX_LENGTH) {
			throw new IllegalArgumentException("Bad stream header");
		}
		ByteBuffer b = ByteBuffer.allocate(HEADER_LENGTH);
		b.put(MAGIC);
		b.put(VERSION);
		b.put(flags);
		b.put(salt);
		b.put(params.toBytes());
		b.putInt(chunkSize);
		b.putLong(length);
		return new Header(flags, salt.clone(), params, chunkSize, length,
				b.array());
	}

	static Header parse(byte[] encoded) throws BackupException {
		if (encoded.length != HEADER_LENGTH) throw new BackupException(CORRUPT);
		ByteBuffer b = ByteBuffer.wrap(encoded);
		byte[] magic = new byte[MAGIC.length];
		b.get(magic);
		if (!Arrays.equals(magic, MAGIC) || b.get() != VERSION) {
			throw new BackupException(CORRUPT);
		}
		byte flags = b.get();
		byte[] salt = new byte[SALT_LENGTH];
		b.get(salt);
		byte[] paramBytes = new byte[PARAM_LENGTH];
		b.get(paramBytes);
		Argon2.Argon2Params params = Argon2.Argon2Params.fromBytes(paramBytes);
		if (params.memoryKb < Argon2.LOW_MEMORY_KB
				|| params.memoryKb > Argon2.DEFAULT_MEMORY_KB
				|| params.iterations < 1 || params.iterations > 10
				|| params.parallelism < 1 || params.parallelism > 4
				|| params.hashLength != Argon2.DEFAULT_HASH_LENGTH) {
			throw new BackupException(CORRUPT);
		}
		int chunkSize = b.getInt();
		long length = b.getLong();
		if (chunkSize < MIN_CHUNK || chunkSize > MAX_CHUNK || length < 1
				|| length > MAX_LENGTH) {
			throw new BackupException(CORRUPT);
		}
		return new Header(flags, salt, params, chunkSize, length,
				encoded.clone());
	}

	static Keys derive(char[] passphrase, Header header) {
		VaultCrypto crypto = new VaultCrypto();
		byte[] master =
				new Argon2().deriveKey(passphrase, header.salt, header.params);
		try {
			return new Keys(
					crypto.hkdfSha256(master, header.salt, INFO_ENC, KEY_LENGTH),
					crypto.hkdfSha256(master, header.salt, INFO_VERIFY,
							KEY_LENGTH));
		} finally {
			Argon2.clearBytes(master);
		}
	}

	static byte[] mac(Keys keys) {
		return new VaultCrypto().computePasswordVerificationMac(keys.verify);
	}

	static boolean verify(Keys keys, byte[] mac) {
		return MessageDigest.isEqual(mac(keys), mac);
	}

	static void seal(InputStream plaintext, long length, char[] passphrase,
			byte flags, Argon2.Argon2Params params, int chunkSize,
			OutputStream out) throws IOException {
		Header header = newHeader(flags, new Argon2().generateSalt(), params,
				chunkSize, length);
		Keys keys = derive(passphrase, header);
		try {
			out.write(header.encoded);
			out.write(mac(keys));
			writeChunks(plaintext, header, keys.enc, out);
		} finally {
			keys.clear();
		}
	}

	static void writeChunks(InputStream plaintext, Header header,
			byte[] encKey, OutputStream out) throws IOException {
		byte[] plain = new byte[header.chunkSize];
		byte[] sealed = new byte[header.chunkSize + TAG_LENGTH];
		try {
			Cipher cipher = Cipher.getInstance(AES_GCM);
			SecretKeySpec key = new SecretKeySpec(encKey, "AES");
			long remaining = header.length;
			long index = 0;
			while (remaining > 0) {
				int n = (int) Math.min(header.chunkSize, remaining);
				boolean last = n == remaining;
				if (!readFully(plaintext, plain, n)) {
					throw new IOException("Source shorter than declared");
				}
				init(cipher, Cipher.ENCRYPT_MODE, key, header, index, last);
				int written = cipher.doFinal(plain, 0, n, sealed, 0);
				if (written != n + TAG_LENGTH) {
					throw new IOException("Short encryption");
				}
				out.write(sealed, 0, written);
				remaining -= n;
				index++;
			}
			if (plaintext.read() != -1) {
				throw new IOException("Source longer than declared");
			}
		} catch (GeneralSecurityException e) {
			throw new IOException(e);
		} finally {
			Arrays.fill(plain, (byte) 0);
		}
	}

	static void readChunks(InputStream in, Header header, byte[] encKey,
			OutputStream sink) throws IOException, BackupException {
		byte[] sealed = new byte[header.chunkSize + TAG_LENGTH];
		byte[] plain = new byte[header.chunkSize];
		try {
			Cipher cipher;
			try {
				cipher = Cipher.getInstance(AES_GCM);
			} catch (GeneralSecurityException e) {
				throw new IOException(e);
			}
			SecretKeySpec key = new SecretKeySpec(encKey, "AES");
			long remaining = header.length;
			long index = 0;
			while (remaining > 0) {
				int n = (int) Math.min(header.chunkSize, remaining);
				boolean last = n == remaining;
				if (!readFully(in, sealed, n + TAG_LENGTH)) {
					throw new BackupException(CORRUPT);
				}
				int opened;
				try {
					init(cipher, Cipher.DECRYPT_MODE, key, header, index, last);
					opened = cipher.doFinal(sealed, 0, n + TAG_LENGTH, plain, 0);
				} catch (GeneralSecurityException e) {
					throw new BackupException(CORRUPT);
				}
				if (opened != n) throw new BackupException(CORRUPT);
				sink.write(plain, 0, n);
				remaining -= n;
				index++;
			}
			if (in.read() != -1) throw new BackupException(CORRUPT);
		} finally {
			Arrays.fill(plain, (byte) 0);
		}
	}

	private static void init(Cipher cipher, int mode, SecretKeySpec key,
			Header header, long index, boolean last)
			throws GeneralSecurityException {
		byte[] nonce = new byte[NONCE_LENGTH];
		ByteBuffer.wrap(nonce).putLong(index);
		nonce[NONCE_LENGTH - 1] = (byte) (last ? 1 : 0);
		byte[] aad = new byte[header.encoded.length + 9];
		System.arraycopy(header.encoded, 0, aad, 0, header.encoded.length);
		ByteBuffer.wrap(aad, header.encoded.length, 9).putLong(index)
				.put((byte) (last ? 1 : 0));
		cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH * 8, nonce));
		cipher.updateAAD(aad);
	}

	static boolean readFully(InputStream in, byte[] buf, int len)
			throws IOException {
		int off = 0;
		while (off < len) {
			int r = in.read(buf, off, len - off);
			if (r < 0) return false;
			off += r;
		}
		return true;
	}
}
