package com.professor.zerion.android.vault.wallet.btc.payjoin;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Orchestrates one Payjoin exchange: send the original proposal over Tor,
 * receive the counterparty response, and validate it locally. It never signs
 * or broadcasts; only a VALIDATED outcome permits the wallet to build the final
 * transaction, which is then authenticated and signed separately. A received
 * proposal is single-use, protecting against replayed or duplicated proposals.
 * Every failure is explicit; a failed Payjoin never becomes a normal payment.
 */
@NotNullByDefault
public final class PayjoinSession {

	public enum Status {
		VALIDATED,
		REJECTED,
		FAILED,
		REPLAYED
	}

	@NotNullByDefault
	public interface ProposalParser {
		PayjoinValidator.ProposedTx parse(byte[] raw) throws Exception;
	}

	@NotNullByDefault
	public static final class Outcome {
		public final Status status;
		@Nullable
		public final PayjoinValidator.Reason reason;
		@Nullable
		public final PayjoinValidator.ProposedTx proposed;
		@Nullable
		public final PayjoinValidator.Result result;

		Outcome(Status status, @Nullable PayjoinValidator.Reason reason,
				@Nullable PayjoinValidator.ProposedTx proposed,
				@Nullable PayjoinValidator.Result result) {
			this.status = status;
			this.reason = reason;
			this.proposed = proposed;
			this.result = result;
		}
	}

	private final PayjoinTransport transport;
	private final ProposalParser parser;
	private boolean consumed;

	public PayjoinSession(PayjoinTransport transport, ProposalParser parser) {
		this.transport = transport;
		this.parser = parser;
	}

	public Outcome requestAndValidate(String pjUri, byte[] originalProposal,
			int socksPort, String isolationTag,
			PayjoinValidator.OriginalTx original,
			PayjoinValidator.Policy policy) {
		if (consumed) {
			return new Outcome(Status.REPLAYED, null, null, null);
		}
		byte[] resp;
		try {
			resp = transport.exchange(pjUri, originalProposal, socksPort,
					isolationTag);
		} catch (Throwable t) {
			return new Outcome(Status.FAILED, null, null, null);
		}
		if (resp == null) {
			return new Outcome(Status.FAILED, null, null, null);
		}
		consumed = true;
		PayjoinValidator.ProposedTx prop;
		try {
			prop = parser.parse(resp);
		} catch (Throwable t) {
			return new Outcome(Status.REJECTED, PayjoinValidator.Reason.MALFORMED,
					null, null);
		}
		PayjoinValidator.Result v =
				PayjoinValidator.validate(original, prop, policy);
		if (!v.ok) {
			return new Outcome(Status.REJECTED, v.reason, prop, v);
		}
		return new Outcome(Status.VALIDATED, PayjoinValidator.Reason.OK, prop,
				v);
	}
}
