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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Canonical identity of the transaction that a Payjoin exchange will actually
 * broadcast. Wallet-owned inputs are signed here with a P2WPKH BIP143 witness;
 * receiver-contributed inputs keep the witnesses supplied in the proposal and
 * are never signed by this wallet. The fingerprint binds every field that a
 * reviewer authenticates, so any later mutation is detectable.
 */
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
					witness);
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

	public PayjoinFinalTx(List<Entry> inputs, List<BtcTx.Output> outputs,
			int version, long locktime, String destinationAddress,
			long destinationAmountSat, @Nullable String changeAddress,
			long changeSat, long feeSat, double feeRateSatPerVb) {
		this.inputs = inputs;
		this.outputs = outputs;
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

	/**
	 * Stable identity over every field a reviewer authenticates: version,
	 * locktime, destination, amount, change, absolute fee, feerate, then each
	 * input outpoint with its sequence and ownership, then each output.
	 */
	public String fingerprint() {
		StringBuilder sb = new StringBuilder();
		sb.append("v=").append(version).append(';');
		sb.append("lt=").append(locktime).append(';');
		sb.append("dest=").append(destinationAddress).append(';');
		sb.append("amt=").append(destinationAmountSat).append(';');
		sb.append("chg=").append(changeAddress == null ? "" : changeAddress)
				.append('/').append(changeSat).append(';');
		sb.append("fee=").append(feeSat).append(';');
		sb.append("rate=").append(String.format(java.util.Locale.ROOT, "%.3f",
				feeRateSatPerVb)).append(';');
		sb.append("in=");
		for (Entry e : inputs) {
			sb.append(e.outpoint()).append('|').append(e.sequence).append('|')
					.append(e.owned ? 'o' : 'f').append(',');
		}
		sb.append(";out=");
		for (BtcTx.Output o : outputs) {
			sb.append(o.address).append('|').append(o.valueSat).append(',');
		}
		return sha256Hex(sb.toString());
	}

	/**
	 * Builds the network transaction, signing only wallet-owned inputs. A
	 * request to sign an input without a wallet key is impossible: foreign
	 * entries carry no key and only receive their proposal witness.
	 */
	public String buildSignedHex() {
		NetworkParameters params = BtcKeys.PARAMS;
		Transaction tx = new Transaction(params);
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
				byte[][] w = e.witness;
				if (w == null) {
					throw new IllegalStateException("foreign input without witness");
				}
				TransactionWitness tw = new TransactionWitness(w.length);
				for (int p = 0; p < w.length; p++) {
					tw.setPush(p, w[p]);
				}
				tx.getInput(i).setWitness(tw);
			}
		}
		return Utils.HEX.encode(tx.bitcoinSerialize());
	}

	private static String sha256Hex(String data) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] h = md.digest(data.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(h.length * 2);
			for (byte b : h) {
				sb.append(Character.forDigit((b >> 4) & 0xf, 16));
				sb.append(Character.forDigit(b & 0xf, 16));
			}
			return sb.toString();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	static List<BtcTx.Output> outputList(BtcTx.Output... outs) {
		List<BtcTx.Output> list = new ArrayList<>();
		for (BtcTx.Output o : outs) {
			list.add(o);
		}
		return list;
	}
}
