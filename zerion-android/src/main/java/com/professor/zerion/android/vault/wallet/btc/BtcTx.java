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

	private BtcTx() {
	}

	public static int estimateVBytes(int numInputs, int numOutputs) {
		return 11 + numInputs * 68 + numOutputs * 31;
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
			byte[] sig = TaprootSign.schnorrSign(inputs.get(i).privKey, sighash,
					new byte[32]);
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
