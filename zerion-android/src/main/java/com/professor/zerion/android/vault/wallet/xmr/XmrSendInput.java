package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Pure validation of the Send screen's inputs and preconditions, with no
 * Android or view dependency so it is fully unit-testable. It decides only
 * whether the user may continue and why not; it owns no user-facing copy, and
 * it is never the fund-safety authority: address validity comes from Monero's
 * own parser through {@link MoneroEngine#addressKind}, the amount from the exact
 * atomic converter, and the actual quarantine, sync and balance facts from the
 * manager. The send flow re-checks all of this before it constructs anything, so
 * this only gates the button.
 */
@NotNullByDefault
public final class XmrSendInput {

	public enum Block {
		NONE, NOT_SYNCED, NO_BALANCE, QUARANTINED, BUSY, BAD_ADDRESS, BAD_AMOUNT,
		INSUFFICIENT_FUNDS
	}

	public final MoneroEngine.AddressKind addressKind;
	public final boolean addressValid;
	public final long amountAtomic;
	public final boolean amountValid;
	public final Block block;

	private XmrSendInput(MoneroEngine.AddressKind addressKind, long amountAtomic,
			boolean amountValid, Block block) {
		this.addressKind = addressKind;
		this.addressValid = addressKind != MoneroEngine.AddressKind.INVALID;
		this.amountAtomic = amountAtomic;
		this.amountValid = amountValid;
		this.block = block;
	}

	public boolean canContinue() {
		return block == Block.NONE;
	}

	/**
	 * Evaluate the inputs against the wallet's live state. Precondition blocks
	 * (not synced, no balance, quarantined, busy) take priority over field
	 * problems so the user is told the most fundamental reason first.
	 */
	public static XmrSendInput evaluate(MoneroEngine engine, String destination,
			String amountText, boolean synced, long unlockedBalanceAtomic,
			boolean quarantined, boolean busy) {
		MoneroEngine.AddressKind kind = engine.addressKind(destination.trim());
		long amount = -1;
		boolean amountOk;
		try {
			amount = MoneroUri.parseXmrToAtomic(amountText.trim());
			amountOk = amount > 0;
		} catch (XmrError.XmrException e) {
			amountOk = false;
		}

		Block block;
		if (quarantined) {
			block = Block.QUARANTINED;
		} else if (busy) {
			block = Block.BUSY;
		} else if (!synced) {
			block = Block.NOT_SYNCED;
		} else if (unlockedBalanceAtomic <= 0) {
			block = Block.NO_BALANCE;
		} else if (kind == MoneroEngine.AddressKind.INVALID) {
			block = Block.BAD_ADDRESS;
		} else if (!amountOk) {
			block = Block.BAD_AMOUNT;
		} else if (amount > unlockedBalanceAtomic) {
			block = Block.INSUFFICIENT_FUNDS;
		} else {
			block = Block.NONE;
		}
		return new XmrSendInput(kind, amount, amountOk, block);
	}
}
