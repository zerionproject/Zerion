package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import com.professor.zerion.android.vault.wallet.WalletCoin;
import com.professor.zerion.android.vault.wallet.WalletRecord;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

/**
 * The minimal ZVault storage surface the XMR layer uses. WalletStore implements
 * this; tests provide a fake. Keeps the XMR layer decoupled from the concrete
 * store and independently testable without touching real vault storage.
 */
@NotNullByDefault
public interface XmrStore {
	String createWallet(WalletCoin coin, String name, char[] mnemonic,
			@Nullable char[] password) throws Exception;

	char[] loadMnemonicChars(String walletId, @Nullable char[] password)
			throws Exception;

	List<WalletRecord> listWallets() throws Exception;

	void deleteWallet(String walletId) throws Exception;

	@Nullable
	String readSettings() throws Exception;

	void writeSettings(String json) throws Exception;

	Object settingsMonitor();

	/**
	 * Read the durable spend-journal string for a wallet, or null if none. Kept
	 * out of the settings blob so its reader can be strictly fail-closed and does
	 * not share the settings JSON's lenient parsing.
	 */
	@Nullable
	String readSpendJournal(String walletId) throws Exception;

	/**
	 * Durably persist the spend-journal string for a wallet. Returns only after
	 * the record is committed to disk, so a relay can be gated on its success.
	 */
	void writeSpendJournal(String walletId, String journal) throws Exception;

	/** Remove the spend-journal for a wallet. Owned by the reconciliation
	 *  authority, never a UI action. */
	void removeSpendJournal(String walletId) throws Exception;
}
