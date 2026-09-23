package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.crypto.DeterministicKey;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

/**
 * Test-only derivation helpers keyed by a mnemonic string. Production code
 * holds only a {@link BtcKeys.Account}; the string form exists here so that
 * fixtures can spell the mnemonic as a literal.
 */
final class TestKeys {

	private static final Map<String, BtcKeys.Account> ACCOUNTS =
			new HashMap<>();

	private TestKeys() {
	}

	static synchronized BtcKeys.Account account(String mnemonic, int account) {
		String key = mnemonic + "/" + account;
		BtcKeys.Account a = ACCOUNTS.get(key);
		if (a == null) {
			try {
				a = BtcKeys.Account.fromMnemonic(mnemonic.toCharArray(),
						account);
			} catch (GeneralSecurityException e) {
				throw new AssertionError(e);
			}
			ACCOUNTS.put(key, a);
		}
		return a;
	}

	static String scriptHash(String mnemonic, int account, int index) {
		return account(mnemonic, account).scriptHash(index);
	}

	static String changeScriptHash(String mnemonic, int account, int index) {
		return account(mnemonic, account).changeScriptHash(index);
	}

	static String address(String mnemonic, int account, int index) {
		return account(mnemonic, account).address(index);
	}

	static String changeAddress(String mnemonic, int account, int index) {
		return account(mnemonic, account).changeAddress(index);
	}

	static DeterministicKey receiveKey(String mnemonic, int account,
			int index) {
		return account(mnemonic, account).receiveKey(index);
	}

	static DeterministicKey changeKey(String mnemonic, int account,
			int index) {
		return account(mnemonic, account).changeKey(index);
	}
}
