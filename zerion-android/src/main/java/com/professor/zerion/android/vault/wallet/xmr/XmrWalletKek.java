package com.professor.zerion.android.vault.wallet.xmr;

import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Base64;

/**
 * Derives the Monero main keys-file password from the per-wallet password, so
 * the spend-capable {@code w.keys} file at rest is protected by a memory-hard
 * key-encryption key independent of the ZVault master key. The wallet password
 * is stretched with the approved wallet Argon2id parameters against a random
 * per-wallet salt, then a domain-separated HKDF-SHA256 subkey becomes the
 * high-entropy string handed to wallet2 for its own file encryption. The
 * background (view-only) keys file uses a separate random vault-tier credential
 * and never this derivation.
 *
 * <p>Decrypting the ZVault layer alone yields the salt but never the wallet
 * password, so the main file password cannot be reconstructed without the
 * memory-hard work over the user's secret; obtaining spend authority therefore
 * requires the wallet-password layer as well as the vault.
 */
@NotNullByDefault
public final class XmrWalletKek {

	/** Bumped only if the derivation changes; persisted per wallet. */
	public static final int VERSION = 1;

	private static final String MAIN_KEYS_INFO = "ZERION:XMR:MAIN-KEYS:v1";

	private XmrWalletKek() {
	}

	/** A fresh 32-byte random salt for a new wallet's key derivation. */
	public static byte[] newSalt() {
		return new Argon2().generateSalt();
	}

	/**
	 * Derive the wallet2 main-file password (a base64url string in a
	 * {@code char[]}) from the wallet password and its salt. The Argon2id output
	 * and the HKDF subkey are wiped before returning; the caller must wipe the
	 * returned array after use. Never derives from, or equals, the background
	 * credential.
	 */
	public static char[] deriveMainFilePassword(char[] walletPassword,
			byte[] salt) {
		Argon2 argon2 = new Argon2();
		byte[] kek = argon2.deriveKey(walletPassword, salt,
				Argon2.Argon2Params.getWalletPassword());
		try {
			byte[] mainKey = new VaultCrypto()
					.hkdfSha256(kek, null, MAIN_KEYS_INFO, 32);
			try {
				return base64UrlChars(mainKey);
			} finally {
				Argon2.clearBytes(mainKey);
			}
		} finally {
			Argon2.clearBytes(kek);
		}
	}

	private static char[] base64UrlChars(byte[] raw) {
		byte[] b64 = Base64.getUrlEncoder().withoutPadding().encode(raw);
		try {
			char[] out = new char[b64.length];
			for (int i = 0; i < b64.length; i++) {
				out[i] = (char) (b64[i] & 0x7f);
			}
			return out;
		} finally {
			Argon2.clearBytes(b64);
		}
	}
}
