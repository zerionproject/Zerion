package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import com.professor.zerion.android.vault.wallet.WalletCoin;
import com.professor.zerion.android.vault.wallet.WalletRecord;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

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

	@Nullable
	byte[] readWalletSecret(String walletId, String name) throws Exception;

	void writeWalletSecret(String walletId, String name, byte[] value)
			throws Exception;

	void removeWalletSecret(String walletId, String name) throws Exception;

	@Nullable
	String readSpendJournal(String walletId) throws Exception;

	void writeSpendJournal(String walletId, String journal) throws Exception;

	void removeSpendJournal(String walletId) throws Exception;
}
