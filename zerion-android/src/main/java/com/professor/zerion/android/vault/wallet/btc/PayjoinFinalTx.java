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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
public final class PayjoinFinalTx {

	public static final class Entry {
		public final String txHash;
		public final int txPos;
		public final long valueSat;
		public final long sequence;
		public final boolean owned;
		@Nullable
		final ECKey key;
		@Nullable
		public final byte[][] witness;

		private Entry(String txHash, int txPos, long valueSat, long sequence,
				boolean owned, @Nullable ECKey key, @Nullable byte[][] witness) {
			this.txHash = txHash;
			this.txPos = txPos;
			this.valueSat = valueSat;
			this.sequence = sequence;
			this.owned = owned;
			this.key = key;
			this.witness = witness;
		}

		public static Entry owned(String txHash, int txPos, long valueSat,
				long sequence, ECKey key) {
			return new Entry(txHash, txPos, valueSat, sequence, true, key, null);
		}

		public static Entry foreign(String txHash, int txPos, long valueSat,
				long sequence, byte[][] witness) {
			return new Entry(txHash, txPos, valueSat, sequence, false, null,
					copy(witness));
		}

		public String outpoint() {
			return txHash + ":" + txPos;
		}
	}

	public final List<Entry> inputs;
	public final List<BtcTx.Output> outputs;
	public final int version;
	public final long locktime;
	public final String destinationAddress;
	public final long destinationAmountSat;
	@Nullable
	public final String changeAddress;
	public final long changeSat;
	public final long feeSat;
	public final double feeRateSatPerVb;

	private static final byte[] FINGERPRINT_TAG = "zerion-btc-payjoin-p2wpkh-v1"
			.getBytes(StandardCharsets.US_ASCII);

