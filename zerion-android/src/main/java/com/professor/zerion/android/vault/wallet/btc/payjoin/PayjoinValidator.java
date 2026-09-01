package com.professor.zerion.android.vault.wallet.btc.payjoin;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Independent, local validation of a Payjoin counterparty proposal before the
 * wallet signs. The counterparty and any relay are treated as untrusted. Any
 * uncertainty fails closed. This class performs no I/O.
 */
@NotNullByDefault
public final class PayjoinValidator {

	private PayjoinValidator() {
	}

	public enum Reason {
		OK,
		MALFORMED,
		OUR_INPUT_MISSING,
		RECIPIENT_MISSING,
		RECIPIENT_REDUCED,
		CHANGE_MISSING,
		CHANGE_REDUCED_TOO_MUCH,
		UNEXPECTED_OUTPUT,
		UNSUPPORTED_SCRIPT,
		WRONG_NETWORK,
		FEE_TOO_HIGH,
		FEERATE_OUT_OF_BOUNDS,
		BAD_VERSION_OR_LOCKTIME
	}

	@NotNullByDefault
	public static final class TxOut {
		public final String address;
		public final long valueSat;
		public final String scriptType;
		public final boolean mainnet;

		public TxOut(String address, long valueSat, String scriptType,
				boolean mainnet) {
			this.address = address;
			this.valueSat = valueSat;
			this.scriptType = scriptType;
			this.mainnet = mainnet;
		}
	}

	@NotNullByDefault
	public static final class OriginalTx {
		public final List<String> ourInputOutpoints;
		public final String recipientAddress;
		public final long recipientAmountSat;
		@Nullable
		public final String changeAddress;
		public final long changeSat;

		public OriginalTx(List<String> ourInputOutpoints,
				String recipientAddress, long recipientAmountSat,
				@Nullable String changeAddress, long changeSat) {
			this.ourInputOutpoints = ourInputOutpoints;
			this.recipientAddress = recipientAddress;
			this.recipientAmountSat = recipientAmountSat;
			this.changeAddress = changeAddress;
			this.changeSat = changeSat;
		}
	}

	@NotNullByDefault
	public static final class ProposedTx {
		public final List<String> inputOutpoints;
		public final List<TxOut> outputs;
		public final long totalInputValueSat;
		public final long vsize;
		public final int version;
		public final long locktime;

		public ProposedTx(List<String> inputOutpoints, List<TxOut> outputs,
				long totalInputValueSat, long vsize, int version,
				long locktime) {
			this.inputOutpoints = inputOutpoints;
			this.outputs = outputs;
			this.totalInputValueSat = totalInputValueSat;
			this.vsize = vsize;
			this.version = version;
			this.locktime = locktime;
		}
	}

	@NotNullByDefault
	public static final class Policy {
		public final long maxAbsoluteFeeSat;
		public final double minFeeRate;
		public final double maxFeeRate;
		public final long maxAdditionalFeeContributionSat;
		public final boolean allowExtraOutputs;

		public Policy(long maxAbsoluteFeeSat, double minFeeRate,
				double maxFeeRate, long maxAdditionalFeeContributionSat,
				boolean allowExtraOutputs) {
			this.maxAbsoluteFeeSat = maxAbsoluteFeeSat;
			this.minFeeRate = minFeeRate;
			this.maxFeeRate = maxFeeRate;
			this.maxAdditionalFeeContributionSat =
					maxAdditionalFeeContributionSat;
			this.allowExtraOutputs = allowExtraOutputs;
		}
	}

	@NotNullByDefault
	public static final class Result {
		public final boolean ok;
		public final Reason reason;
		public final long feeSat;
		public final long ourChangeSat;

		Result(boolean ok, Reason reason, long feeSat, long ourChangeSat) {
			this.ok = ok;
			this.reason = reason;
			this.feeSat = feeSat;
			this.ourChangeSat = ourChangeSat;
		}
	}

	private static Result reject(Reason r) {
		return new Result(false, r, 0, 0);
	}

	public static Result validate(OriginalTx orig, ProposedTx prop,
			Policy pol) {
		try {
			if (prop.inputOutpoints == null || prop.outputs == null
					|| prop.outputs.isEmpty() || orig.ourInputOutpoints == null
					|| orig.ourInputOutpoints.isEmpty()) {
				return reject(Reason.MALFORMED);
			}
			if (prop.version != 1 && prop.version != 2) {
				return reject(Reason.BAD_VERSION_OR_LOCKTIME);
			}
			if (prop.locktime < 0 || prop.locktime > 0xFFFFFFFFL) {
				return reject(Reason.BAD_VERSION_OR_LOCKTIME);
			}

			Set<String> proposedInputs = new HashSet<>(prop.inputOutpoints);
			for (String op : orig.ourInputOutpoints) {
				if (!proposedInputs.contains(op)) {
					return reject(Reason.OUR_INPUT_MISSING);
				}
			}

			for (TxOut o : prop.outputs) {
				if (!o.mainnet) {
					return reject(Reason.WRONG_NETWORK);
				}
				if (!isSupportedScript(o.scriptType)) {
					return reject(Reason.UNSUPPORTED_SCRIPT);
				}
			}

			TxOut recipient = null;
			TxOut change = null;
			List<TxOut> extras = new ArrayList<>();
			boolean recipientMatched = false;
			boolean changeMatched = false;
			for (TxOut o : prop.outputs) {
				if (!recipientMatched
						&& o.address.equals(orig.recipientAddress)) {
					recipient = o;
					recipientMatched = true;
				} else if (orig.changeAddress != null && !changeMatched
						&& o.address.equals(orig.changeAddress)) {
					change = o;
					changeMatched = true;
				} else {
					extras.add(o);
				}
			}

			if (recipient == null) {
				return reject(Reason.RECIPIENT_MISSING);
			}
			if (recipient.valueSat < orig.recipientAmountSat) {
				return reject(Reason.RECIPIENT_REDUCED);
			}

			long ourChangeSat = 0;
			if (orig.changeAddress != null && orig.changeSat > 0) {
				if (change == null) {
					return reject(Reason.CHANGE_MISSING);
				}
				ourChangeSat = change.valueSat;
				if (ourChangeSat < orig.changeSat
						- pol.maxAdditionalFeeContributionSat) {
					return reject(Reason.CHANGE_REDUCED_TOO_MUCH);
				}
			}

			if (!extras.isEmpty() && !pol.allowExtraOutputs) {
				return reject(Reason.UNEXPECTED_OUTPUT);
			}

			long outSum = 0;
			for (TxOut o : prop.outputs) {
				outSum += o.valueSat;
			}
			long fee = prop.totalInputValueSat - outSum;
			if (fee < 0 || fee > pol.maxAbsoluteFeeSat) {
				return reject(Reason.FEE_TOO_HIGH);
			}
			if (prop.vsize <= 0) {
				return reject(Reason.MALFORMED);
			}
			double feeRate = (double) fee / (double) prop.vsize;
			if (feeRate < pol.minFeeRate || feeRate > pol.maxFeeRate) {
				return reject(Reason.FEERATE_OUT_OF_BOUNDS);
			}

			return new Result(true, Reason.OK, fee, ourChangeSat);
		} catch (Throwable t) {
			return reject(Reason.MALFORMED);
		}
	}

	private static boolean isSupportedScript(String scriptType) {
		return "p2wpkh".equals(scriptType) || "p2tr".equals(scriptType);
	}
}
