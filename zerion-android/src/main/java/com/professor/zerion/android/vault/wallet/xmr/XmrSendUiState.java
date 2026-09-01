package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

/**
 * The immutable state the Send UI renders, posted by the manager as the one
 * send flow advances. It exposes only what the screens need and never any
 * internal identity: no fingerprint, native handle, endpoint id or journal
 * detail. The UI reads this and drives the flow through the manager; it never
 * touches the native transaction or the journal itself.
 */
@NotNullByDefault
public final class XmrSendUiState {

	public enum Kind {
		INPUT, PREPARING, REVIEW, AUTHENTICATING, RELAYING, SUCCESS,
		RELAY_UNCERTAIN, FAILED, QUARANTINED, CANCELLED
	}

	/** The reviewed, already-signed transaction as shown on the Review screen. */
	public static final class Review {
		public final long amountAtomic;
		public final String destination;
		public final MoneroEngine.AddressKind destinationKind;
		public final long networkFeeAtomic;
		public final long totalDebitAtomic;
		public final int txCount;
		public final String fromWalletLabel;

		public Review(long amountAtomic, String destination,
				MoneroEngine.AddressKind destinationKind, long networkFeeAtomic,
				long totalDebitAtomic, int txCount, String fromWalletLabel) {
			this.amountAtomic = amountAtomic;
			this.destination = destination;
			this.destinationKind = destinationKind;
			this.networkFeeAtomic = networkFeeAtomic;
			this.totalDebitAtomic = totalDebitAtomic;
			this.txCount = txCount;
			this.fromWalletLabel = fromWalletLabel;
		}

		public boolean isMultiTx() {
			return txCount > 1;
		}
	}

	public final Kind kind;
	@Nullable
	private final Review review;
	private final String[] txids;
	@Nullable
	public final XmrError error;

	private XmrSendUiState(Kind kind, @Nullable Review review, String[] txids,
			@Nullable XmrError error) {
		this.kind = kind;
		this.review = review;
		this.txids = txids;
		this.error = error;
	}

	@Nullable
	public Review review() {
		return review;
	}

	public List<String> txids() {
		return new ArrayList<>(java.util.Arrays.asList(txids));
	}

	private static final String[] NO_TX = new String[0];

	public static XmrSendUiState input() {
		return new XmrSendUiState(Kind.INPUT, null, NO_TX, null);
	}

	public static XmrSendUiState preparing() {
		return new XmrSendUiState(Kind.PREPARING, null, NO_TX, null);
	}

	public static XmrSendUiState review(Review r) {
		return new XmrSendUiState(Kind.REVIEW, r, NO_TX, null);
	}

	public static XmrSendUiState authenticating() {
		return new XmrSendUiState(Kind.AUTHENTICATING, null, NO_TX, null);
	}

	public static XmrSendUiState relaying() {
		return new XmrSendUiState(Kind.RELAYING, null, NO_TX, null);
	}

	public static XmrSendUiState success(List<String> txids) {
		return new XmrSendUiState(Kind.SUCCESS, null,
				txids.toArray(new String[0]), null);
	}

	public static XmrSendUiState relayUncertain() {
		return new XmrSendUiState(Kind.RELAY_UNCERTAIN, null, NO_TX, null);
	}

	public static XmrSendUiState failed(XmrError error) {
		return new XmrSendUiState(Kind.FAILED, null, NO_TX, error);
	}

	public static XmrSendUiState quarantined() {
		return new XmrSendUiState(Kind.QUARANTINED, null, NO_TX, null);
	}

	public static XmrSendUiState cancelled() {
		return new XmrSendUiState(Kind.CANCELLED, null, NO_TX, null);
	}
}
