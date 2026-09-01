package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.SegwitAddress;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.ScriptBuilder;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@NotNullByDefault
public final class BtcKeys {

	public static final NetworkParameters PARAMS = MainNetParams.get();

	private BtcKeys() {
	}

	private static DeterministicKey deriveKey(String mnemonic, int account,
			int change, int index) {
		List<String> words = Arrays.asList(mnemonic.trim().split("\\s+"));
		byte[] seed = MnemonicCode.toSeed(words, "");
		DeterministicKey k = HDKeyDerivation.createMasterPrivateKey(seed);
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(84, true));
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(0, true));
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(account, true));
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(change, false));
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(index, false));
		return k;
	}

	public static DeterministicKey receiveKey(String mnemonic, int account,
			int index) {
		return deriveKey(mnemonic, account, 0, index);
	}

	public static DeterministicKey changeKey(String mnemonic, int account,
			int index) {
		return deriveKey(mnemonic, account, 1, index);
	}

	public static String address(String mnemonic, int account, int index) {
		return SegwitAddress.fromKey(PARAMS,
				receiveKey(mnemonic, account, index)).toString();
	}

	public static String changeAddress(String mnemonic, int account, int index) {
		return SegwitAddress.fromKey(PARAMS,
				changeKey(mnemonic, account, index)).toString();
	}

	public static String scriptHash(String mnemonic, int account, int index) {
		return scriptHashOf(SegwitAddress.fromKey(PARAMS,
				receiveKey(mnemonic, account, index)));
	}

	public static String changeScriptHash(String mnemonic, int account,
			int index) {
		return scriptHashOf(SegwitAddress.fromKey(PARAMS,
				changeKey(mnemonic, account, index)));
	}

	private static DeterministicKey accountKey(byte[] seed, int account) {
		DeterministicKey k = HDKeyDerivation.createMasterPrivateKey(seed);
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(84, true));
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(0, true));
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(account, true));
		return k;
	}

	public static Set<String> ownedAddresses(String mnemonic, int account,
			int receiveCount, int changeCount) {
		List<String> words = Arrays.asList(mnemonic.trim().split("\\s+"));
		byte[] seed = MnemonicCode.toSeed(words, "");
		DeterministicKey acct = accountKey(seed, account);
		DeterministicKey recv =
				HDKeyDerivation.deriveChildKey(acct, new ChildNumber(0, false));
		DeterministicKey chg =
				HDKeyDerivation.deriveChildKey(acct, new ChildNumber(1, false));
		Set<String> out = new HashSet<>();
		for (int i = 0; i < receiveCount; i++) {
			out.add(SegwitAddress.fromKey(PARAMS,
					HDKeyDerivation.deriveChildKey(recv, new ChildNumber(i, false)))
					.toString());
		}
		for (int i = 0; i < changeCount; i++) {
			out.add(SegwitAddress.fromKey(PARAMS,
					HDKeyDerivation.deriveChildKey(chg, new ChildNumber(i, false)))
					.toString());
		}
		return out;
	}

	private static DeterministicKey silentKey(String mnemonic, int account,
			int branch) {
		List<String> words = Arrays.asList(mnemonic.trim().split("\\s+"));
		byte[] seed = MnemonicCode.toSeed(words, "");
		DeterministicKey k = HDKeyDerivation.createMasterPrivateKey(seed);
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(352, true));
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(0, true));
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(account, true));
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(branch, true));
		k = HDKeyDerivation.deriveChildKey(k, new ChildNumber(0, false));
		return k;
	}

	public static String silentPaymentAddress(String mnemonic, int account) {
		byte[] scanPub = silentKey(mnemonic, account, 1).getPubKey();
		byte[] spendPub = silentKey(mnemonic, account, 0).getPubKey();
		return SilentPayment.encodeAddress(scanPub, spendPub, true);
	}

	public static byte[] silentScanPriv(String mnemonic, int account) {
		return silentKey(mnemonic, account, 1).getPrivKeyBytes();
	}

	public static byte[] silentSpendPub(String mnemonic, int account) {
		return silentKey(mnemonic, account, 0).getPubKey();
	}

	public static java.math.BigInteger silentSpendPriv(String mnemonic,
			int account) {
		return silentKey(mnemonic, account, 0).getPrivKey();
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

	private static String scriptHashOf(SegwitAddress address) {
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
