package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * {@link MoneroEngine} backed by the wallet2_api JNI library. Thin: it converts
 * opaque native handles into {@link Session}/{@link Prepared} objects and
 * forwards. Fail-closed: if the native library is unavailable every factory
 * method returns null, so the caller can present an honest "engine unavailable"
 * state and never proceed to a weaker path. No key material is retained here.
 */
@NotNullByDefault
public final class NativeMoneroEngine implements MoneroEngine {

	@Override
	public boolean isAvailable() {
		return NativeMonero.isAvailable();
	}

	private static byte[] utf8(char[] c) {
		if (c == null) return new byte[0];
		java.nio.ByteBuffer bb = java.nio.charset.StandardCharsets.UTF_8.encode(
				java.nio.CharBuffer.wrap(c));
		byte[] out = new byte[bb.remaining()];
		bb.get(out);
		java.util.Arrays.fill(bb.array(), (byte) 0);
		return out;
	}

	private static char[] toChars(byte[] b) {
		if (b == null || b.length == 0) return new char[0];
		java.nio.CharBuffer cb = java.nio.charset.StandardCharsets.UTF_8.decode(
				java.nio.ByteBuffer.wrap(b));
		char[] out = new char[cb.remaining()];
		cb.get(out);
		java.util.Arrays.fill(cb.array(), '\0');
		return out;
	}

	@Nullable
	@Override
	public Session create(String path, char[] password, String language) {
		if (!NativeMonero.isAvailable()) return null;
		byte[] pw = utf8(password);
		try {
			long h = NativeMonero.nCreate(path, pw, language);
			return h == 0 ? null : new NativeSession(h);
		} finally {
			java.util.Arrays.fill(pw, (byte) 0);
		}
	}

	@Nullable
	@Override
	public Session restore(String path, char[] password, char[] seed,
			long restoreHeight, char[] seedOffset) {
		if (!NativeMonero.isAvailable()) return null;
		byte[] pw = utf8(password);
		byte[] sd = utf8(seed);
		byte[] off = utf8(seedOffset);
		try {
			long h = NativeMonero.nRestore(path, pw, sd, restoreHeight, off);
			return h == 0 ? null : new NativeSession(h);
		} finally {
			java.util.Arrays.fill(pw, (byte) 0);
			java.util.Arrays.fill(sd, (byte) 0);
			java.util.Arrays.fill(off, (byte) 0);
		}
	}

	@Nullable
	@Override
	public Session open(String path, char[] password) {
		if (!NativeMonero.isAvailable()) return null;
		byte[] pw = utf8(password);
		try {
			long h = NativeMonero.nOpen(path, pw);
			return h == 0 ? null : new NativeSession(h);
		} finally {
			java.util.Arrays.fill(pw, (byte) 0);
		}
	}

	@Override
	public boolean validateAddress(String address) {
		return NativeMonero.isAvailable()
				&& NativeMonero.nValidateAddress(address);
	}

	@Override
	public AddressKind addressKind(String address) {
		if (!NativeMonero.isAvailable()) return AddressKind.INVALID;
		switch (NativeMonero.nAddressKind(address)) {
			case 1:
				return AddressKind.STANDARD;
			case 2:
				return AddressKind.SUBADDRESS;
			case 3:
				return AddressKind.INTEGRATED;
			default:
				return AddressKind.INVALID;
		}
	}

	private static final class NativeSession implements Session {
		private final long h;
		private final java.util.concurrent.atomic.AtomicBoolean closed =
				new java.util.concurrent.atomic.AtomicBoolean(false);

		NativeSession(long h) {
			this.h = h;
		}

		@Override
		public int status() {
			if (closed.get()) return -1;
			return NativeMonero.nStatus(h);
		}

		@Nullable
		@Override
		public String errorString() {
			if (closed.get()) return null;
			return NativeMonero.nErrorString(h);
		}

		@Override
		public char[] seed(char[] seedOffset) {
			if (closed.get()) return new char[0];
			byte[] off = utf8(seedOffset);
			byte[] raw = null;
			try {
				raw = NativeMonero.nSeed(h, off);
				return toChars(raw);
			} finally {
				java.util.Arrays.fill(off, (byte) 0);
				if (raw != null) java.util.Arrays.fill(raw, (byte) 0);
			}
		}

		@Override
		public String address(long account, long subaddress) {
			if (closed.get()) return "";
			return NativeMonero.nAddress(h, account, subaddress);
		}

