package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface MoneroEngine {

	boolean isAvailable();

	interface Session extends AutoCloseable {
		int status();

		@Nullable
		String errorString();

		char[] seed(char[] seedOffset);

		String address(long account, long subaddress);

		void addSubaddress(long account, String label);

		long numSubaddresses(long account);

		boolean init(String daemonAddress, String proxyAddress,
				boolean trustedDaemon);

		void setRefreshFromHeight(long height);

		default void setRecoveringFromSeed(boolean recovering) {
		}

		boolean refresh();

		void setAutoRefreshInterval(int millis);

		void startRefresh();

		void pauseRefresh();

		default void interruptRefresh() {
			pauseRefresh();
			stopRefresh();
		}

		default boolean rescanBlockchain() {
			return false;
		}

		default boolean trustedDaemon() {
			return false;
		}

		long blockchainHeight();

		long daemonHeight();

		boolean isSynchronized();

		void stopRefresh();

		int connectionStatus();

		long balance(long account);

		long unlockedBalance(long account);

		java.util.List<XmrTxInfo> history();

		@Nullable
		Prepared prepare(String address, long amountAtomic, int priority,
				long account);

		boolean waitRefreshIdle(long timeoutMs);

		java.util.List<XmrTxLookup> lookupTxs(java.util.List<String> txids,
				long timeoutMs);

		boolean store(String path);

		default boolean setupBackgroundSync(char[] walletPassword,
				char[] backgroundPassword) {
			return false;
		}

		default boolean startBackgroundSync() {
			return false;
		}

		default boolean stopBackgroundSync(char[] walletPassword) {
			return false;
		}

		default boolean isBackgroundSyncing() {
			return false;
		}

		default boolean isBackgroundWallet() {
			return false;
		}

		default int backgroundSyncType() {
			return 0;
		}

		default boolean setPassword(char[] password) {
			return false;
		}

		boolean closePersisting();

		@Override
		void close();
	}

	enum AddressKind { INVALID, STANDARD, SUBADDRESS, INTEGRATED }

	interface Prepared extends AutoCloseable {
		int status();

		@Nullable
		String errorString();

		long feeAtomic();

		long amountAtomic();

		String txId();

		java.util.List<String> txIds();

		long txCount();

		long dustAtomic();

		long changeAtomic();

		boolean commit();

		boolean isDisposed();

		@Override
		void close();
	}

	@Nullable
	Session create(String path, char[] password, String language);

	@Nullable
	Session restore(String path, char[] password, char[] seed,
			long restoreHeight, char[] seedOffset);

	@Nullable
	Session open(String path, char[] password);

	boolean validateAddress(String address);

	AddressKind addressKind(String address);
}
