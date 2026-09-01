package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

/**
 * In-JVM fake of {@link MoneroEngine} for deterministic XmrWalletManager tests.
 * No native code, no real storage. Lets tests drive create/restore/open
 * outcomes and observe close/seed calls.
 */
public final class FakeMoneroEngine implements MoneroEngine {

	static final String FAKE_SEED =
			"aabbcc ddeeff gghhii jjkkll mmnnoo ppqqrr ssttuu vvwwxx yyzzaa "
					+ "bbccdd eeffgg hhiijj kkllmm nnoopp qqrrss ttuuvv wwxxyy "
					+ "zzaabb ccddee ffgghh iijjkk llmmnn ooppqq rrsstt uuvvww";

	boolean available = true;
	boolean createFails = false;
	int restoreStatusForBadSeed = 1;
	int openCount = 0;
	int closeCount = 0;

	@Override
	public boolean isAvailable() {
		return available;
	}

	@Nullable
	@Override
	public Session create(String path, char[] password, String language) {
		if (!available || createFails) return null;
		touch(path);
		touch(path + ".keys");
		return new FakeSession(0, path, false);
	}

	@Nullable
	@Override
	public Session restore(String path, char[] password, char[] seed,
			long restoreHeight, char[] seedOffset) {
		if (!available) return null;
		String s = new String(seed).trim();
		boolean valid = s.split("\\s+").length == 25;
		openCount++;
		if (valid) {
			touch(path);
			touch(path + ".keys");
		}
		return new FakeSession(valid ? 0 : restoreStatusForBadSeed, path, false);
	}

	@Nullable
	@Override
	public Session open(String path, char[] password) {
		if (!available) return null;
		openCount++;
		boolean bg = path.endsWith(".background");
		return new FakeSession(0, path, bg);
	}

	private static void touch(String path) {
		try {
			java.io.File f = new java.io.File(path);
			if (!f.isAbsolute()) return;
			java.io.File parent = f.getParentFile();
			if (parent != null) parent.mkdirs();
			if (!f.exists()) {
				new java.io.FileOutputStream(f).close();
			}
		} catch (Exception ignored) {
		}
	}

	@Override
	public boolean validateAddress(String address) {
		return available && (address.startsWith("4") || address.startsWith("8"))
				&& address.length() > 90;
	}

	@Override
	public AddressKind addressKind(String address) {
		if (!validateAddress(address)) return AddressKind.INVALID;
		if (address.length() > 100) return AddressKind.INTEGRATED;
		return address.startsWith("8") ? AddressKind.SUBADDRESS
				: AddressKind.STANDARD;
	}

	/** Configurable prepared transaction for JVM tests; never relays. */
	public static final class FakePrepared implements Prepared {
		public java.util.List<String> ids = new java.util.ArrayList<>();
		public long fee, amount, dust, count, change;
		public int status;
		public boolean disposed;
		public int commits;
		public boolean commitResult = true;
		public int inspections;
		@Nullable
		public Runnable onInspect;

		private void inspected() {
			inspections++;
			if (onInspect != null) onInspect.run();
		}

		@Override
		public int status() {
			return disposed ? -1 : status;
		}

		@Nullable
		@Override
		public String errorString() {
			return status == 0 ? "" : "fake";
		}

		@Override
		public long feeAtomic() {
			inspected();
			return disposed ? -1 : fee;
		}

		@Override
		public long amountAtomic() {
			inspected();
			return disposed ? -1 : amount;
		}

		@Override
		public String txId() {
			return disposed || ids.isEmpty() ? "" : ids.get(0);
		}

		@Override
		public java.util.List<String> txIds() {
			inspected();
			return disposed ? new java.util.ArrayList<>()
					: new java.util.ArrayList<>(ids);
		}

		@Override
		public long txCount() {
			inspected();
			return disposed ? NativeMonero.LONG_ERR : count;
		}

		@Override
		public long dustAtomic() {
			inspected();
			return disposed ? NativeMonero.LONG_ERR : dust;
		}

		@Override
		public long changeAtomic() {
			return disposed ? NativeMonero.LONG_ERR : change;
		}

		@Override
		public boolean commit() {
			if (disposed) return false;
			commits++;
			return commitResult;
		}

		@Override
		public boolean isDisposed() {
			return disposed;
		}

