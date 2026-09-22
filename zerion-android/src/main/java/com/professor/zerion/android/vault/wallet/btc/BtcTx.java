package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

@NotNullByDefault
public final class BtcTx {

	public static final class Input {
		public final String txHash;
		public final int txPos;
		public final long valueSat;
		public final ECKey key;

		public Input(String txHash, int txPos, long valueSat, ECKey key) {
			this.txHash = txHash;
			this.txPos = txPos;
			this.valueSat = valueSat;
			this.key = key;
		}
	}

	public static final class Output {
		public final String address;
		public final long valueSat;

		public Output(String address, long valueSat) {
			this.address = address;
			this.valueSat = valueSat;
		}
	}

	private static final long RBF_SEQUENCE = 0xfffffffdL;
	private static final java.security.SecureRandom AUX_RANDOM =
			new java.security.SecureRandom();

	private BtcTx() {
	}

	public static int estimateVBytes(int numInputs, int numOutputs) {
		return 11 + numInputs * 68 + numOutputs * 31;
	}

	/** The size of the wallet's own change output, which is always P2WPKH. */
	public static final int CHANGE_OUTPUT_VBYTES = 31;

	/**
	 * Size estimate for a transaction spending P2WPKH inputs to the given
	 * outputs, sized by the script type of each destination.
	 */
	public static int estimateVBytes(int numInputs, List<Output> outputs) {
		int size = 11 + numInputs * 68;
		for (Output o : outputs) size += outputVBytes(o.address);
		return size;
	}

	/**
	 * The virtual size of an output paying the address, by its script type:
	 * 34 for P2PKH, 32 for P2SH, 31 for P2WPKH, 43 for P2WSH and P2TR. An
	 * address whose type is not recognised is sized as the largest, so a
	 * fee is never estimated below what the destination costs.
	 */
	public static int outputVBytes(String address) {
		Script.ScriptType type = scriptTypeOf(address);
		if (type == Script.ScriptType.P2PKH) return 34;
		if (type == Script.ScriptType.P2SH) return 32;
		if (type == Script.ScriptType.P2WPKH) return 31;
		return 43;
	}

	/**
	 * The value at or below which an output to the address is dust for the
	 * network's default relay policy: 546 sat for P2PKH, 540 for P2SH, 294
	 * for P2WPKH and 330 for P2WSH and P2TR. An unrecognised type takes the
	 * highest threshold.
	 */
	public static long dustThresholdSat(String address) {
		Script.ScriptType type = scriptTypeOf(address);
		if (type == Script.ScriptType.P2WPKH) return 294L;
		if (type == Script.ScriptType.P2WSH
				|| type == Script.ScriptType.P2TR) return 330L;
		if (type == Script.ScriptType.P2SH) return 540L;
		return 546L;
	}

	@javax.annotation.Nullable
	private static Script.ScriptType scriptTypeOf(String address) {
		try {
			return Address.fromString(BtcKeys.PARAMS, address.trim())
					.getOutputScriptType();
		} catch (RuntimeException unknownType) {
			return null;
		}
	}

	public static String buildAndSign(List<Input> inputs, List<Output> outputs) {
		NetworkParameters params = BtcKeys.PARAMS;
		java.util.List<Input> ins = new java.util.ArrayList<>(inputs);
		java.util.List<Output> outs = new java.util.ArrayList<>(outputs);
		ins.sort(BtcTx::compareInputsBip69);
		outs.sort((a, b) -> compareOutputsBip69(params, a, b));
		Transaction tx = new Transaction(params);
		tx.setVersion(2);
		for (Output o : outs) {
			tx.addOutput(Coin.valueOf(o.valueSat),
					Address.fromString(params, o.address));
		}
		for (Input in : ins) {
			TransactionOutPoint outPoint = new TransactionOutPoint(params,
					in.txPos, Sha256Hash.wrap(in.txHash));
			TransactionInput ti = new TransactionInput(params, tx, new byte[0],
					outPoint, Coin.valueOf(in.valueSat));
			ti.setSequenceNumber(RBF_SEQUENCE);
			tx.addInput(ti);
		}
		for (int i = 0; i < ins.size(); i++) {
			Input in = ins.get(i);
			Script scriptCode = ScriptBuilder.createP2PKHOutputScript(in.key);
			TransactionSignature sig = tx.calculateWitnessSignature(i, in.key,
					scriptCode, Coin.valueOf(in.valueSat),
					Transaction.SigHash.ALL, false);
			tx.getInput(i).setWitness(TransactionWitness.redeemP2WPKH(sig, in.key));
		}
		return Utils.HEX.encode(tx.bitcoinSerialize());
	}

