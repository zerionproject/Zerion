package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.ScriptBuilder;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Bitcoin key material. The mnemonic is never held as a string: an
 * {@link Account} is derived once from the mnemonic characters, the seed
 * is wiped as soon as the account-level keys exist, and every address,
 * script hash and signing key comes from those account keys. Private
 * scalars live inside the library's key objects as immutable big integers
 * for the account's lifetime, which the JVM cannot wipe; closing the
 * account drops every reference to them.
 */
@NotNullByDefault
public final class BtcKeys {

	public static final NetworkParameters PARAMS = MainNetParams.get();

	private BtcKeys() {
	}

	/** The account-level keys of one wallet, derived once. */
	public static final class Account {

		@Nullable
		private volatile DeterministicKey account;
		@Nullable
		private volatile DeterministicKey silent;
		@Nullable
		private volatile DeterministicKey receiveChain;
		@Nullable
		private volatile DeterministicKey changeChain;

		private Account(DeterministicKey account, DeterministicKey silent) {
			this.account = account;
			this.silent = silent;
			this.receiveChain = HDKeyDerivation.deriveChildKey(account,
					new ChildNumber(0, false));
			this.changeChain = HDKeyDerivation.deriveChildKey(account,
					new ChildNumber(1, false));
		}

		/**
		 * Derives the account keys from the mnemonic characters, which the
		 * caller keeps and wipes. The seed exists only inside this call.
		 */
		public static Account fromMnemonic(char[] mnemonic, int account)
				throws GeneralSecurityException {
			byte[] seed = Bip39Seed.fromMnemonic(mnemonic);
			try {
				DeterministicKey master =
						HDKeyDerivation.createMasterPrivateKey(seed);
				DeterministicKey acct = purpose(master, 84, account);
				DeterministicKey silent = purpose(master, 352, account);
				return new Account(acct, silent);
			} finally {
				Arrays.fill(seed, (byte) 0);
			}
		}

		private static DeterministicKey purpose(DeterministicKey master,
				int purpose, int account) {
			DeterministicKey k = HDKeyDerivation.deriveChildKey(master,
					new ChildNumber(purpose, true));
			k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(0, true));
			return HDKeyDerivation.deriveChildKey(k,
					new ChildNumber(account, true));
		}

		private DeterministicKey chain(boolean change) {
			DeterministicKey k = change ? changeChain : receiveChain;
			if (k == null) throw new IllegalStateException("account closed");
			return k;
		}

		private DeterministicKey silentRoot() {
			DeterministicKey k = silent;
			if (k == null) throw new IllegalStateException("account closed");
			return k;
		}

		/** Drops every key reference; the account is unusable afterwards. */
		public void close() {
			account = null;
			silent = null;
			receiveChain = null;
			changeChain = null;
		}

		public boolean isClosed() {
			return account == null;
		}

		public DeterministicKey receiveKey(int index) {
			return HDKeyDerivation.deriveChildKey(chain(false),
					new ChildNumber(index, false));
		}

		public DeterministicKey changeKey(int index) {
			return HDKeyDerivation.deriveChildKey(chain(true),
					new ChildNumber(index, false));
		}

		public String address(int index) {
			return SegwitAddress.fromKey(PARAMS, receiveKey(index)).toString();
		}

		public String changeAddress(int index) {
			return SegwitAddress.fromKey(PARAMS, changeKey(index)).toString();
		}

		public String scriptHash(int index) {
			return scriptHashOf(SegwitAddress.fromKey(PARAMS,
					receiveKey(index)));
		}

		public String changeScriptHash(int index) {
			return scriptHashOf(SegwitAddress.fromKey(PARAMS,
					changeKey(index)));
		}

		public Set<String> ownedAddresses(int receiveCount, int changeCount) {
			Set<String> out = new HashSet<>();
			for (int i = 0; i < receiveCount; i++) out.add(address(i));
			for (int i = 0; i < changeCount; i++) out.add(changeAddress(i));
			return out;
		}

		private DeterministicKey silentKey(int branch) {
			DeterministicKey k = HDKeyDerivation.deriveChildKey(silentRoot(),
					new ChildNumber(branch, true));
			return HDKeyDerivation.deriveChildKey(k, new ChildNumber(0, false));
		}

		public String silentPaymentAddress() {
			byte[] scanPub = silentKey(1).getPubKey();
			byte[] spendPub = silentKey(0).getPubKey();
			return SilentPayment.encodeAddress(scanPub, spendPub, true);
		}

		public byte[] silentScanPriv() {
			return silentKey(1).getPrivKeyBytes();
		}

		public byte[] silentSpendPub() {
			return silentKey(0).getPubKey();
		}

		public java.math.BigInteger silentSpendPriv() {
			return silentKey(0).getPrivKey();
		}
	}

	public static boolean isValidAddress(String address) {
		try {
			Address.fromString(PARAMS, address.trim());
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public static String scriptHashOfBytes(byte[] scriptPubKey) {
		byte[] hash = Sha256Hash.hash(scriptPubKey);
		byte[] reversed = new byte[hash.length];
		for (int i = 0; i < hash.length; i++) {
			reversed[i] = hash[hash.length - 1 - i];
		}
		return toHex(reversed);
	}

	static String scriptHashOf(SegwitAddress address) {
		byte[] program = ScriptBuilder.createOutputScript(address).getProgram();
		byte[] hash = Sha256Hash.hash(program);
		byte[] reversed = new byte[hash.length];
		for (int i = 0; i < hash.length; i++) {
			reversed[i] = hash[hash.length - 1 - i];
		}
		return toHex(reversed);
	}

	static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}
}