	public PayjoinFinalTx(List<Entry> inputs, List<BtcTx.Output> outputs,
			int version, long locktime, String destinationAddress,
			long destinationAmountSat, @Nullable String changeAddress,
			long changeSat, long feeSat, double feeRateSatPerVb) {
		this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
		this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));
		this.version = version;
		this.locktime = locktime;
		this.destinationAddress = destinationAddress;
		this.destinationAmountSat = destinationAmountSat;
		this.changeAddress = changeAddress;
		this.changeSat = changeSat;
		this.feeSat = feeSat;
		this.feeRateSatPerVb = feeRateSatPerVb;
	}

	public Set<String> ownedOutpoints() {
		Set<String> owned = new LinkedHashSet<>();
		for (Entry e : inputs) {
			if (e.owned) {
				owned.add(e.outpoint());
			}
		}
		return owned;
	}

	public String fingerprint() {
		return fingerprint(unsigned(), witnesses());
	}

	@Nullable
	public String buildSignedHexApproved(String approvedFingerprint) {
		byte[][][] witnesses = witnesses();
		Transaction tx = unsigned();
		if (!paysWhatWasReviewed()
				|| !fingerprint(tx, witnesses).equals(approvedFingerprint)) {
			return null;
		}
		for (int i = 0; i < inputs.size(); i++) {
			Entry e = inputs.get(i);
			if (e.owned) {
				ECKey key = e.key;
				if (key == null) {
					throw new IllegalStateException("owned input without key");
				}
				Script scriptCode = ScriptBuilder.createP2PKHOutputScript(key);
				TransactionSignature sig = tx.calculateWitnessSignature(i, key,
						scriptCode, Coin.valueOf(e.valueSat),
						Transaction.SigHash.ALL, false);
				tx.getInput(i).setWitness(
						TransactionWitness.redeemP2WPKH(sig, key));
			} else {
				byte[][] w = witnesses[i];
				TransactionWitness tw = new TransactionWitness(w.length);
				for (int p = 0; p < w.length; p++) {
					tw.setPush(p, w[p]);
				}
				tx.getInput(i).setWitness(tw);
			}
		}
		return Utils.HEX.encode(tx.bitcoinSerialize());
	}

	private Transaction unsigned() {
		NetworkParameters params = BtcKeys.PARAMS;
		Transaction tx = new Transaction(params);
		tx.setVersion(version);
		tx.setLockTime(locktime);
		for (BtcTx.Output o : outputs) {
			tx.addOutput(Coin.valueOf(o.valueSat),
					Address.fromString(params, o.address));
		}
		for (Entry e : inputs) {
			TransactionOutPoint outPoint = new TransactionOutPoint(params,
					e.txPos, Sha256Hash.wrap(e.txHash));
			TransactionInput ti = new TransactionInput(params, tx, new byte[0],
					outPoint, Coin.valueOf(e.valueSat));
			ti.setSequenceNumber(e.sequence);
			tx.addInput(ti);
		}
		return tx;
	}

	private byte[][][] witnesses() {
		byte[][][] out = new byte[inputs.size()][][];
		for (int i = 0; i < inputs.size(); i++) {
			Entry e = inputs.get(i);
			if (e.owned) continue;
			byte[][] w = e.witness;
			if (w == null) {
				throw new IllegalStateException("foreign input without witness");
			}
			out[i] = copy(w);
		}
		return out;
	}

	private String fingerprint(Transaction tx, byte[][][] witnesses) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		BtcTx.writeField(b, FINGERPRINT_TAG);
		BtcTx.writeField(b, tx.bitcoinSerialize());
		for (int i = 0; i < inputs.size(); i++) {
			Entry e = inputs.get(i);
			BtcTx.writeAmount(b, e.valueSat);
			if (e.owned) {
				ECKey key = e.key;
				if (key == null) {
					throw new IllegalStateException("owned input without key");
				}
				b.write(1);
				BtcTx.writeField(b, ScriptBuilder.createP2PKHOutputScript(key)
						.getProgram());
			} else {
				b.write(0);
				byte[][] w = witnesses[i];
				BtcTx.writeAmount(b, w.length);
				for (byte[] push : w) BtcTx.writeField(b, push);
			}
		}
		b.write(Transaction.SigHash.ALL.byteValue());
		BtcTx.writeField(b, destinationAddress.getBytes(StandardCharsets.UTF_8));
		BtcTx.writeAmount(b, destinationAmountSat);
		String change = changeAddress;
		b.write(change == null ? 0 : 1);
		BtcTx.writeField(b, (change == null ? "" : change)
				.getBytes(StandardCharsets.UTF_8));
		BtcTx.writeAmount(b, changeSat);
		BtcTx.writeAmount(b, feeSat);
		BtcTx.writeField(b, String.format(Locale.ROOT, "%.3f",
				feeRateSatPerVb).getBytes(StandardCharsets.US_ASCII));
		return TlsTrust.sha256Hex(b.toByteArray());
	}

	private boolean paysWhatWasReviewed() {
		long in = 0;
		long out = 0;
		boolean destination = false;
		boolean change = changeAddress == null && changeSat == 0;
		try {
			for (Entry e : inputs) {
				if (e.valueSat <= 0) return false;
				in = Math.addExact(in, e.valueSat);
			}
			for (BtcTx.Output o : outputs) {
				if (o.valueSat <= 0) return false;
				out = Math.addExact(out, o.valueSat);
				if (o.address.equals(destinationAddress)
						&& o.valueSat == destinationAmountSat) {
					destination = true;
				}
				if (o.address.equals(changeAddress)
						&& o.valueSat == changeSat) {
					change = true;
				}
			}
		} catch (ArithmeticException overflow) {
			return false;
		}
		return feeSat >= 0 && in - out == feeSat && destination && change;
	}

	private static byte[][] copy(byte[][] witness) {
		byte[][] out = new byte[witness.length][];
		for (int i = 0; i < witness.length; i++) {
			out[i] = witness[i].clone();
		}
		return out;
	}

	static List<BtcTx.Output> outputList(BtcTx.Output... outs) {
		List<BtcTx.Output> list = new ArrayList<>();
		for (BtcTx.Output o : outs) {
			list.add(o);
		}
		return list;
	}
}
