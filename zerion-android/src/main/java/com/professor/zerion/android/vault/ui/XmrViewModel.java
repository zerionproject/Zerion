package com.professor.zerion.android.vault.ui;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.professor.zerion.android.vault.wallet.WalletRecord;
import com.professor.zerion.android.vault.wallet.xmr.XmrError;
import com.professor.zerion.android.vault.wallet.xmr.XmrWalletManager;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.inject.Inject;

/**
 * Per-activity UI façade over the single application-scoped
 * {@link XmrWalletManager}. Holds no wallet secrets and owns no session: every
 * hosting activity resolves the same manager, so one wallet can never have
 * two native sessions through two surfaces. Clearing this ViewModel only
 * detaches its observers (they are lifecycle-bound); the manager, and the
 * session it owns, live and die with the vault lock, not with the activity.
 */
@NotNullByDefault
public class XmrViewModel extends AndroidViewModel {

	private final XmrWalletManager manager;

	@Inject
	public XmrViewModel(Application application, XmrWalletManager manager) {
		super(application);
		this.manager = manager;
	}

	XmrWalletManager manager() {
		return manager;
	}

	public void refreshNow() {
		manager.refreshNow();
	}

	public LiveData<
			com.professor.zerion.android.vault.wallet.xmr.XmrPrice.Rates>
			getXmrRates() {
		return manager.getXmrRates();
	}

	public void loadPrice() {
		manager.loadPrice();
	}

	public void loadCachedPrice() {
		manager.loadCachedPrice();
	}

	@androidx.annotation.Nullable
	public char[] takeRecoverySeed(String walletId) {
		return manager.takePendingSeed(walletId);
	}

	public LiveData<Boolean> getBackupVerified() {
		return manager.getBackupVerified();
	}

	public void loadBackupState(String walletId) {
		manager.loadBackupState(walletId);
	}

	public void setBackupVerified(String walletId, boolean verified) {
		manager.setBackupVerified(walletId, verified);
	}

	public com.professor.zerion.android.vault.wallet.xmr.XmrNodeConfig
			getNodeConfig() {
		return manager.currentNodeConfig();
	}

	public void saveNodeConfig(
			com.professor.zerion.android.vault.wallet.xmr.XmrNodeConfig config) {
		manager.saveNodeConfig(config);
	}

	public androidx.lifecycle.LiveData<
			com.professor.zerion.android.vault.wallet.xmr.XmrSyncStatus>
			getSyncStatus() {
		return manager.getSyncStatus();
	}

	public LiveData<List<WalletRecord>> getWallets() {
		return manager.getWallets();
	}

	public LiveData<Event<String>> getSessionOpened() {
		return manager.getSessionOpened();
	}

	public LiveData<Event<XmrError>> getError() {
		return manager.getError();
	}

	public LiveData<Event<String>> getSeedReveal() {
		return manager.getSeedReveal();
	}

	public LiveData<Event<String>> getWalletDeleted() {
		return manager.getWalletDeleted();
	}

	public LiveData<Boolean> getBusy() {
		return manager.getBusy();
	}

	public LiveData<Event<
			com.professor.zerion.android.vault.wallet.xmr.XmrReceiveAddress>>
			getReceiveAddress() {
		return manager.getReceiveAddress();
	}

	public LiveData<java.util.List<
			com.professor.zerion.android.vault.wallet.xmr.XmrReceiveAddress>>
			getReceiveList() {
		return manager.getReceiveList();
	}

	public void newReceiveAddress(String walletId) {
		manager.newReceiveAddress(walletId);
	}

	public void loadReceiveList(String walletId) {
		manager.loadReceiveList(walletId);
	}

	public LiveData<java.util.List<
			com.professor.zerion.android.vault.wallet.xmr.XmrTxInfo>>
			getHistory() {
		return manager.getHistory();
	}

	public void loadHistory(String walletId) {
		manager.loadHistory(walletId);
	}

	public void retrySync() {
		manager.retrySync();
	}

	public LiveData<com.professor.zerion.android.vault.wallet.xmr
			.XmrSendUiState> getSendState() {
		return manager.getSendState();
	}

	public boolean isSpendQuarantined(String walletId) {
		return manager.isSpendQuarantined(walletId);
	}

	public boolean isValidXmrAddress(String address) {
		return manager.isValidXmrAddress(address);
	}

	public void prepareSend(String walletId, String walletLabel,
			String destination, long amountAtomic, int priority,
			char[] walletPassword) {
		manager.prepareSend(walletId, walletLabel, destination, amountAtomic,
				priority, walletPassword);
	}

	public void confirmSend(char[] walletPassword) {
		manager.confirmSend(walletPassword);
	}

	public void openWalletForView(String walletId) {
		manager.openWalletForView(walletId);
	}

	public boolean needsPasswordToOpen(String walletId) {
		return manager.needsPasswordToOpen(walletId);
	}

	public void cancelSend() {
		manager.cancelSend();
	}

	public void setReceiveLabel(String walletId, int index,
			@androidx.annotation.Nullable String label) {
		manager.setReceiveLabel(walletId, index, label);
	}

	public void loadWallets() {
		manager.loadWallets();
	}

	public void createWallet(String name, char[] password) {
		manager.createWallet(name, password);
	}

	public void importWallet(String name, char[] seed, long restoreHeight,
			char[] password) {
		manager.importWallet(name, seed, restoreHeight, password);
	}

	public void openWallet(String walletId, char[] password) {
		manager.openWallet(walletId, password);
	}

	public void revealSeed(String walletId, char[] password) {
		manager.revealSeed(walletId, password);
	}

	public void renameWallet(String walletId, String newName, char[] password) {
		manager.renameWallet(walletId, newName, password);
	}

	public void deleteWallet(String walletId, char[] password) {
		manager.deleteWallet(walletId, password);
	}

	public void rescan(String walletId, char[] password, long restoreHeight) {
		manager.rescan(walletId, password, restoreHeight);
	}

	public void closeSession() {
		manager.closeSession();
	}

	public boolean isSessionValid() {
		return manager.isSessionValid();
	}

	@androidx.annotation.Nullable
	public String openWalletId() {
		return manager.openWalletId();
	}
}
