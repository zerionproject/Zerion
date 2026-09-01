package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.vault.wallet.btc.payjoin.PayjoinValidator;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyAnalyzer;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyEngine;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyMeta;
import com.professor.zerion.android.vault.wallet.btc.privacy.UtxoOrigin;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Assembles the final Payjoin transaction from a validated proposal. Ownership
 * of every input is decided only from wallet state; nothing the proposal or the
 * native layer claims can make an input wallet-owned. Both validation layers
 * must agree, the non-overridable privacy rules must still hold, and the final
 * transaction is analysed as it will actually be signed. Any failure produces a
 * rejection and no signable transaction.
 */
@NotNullByDefault
public final class PayjoinSender {

	public enum Reject {
		OK,
		VALIDATORS_DISAGREE,
		OUR_INPUT_MISSING,
		OWNED_SET_CHANGED,
		FROZEN_INPUT,
		SP_ISOLATION,
		CLUSTER_MERGE_STRICT,
		CHANGE_NOT_OURS
	}

	public static final class ProposalInput {
		public final String txHash;
		public final int txPos;
		public final long valueSat;
		public final long sequence;
		@Nullable
		public final byte[][] witness;
		public final boolean claimsOurs;

		public ProposalInput(String txHash, int txPos, long valueSat,
				long sequence, @Nullable byte[][] witness, boolean claimsOurs) {
			this.txHash = txHash;
			this.txPos = txPos;
			this.valueSat = valueSat;
			this.sequence = sequence;
			this.witness = witness;
			this.claimsOurs = claimsOurs;
		}

		public String outpoint() {
			return txHash + ":" + txPos;
		}
	}

	public static final class Review {
		public final boolean ok;
		public final Reject reject;
		@Nullable
		public final PayjoinFinalTx finalTx;
		public final PrivacyAnalyzer.Analysis analysis;
		public final Set<String> ownedOutpoints;
		public final Set<String> foreignOutpoints;

		Review(boolean ok, Reject reject, @Nullable PayjoinFinalTx finalTx,
				PrivacyAnalyzer.Analysis analysis, Set<String> ownedOutpoints,
				Set<String> foreignOutpoints) {
			this.ok = ok;
			this.reject = reject;
			this.finalTx = finalTx;
			this.analysis = analysis;
			this.ownedOutpoints = ownedOutpoints;
			this.foreignOutpoints = foreignOutpoints;
		}

		static Review rejected(Reject reason) {
			return new Review(false, reason, null,
					PrivacyAnalyzer.Analysis.unavailable(),
					new LinkedHashSet<>(), new LinkedHashSet<>());
		}
	}

	private PayjoinSender() {
	}

