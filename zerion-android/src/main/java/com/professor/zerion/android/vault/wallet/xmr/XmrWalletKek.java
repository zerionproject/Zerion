package com.professor.zerion.android.vault.wallet.xmr;

import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Base64;

@NotNullByDefault
public final class XmrWalletKek {

	public static final int VERSION = 1;

	private static final String MAIN_KEYS_INFO = "ZERION:XMR:MAIN-KEYS:v1";

	private XmrWalletKek() {
	}

	public static byte[] newSalt() {
		return new Argon2().generateSalt();
	}

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
