package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class NativeMonero {

	static final long LONG_ERR = Long.MIN_VALUE;

	private static final boolean AVAILABLE;

	static {
		boolean ok;
		try {
			System.loadLibrary("zmonero");
			ok = true;
		} catch (Throwable t) {
			ok = false;
		}
		AVAILABLE = ok;
	}

	private NativeMonero() {
	}

	public static boolean isAvailable() {
		return AVAILABLE;
	}

	static native long nCreate(String path, byte[] password, String language);

	static native long nRestore(String path, byte[] password, byte[] seed,
			long restoreHeight, byte[] seedOffset);

	static native long nOpen(String path, byte[] password);

	static native int nStatus(long wallet);

	static native String nErrorString(long wallet);

	static native boolean nStore(long wallet, String path);

	static native boolean nSetupBackgroundSync(long wallet,
			byte[] walletPassword, byte[] backgroundPassword);

	static native boolean nStartBackgroundSync(long wallet);

	static native boolean nStopBackgroundSync(long wallet,
			byte[] walletPassword);

	static native boolean nIsBackgroundSyncing(long wallet);

	static native boolean nIsBackgroundWallet(long wallet);

	static native int nBackgroundSyncType(long wallet);

	static native boolean nSetPassword(long wallet, byte[] password);

	static native boolean nClose(long wallet, boolean store);

	static native byte[] nSeed(long wallet, byte[] seedOffset);

	static native String nAddress(long wallet, long account, long subaddr);

	static native void nAddSubaddress(long wallet, long account, String label);

	static native long nNumSubaddresses(long wallet, long account);

	static native boolean nValidateAddress(String address);

	static native boolean nInit(long wallet, String daemonAddress,
			String proxyAddress, boolean trusted);

	static native void nSetRefreshFromHeight(long wallet, long height);

	static native long nGetRefreshFromHeight(long wallet);

	static native boolean nTrustedDaemon(long wallet);

	static native boolean nRescanBlockchain(long wallet);

	static native void nSetRecoveringFromSeed(long wallet, boolean recovering);

	static native boolean nRefresh(long wallet);

	static native long nBlockchainHeight(long wallet);

	static native long nDaemonHeight(long wallet);

	static native boolean nSynchronized(long wallet);

	static native long nBalance(long wallet, long account);

	static native long nUnlockedBalance(long wallet, long account);

	static native String nHistory(long wallet);

	static native void nStop(long wallet);

	static native int nConnected(long wallet);

	static native void nSetAutoRefreshInterval(long wallet, int millis);

	static native void nStartRefresh(long wallet);

	static native void nPauseRefresh(long wallet);

	static native void nStopRefreshThread(long wallet);

	static native long nPrepare(long wallet, String address, long amount,
			int priority, long account);

	static native int nTxStatus(long tx);

	static native String nTxError(long tx);

	static native long nTxFee(long tx);

	static native long nTxAmount(long tx);

	static native String nTxId(long tx);

	static native boolean nCommit(long tx);

	static native void nDisposeTx(long wallet, long tx);

	static native String[] nTxIds(long tx);

	static native long nTxCount(long tx);

	static native long nTxDust(long tx);

	static native long nTxChange(long tx);

	static native int nAddressKind(String address);

	static native boolean nWaitRefreshIdle(long wallet, long timeoutMs);

	static native long[] nLookupTxs(long wallet, String[] txids, long timeoutMs);
}
