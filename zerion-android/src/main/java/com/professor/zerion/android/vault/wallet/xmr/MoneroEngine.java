package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * The single interface the XMR wallet layer talks to, so the native
 * implementation is swappable and testable. Only the operations the XMR
 * lifecycle needs are exposed. Implementations forward to Monero's audited
 * code and never make a policy decision: authentication, session lifetime,
 * storage sealing and the send-authorization gate are enforced by the caller,
 * not here.
 *
 * The send flow is split on purpose: {@link #prepare} builds and signs a
 * transaction without relaying it and returns a handle whose fee/amount/txid
 * the UI shows for review; {@link #commit} relays a prepared transaction. The
 * caller must run its fresh, plan-bound authorization between the two. There is
 * no combined prepare-and-send operation.
 */
@NotNullByDefault
public interface MoneroEngine {

	boolean isAvailable();

	/** Opaque native wallet handle. Closing releases native resources. */
	interface Session extends AutoCloseable {
		int status();

		@Nullable
		String errorString();

		char[] seed(char[] seedOffset);

		/** account 0 / subaddress 0 is the primary address; never use it for
		 *  normal receiving. */
		String address(long account, long subaddress);

		void addSubaddress(long account, String label);

		long numSubaddresses(long account);

		boolean init(String daemonAddress, String proxyAddress,
				boolean trustedDaemon);

		void setRefreshFromHeight(long height);

		/**
		 * Mark the wallet as recovering from seed so a connect does not
		 * fast-forward an unscanned background wallet's refresh height to the
		 * daemon tip (which would skip all history before now). A no-op for
		 * engines that do not model this.
		 */
		default void setRecoveringFromSeed(boolean recovering) {
		}

		boolean refresh();

		/** Auto-refresh interval (ms) for the background refresh thread. */
		void setAutoRefreshInterval(int millis);

		/**
		 * Starts the background refresh thread so scanning does not block the
		 * caller. The scanned height ({@link #blockchainHeight()}) grows as it
		 * progresses, so the caller can poll it against {@link #daemonHeight()}
		 * to report truthful sync progress.
		 */
		void startRefresh();

		/** Pauses the background refresh thread (call before closing). */
		void pauseRefresh();

		long blockchainHeight();

		long daemonHeight();

		boolean isSynchronized();

		/**
		 * Interrupts a running {@link #refresh()} once so it returns promptly.
		 * Safe to call from another thread; used by the lock path so a vault
		 * lock is not held up by an in-flight refresh before the handle closes.
		 */
		void stopRefresh();

		/** 0 disconnected, 1 connected, 2 wrong-version. */
		int connectionStatus();

		long balance(long account);

		long unlockedBalance(long account);

		/**
		 * Read-only, validated snapshot of the wallet's transaction history.
		 * Refreshes history from the wallet cache and copies each entry into an
		 * immutable {@link XmrTxInfo}. Malformed native rows are dropped. No send
		 * or transaction-construction capability is exposed here.
		 */
		java.util.List<XmrTxInfo> history();

		/** Build + sign without relaying. Returns a review handle or null. */
		@Nullable
		Prepared prepare(String address, long amountAtomic, int priority,
				long account);

		/**
		 * Bounded probe that the background refresh thread is idle. True only
		 * when idle was observed; false on timeout or a closed session. The
		 * caller pauses and interrupts refresh first and never constructs a
		 * transaction on false.
		 */
		boolean waitRefreshIdle(long timeoutMs);

		/**
		 * Factual per-txid lookup on the wallet's configured daemon over its
		 * existing connection (no failover, no retry). One result per input,
		 * in input order; see {@link XmrTxLookup}.
		 */
		java.util.List<XmrTxLookup> lookupTxs(java.util.List<String> txids,
				long timeoutMs);

		boolean store(String path);

		/**
		 * Configure Monero background sync so the private spend key at rest is
		 * encrypted under {@code walletPassword} while a separate view-only
		 * background keys file, encrypted under {@code backgroundPassword},
		 * drives unattended sync with no spend key in memory.
		 */
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

		/** True when this session opened a spend-keyless background keys file. */
		default boolean isBackgroundWallet() {
			return false;
		}

		default int backgroundSyncType() {
			return 0;
		}

		/** Re-encrypt the wallet keys under a new password on the next store. */
		default boolean setPassword(char[] password) {
			return false;
		}

		/**
		 * Flush the scan cache and close, in that order, with the refresh thread
		 * stopped first (wallet2 {@code closeWallet(store=true)}). Used on the
		 * lock/close path so scanning can resume from the persisted height next
		 * time. {@link #close()} closes WITHOUT persisting (error/discard paths).
		 */
		void closePersisting();

		@Override
		void close();
	}

	/** A built, signed, not-yet-relayed transaction. Relayed only via commit. */
	/** Mainnet destination classification; validity comes from Monero's parser. */
	enum AddressKind { INVALID, STANDARD, SUBADDRESS, INTEGRATED }

	interface Prepared extends AutoCloseable {
		int status();

		@Nullable
		String errorString();

		long feeAtomic();

		long amountAtomic();

		String txId();

		/** All txids in wallet2 order; empty on failure. Must match txCount. */
		java.util.List<String> txIds();

		/** Number of transactions the send was split into; {@code LONG_ERR} on
		 *  failure. */
		long txCount();

		/** Dust already included in fee (informational); {@code LONG_ERR} on
		 *  failure. */
		long dustAtomic();

		/** Total change returned to the wallet; 0 on a sweep, {@code LONG_ERR}
		 *  on error. amount + fee + change is the consumed-input total. */
		long changeAtomic();

		/** Relay the exact reviewed transaction. */
		boolean commit();

		/**
		 * Whether this wrapper has been closed. A pure Java flag read that makes
		 * no native call, so the send gate can verify liveness before it
		 * dereferences the native transaction.
		 */
		boolean isDisposed();

		@Override
		void close();
	}

	/** Create a brand-new wallet at path. The password is the native wallet-file
	 *  password (a random per-session value), not any Zerion credential. */
	@Nullable
	Session create(String path, char[] password, String language);

	/** Restore from a 25-word seed with a restore height. */
	@Nullable
	Session restore(String path, char[] password, char[] seed,
			long restoreHeight, char[] seedOffset);

	/** Open an existing wallet file. */
	@Nullable
	Session open(String path, char[] password);

	boolean validateAddress(String address);

	/** Classification by Monero's parser (mainnet); INVALID when not parseable. */
	AddressKind addressKind(String address);
}