		@Override
		public void addSubaddress(long account, String label) {
			if (closed.get()) return;
			NativeMonero.nAddSubaddress(h, account, label);
		}

		@Override
		public long numSubaddresses(long account) {
			if (closed.get()) return 0;
			return NativeMonero.nNumSubaddresses(h, account);
		}

		@Override
		public boolean init(String daemonAddress, String proxyAddress,
				boolean trustedDaemon) {
			if (closed.get()) return false;
			return NativeMonero.nInit(h, daemonAddress, proxyAddress,
					trustedDaemon);
		}

		@Override
		public void setRefreshFromHeight(long height) {
			if (closed.get()) return;
			NativeMonero.nSetRefreshFromHeight(h, height);
		}

		@Override
		public void setRecoveringFromSeed(boolean recovering) {
			if (closed.get()) return;
			NativeMonero.nSetRecoveringFromSeed(h, recovering);
		}

		@Override
		public boolean refresh() {
			if (closed.get()) return false;
			return NativeMonero.nRefresh(h);
		}

		@Override
		public void setAutoRefreshInterval(int millis) {
			if (closed.get()) return;
			NativeMonero.nSetAutoRefreshInterval(h, millis);
		}

		@Override
		public void startRefresh() {
			if (closed.get()) return;
			NativeMonero.nStartRefresh(h);
		}

		@Override
		public void pauseRefresh() {
			if (closed.get()) return;
			NativeMonero.nPauseRefresh(h);
		}

		@Override
		public long blockchainHeight() {
			if (closed.get()) return 0;
			return NativeMonero.nBlockchainHeight(h);
		}

		@Override
		public long daemonHeight() {
			if (closed.get()) return 0;
			return NativeMonero.nDaemonHeight(h);
		}

		@Override
		public boolean isSynchronized() {
			if (closed.get()) return false;
			return NativeMonero.nSynchronized(h);
		}

		@Override
		public void stopRefresh() {
			if (closed.get()) return;
			NativeMonero.nStop(h);
		}

		@Override
		public int connectionStatus() {
			if (closed.get()) return 0;
			return NativeMonero.nConnected(h);
		}

		@Override
		public long balance(long account) {
			if (closed.get()) return 0;
			return NativeMonero.nBalance(h, account);
		}

		@Override
		public long unlockedBalance(long account) {
			if (closed.get()) return 0;
			return NativeMonero.nUnlockedBalance(h, account);
		}

		@Override
		public java.util.List<XmrTxInfo> history() {
			java.util.List<XmrTxInfo> out = new java.util.ArrayList<>();
			if (closed.get()) return out;
			String snapshot = NativeMonero.nHistory(h);

			if (snapshot == null) {
				throw new IllegalStateException("xmr history unavailable");
			}
			if (snapshot.isEmpty()) return out;
			for (String line : snapshot.split("\n")) {
				if (line.isEmpty()) continue;
				XmrTxInfo tx = XmrTxInfo.parse(line);
				if (tx != null) out.add(tx);
			}
			return out;
		}

		@Nullable
		@Override
		public Prepared prepare(String address, long amountAtomic, int priority,
				long account) {
			if (closed.get()) return null;
			long tx = NativeMonero.nPrepare(h, address, amountAtomic, priority,
					account);
			if (tx == 0) return null;
			NativePrepared p = new NativePrepared(h, tx);

			if (p.status() != 0) {
				p.close();
				return null;
			}
			return p;
		}

		@Override
		public boolean waitRefreshIdle(long timeoutMs) {
			long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
			for (;;) {
				if (closed.get()) return false;
				long left = deadline - System.currentTimeMillis();
				long slice = Math.min(1000, Math.max(0, left));
				if (NativeMonero.nWaitRefreshIdle(h, slice)) return true;
				if (left <= 0) return false;
			}
		}

		@Override
		public java.util.List<XmrTxLookup> lookupTxs(
				java.util.List<String> txids, long timeoutMs) {
			if (closed.get()) return XmrTxLookup.decode(txids, null);
			String[] in = txids.toArray(new String[0]);
			long[] codes = NativeMonero.nLookupTxs(h, in, timeoutMs);
			return XmrTxLookup.decode(txids, codes);
		}

		@Override
		public boolean store(String path) {
			if (closed.get()) return false;
			return NativeMonero.nStore(h, path);
		}