		public int closes;

		@Override
		public void close() {
			disposed = true;
			closes++;
		}
	}

	volatile boolean refreshIdle = true;
	@Nullable
	volatile long[] lookupCodes;

	private java.util.List<XmrTxLookup> fakeLookup(java.util.List<String> ids) {
		return XmrTxLookup.decode(ids, lookupCodes);
	}

	final class FakeSession implements Session {
		private final int status;
		private final String basePath;
		private final boolean background;
		boolean closed = false;
		int subaddresses = 1;

		FakeSession(int status) {
			this(status, "", false);
		}

		FakeSession(int status, String basePath, boolean background) {
			this.status = status;
			this.basePath = basePath;
			this.background = background;
		}

		private String walletBase() {
			return basePath.endsWith(".background")
					? basePath.substring(0, basePath.length() - ".background"
							.length())
					: basePath;
		}

		@Override
		public int status() {
			return status;
		}

		@Nullable
		@Override
		public String errorString() {
			return status == 0 ? "" : "fake error";
		}

		@Override
		public char[] seed(char[] seedOffset) {
			return background ? new char[0] : FAKE_SEED.toCharArray();
		}

		@Override
		public String address(long account, long subaddress) {
			String h = Integer.toHexString(walletBase().hashCode());
			StringBuilder sb = new StringBuilder();
			sb.append(subaddress == 0 ? "4" : "8").append(account)
					.append(subaddress).append(h);
			while (sb.length() < 95) sb.append('0');
			return sb.toString();
		}

		@Override
		public void addSubaddress(long account, String label) {
			subaddresses++;
		}

		@Override
		public long numSubaddresses(long account) {
			return subaddresses;
		}

		@Override
		public boolean init(String d, String p, boolean t) {
			return true;
		}

		@Override
		public void setRefreshFromHeight(long height) {
		}

		@Override
		public boolean refresh() {
			return true;
		}

		@Override
		public void setAutoRefreshInterval(int millis) {
		}

		@Override
		public void startRefresh() {
		}

		@Override
		public void pauseRefresh() {
		}

		@Override
		public long blockchainHeight() {
			return 0;
		}

		@Override
		public long daemonHeight() {
			return 0;
		}

		@Override
		public boolean isSynchronized() {
			return false;
		}

		@Override
		public void stopRefresh() {
		}

		@Override
		public int connectionStatus() {
			return 1;
		}

		@Override
		public long balance(long account) {
			return 0;
		}

		@Override
		public long unlockedBalance(long account) {
			return 0;
		}

		@Override
		public java.util.List<XmrTxInfo> history() {
			return new java.util.ArrayList<>();
		}

		@Nullable
		public Prepared nextPrepared;
		public int prepareCalls;

		@Nullable
		@Override
		public Prepared prepare(String a, long amt, int pri, long acc) {
			prepareCalls++;
			return nextPrepared;
		}

		@Override
		public boolean waitRefreshIdle(long timeoutMs) {
			return !closed && refreshIdle;
		}

		@Override
		public java.util.List<XmrTxLookup> lookupTxs(
				java.util.List<String> txids, long timeoutMs) {
			return fakeLookup(txids);
		}

		@Override
		public boolean store(String path) {
			touch(path);
			touch(path + ".keys");
			return true;
		}

		boolean backgroundSyncing = false;

		@Override
		public boolean setupBackgroundSync(char[] walletPassword,
				char[] backgroundPassword) {
			touch(walletBase() + ".background");
			touch(walletBase() + ".background.keys");
			return true;
		}

		@Override
		public boolean startBackgroundSync() {
			backgroundSyncing = true;
			return true;
		}

		@Override
		public boolean stopBackgroundSync(char[] walletPassword) {
			backgroundSyncing = false;
			return true;
		}

		@Override
		public boolean isBackgroundSyncing() {
			return backgroundSyncing;
		}

		@Override
		public boolean isBackgroundWallet() {
			return background;
		}

		@Override
		public int backgroundSyncType() {
			return 2;
		}

		@Override
		public boolean setPassword(char[] password) {
			return true;
		}

		@Override
		public void closePersisting() {
			closed = true;
			closeCount++;
		}

		@Override
		public void close() {
			closed = true;
			closeCount++;
		}
	}
}