	public static Review assemble(BtcWallet.ScanResult scan,
			Set<String> authorizedOwnedOutpoints,
			List<ProposalInput> proposalInputs,
			List<PayjoinValidator.TxOut> proposalOutputs,
			int version, long locktime, long feeSat, double feeRateSatPerVb,
			String destinationAddress, long destinationAmountSat,
			@Nullable String changeAddress, long changeSat,
			List<PrivacyMeta> ownedMetas, PrivacyEngine.Policy policy,
			PayjoinValidator.Result nativeResult,
			PayjoinValidator.Result javaResult) {

		if (!javaResult.ok || !nativeResult.ok
				|| javaResult.reason != nativeResult.reason) {
			return Review.rejected(Reject.VALIDATORS_DISAGREE);
		}

		Map<String, BtcWallet.OwnedUtxo> ours = new HashMap<>();
		for (BtcWallet.OwnedUtxo u : scan.utxos) {
			ours.put(u.txHash + ":" + u.txPos, u);
		}

		List<PayjoinFinalTx.Entry> entries = new ArrayList<>();
		Set<String> ownedOutpoints = new LinkedHashSet<>();
		Set<String> foreignOutpoints = new LinkedHashSet<>();
		for (ProposalInput pin : proposalInputs) {
			String op = pin.outpoint();
			BtcWallet.OwnedUtxo u = ours.get(op);
			if (u != null) {
				ownedOutpoints.add(op);
				entries.add(PayjoinFinalTx.Entry.owned(u.txHash, u.txPos,
						u.value, pin.sequence, u.key));
			} else {
				foreignOutpoints.add(op);
				byte[][] w = pin.witness == null ? new byte[0][] : pin.witness;
				entries.add(PayjoinFinalTx.Entry.foreign(pin.txHash, pin.txPos,
						pin.valueSat, pin.sequence, w));
			}
		}

		for (String authorized : authorizedOwnedOutpoints) {
			if (!ownedOutpoints.contains(authorized)) {
				return Review.rejected(Reject.OUR_INPUT_MISSING);
			}
		}
		for (String owned : ownedOutpoints) {
			if (!authorizedOwnedOutpoints.contains(owned)) {
				return Review.rejected(Reject.OWNED_SET_CHANGED);
			}
		}

		List<PrivacyMeta> ownedSelected = new ArrayList<>();
		for (PrivacyMeta m : ownedMetas) {
			if (ownedOutpoints.contains(m.outpoint)) {
				ownedSelected.add(m);
				if (m.frozen) {
					return Review.rejected(Reject.FROZEN_INPUT);
				}
				if (m.origin == UtxoOrigin.SILENT_PAYMENT) {
					return Review.rejected(Reject.SP_ISOLATION);
				}
			}
		}
		if (policy == PrivacyEngine.Policy.STRICT
				&& PrivacyEngine.clustersIn(ownedSelected).size() > 1) {
			return Review.rejected(Reject.CLUSTER_MERGE_STRICT);
		}

		if (changeAddress != null
				&& !scan.ownedAddresses.contains(changeAddress)) {
			return Review.rejected(Reject.CHANGE_NOT_OURS);
		}

		List<BtcTx.Output> outputs = new ArrayList<>();
		for (PayjoinValidator.TxOut o : proposalOutputs) {
			outputs.add(new BtcTx.Output(o.address, o.valueSat));
		}

		PayjoinFinalTx finalTx = new PayjoinFinalTx(entries, outputs, version,
				locktime, destinationAddress, destinationAmountSat,
				changeAddress, changeSat, feeSat, feeRateSatPerVb);

		PrivacyAnalyzer.Analysis analysis = analyzeFinal(entries, ownedSelected,
				changeAddress, destinationAmountSat, scan);

		return new Review(true, Reject.OK, finalTx, analysis, ownedOutpoints,
				foreignOutpoints);
	}

	private static PrivacyAnalyzer.Analysis analyzeFinal(
			List<PayjoinFinalTx.Entry> entries, List<PrivacyMeta> ownedSelected,
			@Nullable String changeAddress, long targetSat,
			BtcWallet.ScanResult scan) {
		Map<String, PrivacyMeta> byOutpoint = new HashMap<>();
		for (PrivacyMeta m : ownedSelected) {
			byOutpoint.put(m.outpoint, m);
		}
		List<PrivacyAnalyzer.InputCoin> coins = new ArrayList<>();
		for (PayjoinFinalTx.Entry e : entries) {
			String op = e.outpoint();
			PrivacyMeta m = byOutpoint.get(op);
			if (m != null) {
				coins.add(new PrivacyAnalyzer.InputCoin(op, m.address, m.origin,
						m.clusterId, m.valueSat, false));
			} else {
				coins.add(new PrivacyAnalyzer.InputCoin(op, "", UtxoOrigin.RECEIVE,
						"pjext:" + op, e.valueSat, false));
			}
		}
		String changeCluster = null;
		if (changeAddress != null) {
			changeCluster = "addr:" + changeAddress;
		}
		boolean hasChange = changeAddress != null;
		return PrivacyAnalyzer.analyze(new PrivacyAnalyzer.AnalysisInput(coins,
				hasChange, changeCluster, false, targetSat));
	}
}