		@Override
		public boolean setupBackgroundSync(char[] walletPassword,
				char[] backgroundPassword) {
			if (closed.get()) return false;
			byte[] wp = utf8(walletPassword);
			byte[] bp = utf8(backgroundPassword);
			try {
				return NativeMonero.nSetupBackgroundSync(h, wp, bp);
			} finally {
				java.util.Arrays.fill(wp, (byte) 0);
				java.util.Arrays.fill(bp, (byte) 0);
			}
		}

		@Override
		public boolean startBackgroundSync() {
			return !closed.get() && NativeMonero.nStartBackgroundSync(h);
		}

		@Override
		public boolean stopBackgroundSync(char[] walletPassword) {
			if (closed.get()) return false;
			byte[] wp = utf8(walletPassword);
			try {
				return NativeMonero.nStopBackgroundSync(h, wp);
			} finally {
				java.util.Arrays.fill(wp, (byte) 0);
			}
		}

		@Override
		public boolean isBackgroundSyncing() {
			return !closed.get() && NativeMonero.nIsBackgroundSyncing(h);
		}

		@Override
		public boolean isBackgroundWallet() {
			return !closed.get() && NativeMonero.nIsBackgroundWallet(h);
		}

		@Override
		public int backgroundSyncType() {
			if (closed.get()) return -1;
			return NativeMonero.nBackgroundSyncType(h);
		}

		@Override
		public boolean setPassword(char[] password) {
			if (closed.get()) return false;
			byte[] pw = utf8(password);
			try {
				return NativeMonero.nSetPassword(h, pw);
			} finally {
				java.util.Arrays.fill(pw, (byte) 0);
			}
		}

		@Override
		public void closePersisting() {
			if (closed.compareAndSet(false, true)) {
				quiesce();
				NativeMonero.nClose(h, true);
			}
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				quiesce();
				NativeMonero.nClose(h, false);
			}
		}

		/**
		 * Disable the refresh thread, interrupt any refresh in flight, then join
		 * the thread. Pausing first means a refresh that started after the
		 * caller's interrupt cannot run a whole catch-up before the join returns.
		 */
		private void quiesce() {
			NativeMonero.nPauseRefresh(h);
			NativeMonero.nStop(h);
			NativeMonero.nStopRefreshThread(h);
		}
	}

	private static final class NativePrepared implements Prepared {
		private final long wallet;
		private final long tx;
		private final java.util.concurrent.atomic.AtomicBoolean disposed =
				new java.util.concurrent.atomic.AtomicBoolean(false);

		NativePrepared(long wallet, long tx) {
			this.wallet = wallet;
			this.tx = tx;
		}

		@Override
		public int status() {
			if (disposed.get()) return -1;
			return NativeMonero.nTxStatus(tx);
		}

		@Nullable
		@Override
		public String errorString() {
			if (disposed.get()) return null;
			return NativeMonero.nTxError(tx);
		}

		@Override
		public long feeAtomic() {
			if (disposed.get()) return 0;
			return NativeMonero.nTxFee(tx);
		}

		@Override
		public long amountAtomic() {
			if (disposed.get()) return 0;
			return NativeMonero.nTxAmount(tx);
		}

		@Override
		public String txId() {
			if (disposed.get()) return "";
			return NativeMonero.nTxId(tx);
		}

		@Override
		public java.util.List<String> txIds() {
			java.util.List<String> out = new java.util.ArrayList<>();
			if (disposed.get()) return out;
			String[] ids = NativeMonero.nTxIds(tx);
			if (ids == null) return out;
			for (String id : ids) {
				if (id == null || !XmrTxLookup.isTxidHex(id)) {
					out.clear();
					return out;
				}
				out.add(id);
			}
			return out;
		}

		@Override
		public long txCount() {
			if (disposed.get()) return NativeMonero.LONG_ERR;
			return NativeMonero.nTxCount(tx);
		}

		@Override
		public long dustAtomic() {
			if (disposed.get()) return NativeMonero.LONG_ERR;
			return NativeMonero.nTxDust(tx);
		}

		@Override
		public long changeAtomic() {
			if (disposed.get()) return NativeMonero.LONG_ERR;
			return NativeMonero.nTxChange(tx);
		}

		@Override
		public boolean commit() {
			if (disposed.get()) return false;
			return NativeMonero.nCommit(tx);
		}

		@Override
		public boolean isDisposed() {
			return disposed.get();
		}

		@Override
		public void close() {
			if (disposed.compareAndSet(false, true)) {
				NativeMonero.nDisposeTx(wallet, tx);
			}
		}
	}
}