	private static int compareInputsBip69(Input a, Input b) {
		int c = compareUnsigned(Utils.HEX.decode(a.txHash),
				Utils.HEX.decode(b.txHash));
		return c != 0 ? c : Integer.compare(a.txPos, b.txPos);
	}

	private static int compareOutputsBip69(NetworkParameters params, Output a,
			Output b) {
		int c = Long.compare(a.valueSat, b.valueSat);
		if (c != 0) {
			return c;
		}
		return compareUnsigned(scriptOf(params, a.address),
				scriptOf(params, b.address));
	}

	private static byte[] scriptOf(NetworkParameters params, String address) {
		return ScriptBuilder.createOutputScript(
				Address.fromString(params, address)).getProgram();
	}

	private static int compareUnsigned(byte[] a, byte[] b) {
		int n = Math.min(a.length, b.length);
		for (int i = 0; i < n; i++) {
			int x = (a[i] & 0xff) - (b[i] & 0xff);
			if (x != 0) {
				return x;
			}
		}
		return a.length - b.length;
	}

	public static final class TaprootInput {
		public final String txHash;
		public final int txPos;
		public final long valueSat;
		public final byte[] scriptPubKey;
		public final java.math.BigInteger privKey;

		public TaprootInput(String txHash, int txPos, long valueSat,
				byte[] scriptPubKey, java.math.BigInteger privKey) {
			this.txHash = txHash;
			this.txPos = txPos;
			this.valueSat = valueSat;
			this.scriptPubKey = scriptPubKey;
			this.privKey = privKey;
		}
	}

	public static String buildAndSignTaproot(List<TaprootInput> inputs,
			List<Output> outputs) {
		NetworkParameters params = BtcKeys.PARAMS;
		List<TaprootInput> tin = new java.util.ArrayList<>(inputs);
		List<Output> outs = new java.util.ArrayList<>(outputs);
		tin.sort((a, b) -> {
			int c = compareUnsigned(Utils.HEX.decode(a.txHash),
					Utils.HEX.decode(b.txHash));
			return c != 0 ? c : Integer.compare(a.txPos, b.txPos);
		});
		outs.sort((a, b) -> compareOutputsBip69(params, a, b));
		inputs = tin;
		outputs = outs;
		Transaction tx = new Transaction(params);
		tx.setVersion(2);
		for (Output o : outputs) {
			tx.addOutput(Coin.valueOf(o.valueSat),
					Address.fromString(params, o.address));
		}
		for (TaprootInput in : inputs) {
			TransactionOutPoint outPoint = new TransactionOutPoint(params,
					in.txPos, Sha256Hash.wrap(in.txHash));
			TransactionInput ti = new TransactionInput(params, tx, new byte[0],
					outPoint, Coin.valueOf(in.valueSat));
			ti.setSequenceNumber(RBF_SEQUENCE);
			tx.addInput(ti);
		}
		java.util.List<TaprootSign.Prevout> prevouts =
				new java.util.ArrayList<>();
		for (TaprootInput in : inputs) {
			prevouts.add(new TaprootSign.Prevout(in.scriptPubKey, in.valueSat));
		}
		for (int i = 0; i < inputs.size(); i++) {
			byte[] sighash = TaprootSign.keyPathSigHash(tx, prevouts, i, 0);
			byte[] aux = new byte[32];
			AUX_RANDOM.nextBytes(aux);
			byte[] sig = TaprootSign.schnorrSign(inputs.get(i).privKey, sighash,
					aux);
			byte[] spk = inputs.get(i).scriptPubKey;
			byte[] xonly = java.util.Arrays.copyOfRange(spk, 2, 34);
			if (!TaprootSign.schnorrVerify(xonly, sighash, sig)) {
				throw new IllegalStateException("taproot signature self-check failed");
			}
			TransactionWitness w = new TransactionWitness(1);
			w.setPush(0, sig);
			tx.getInput(i).setWitness(w);
		}
		return Utils.HEX.encode(tx.bitcoinSerialize());
	}
}
