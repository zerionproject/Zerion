package com.professor.zerion.android.vault.ui;

import android.app.Application;
import android.content.Context;

import com.professor.zerion.R;
import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.crypto.ProtectedNoteCodec;
import com.professor.zerion.android.vault.model.PasswordEntry;
import com.professor.zerion.android.vault.model.PasswordEntryCodec;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.utils.SecureMemory;
import com.professor.zerion.android.vault.wallet.WalletCoin;
import com.professor.zerion.android.vault.wallet.WalletRecord;
import com.professor.zerion.android.vault.wallet.WalletStore;
import com.professor.zerion.android.vault.wallet.btc.BtcNodeRouting;
import com.professor.zerion.android.vault.wallet.btc.BtcWallet;
import com.professor.zerion.android.vault.wallet.btc.BtcWalletSettings;
import com.professor.zerion.android.vault.wallet.btc.ElectrumClient;
import com.professor.zerion.android.vault.wallet.btc.SilentPaymentCoins;
import com.professor.zerion.android.vault.wallet.btc.WalletCredential;

import org.bitcoinj.crypto.MnemonicCode;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import androidx.annotation.StringRes;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.plugin.TorSocksPort;

@NotNullByDefault
public class VaultViewModel extends AndroidViewModel {

	private static final Map<String, Integer> WALLET_ERRORS = new HashMap<>();

	static {
		WALLET_ERRORS.put(BtcWallet.ERR_FROZEN_COIN, R.string.wallet_err_frozen_coin);
		WALLET_ERRORS.put(BtcWallet.ERR_COIN_GONE, R.string.wallet_err_coin_gone);
		WALLET_ERRORS.put(BtcWallet.ERR_INVALID_ADDRESS, R.string.wallet_err_invalid_address);
		WALLET_ERRORS.put(BtcWallet.ERR_AMOUNT_RANGE, R.string.wallet_err_amount_range);
		WALLET_ERRORS.put(BtcWallet.ERR_NO_COINS, R.string.wallet_err_no_coins);
		WALLET_ERRORS.put(BtcWallet.ERR_BALANCE_AFTER_FEE, R.string.wallet_err_balance_after_fee);
		WALLET_ERRORS.put(BtcWallet.ERR_DUST, R.string.wallet_err_dust);
		WALLET_ERRORS.put(BtcWallet.ERR_INSUFFICIENT, R.string.wallet_err_insufficient);
		WALLET_ERRORS.put(BtcWallet.ERR_FEE_TOO_LOW, R.string.wallet_err_fee_too_low);
		WALLET_ERRORS.put(BtcWallet.ERR_ORACLE_UNREACHABLE, R.string.wallet_err_oracle_unreachable);
		WALLET_ERRORS.put(BtcWallet.ERR_SP_SELF_CHECK, R.string.wallet_err_sp_self_check);
		WALLET_ERRORS.put(BtcWallet.ERR_SP_NONE, R.string.wallet_err_sp_none);
		WALLET_ERRORS.put(BtcWallet.ERR_AMOUNT_AFTER_FEE, R.string.wallet_err_amount_after_fee);
	}

	public enum VaultState {
		NOT_CREATED,
		LOCKED,
		UNLOCKED
	}

	private final VaultManager vaultManager;
	private final Executor dbExecutor;
	private static final Executor THUMB_DECODE_EXECUTOR =
			java.util.concurrent.Executors.newSingleThreadExecutor();

	private final MutableLiveData<VaultState> vaultState = new MutableLiveData<>();
	private final MutableLiveData<List<VaultItem>> vaultItems = new MutableLiveData<>();
	private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
	private final MutableLiveData<String> successMessage = new MutableLiveData<>();
	private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
	private final MutableLiveData<Integer> progressPercent = new MutableLiveData<>(0);
	private final MutableLiveData<String> progressMessage = new MutableLiveData<>();

	private static final Executor WALLET_EXECUTOR =
			Executors.newSingleThreadExecutor();
	private static final Executor DERIVE_EXECUTOR =
			Executors.newSingleThreadExecutor();
	private static final Executor CRYPTO_EXECUTOR =
			Executors.newSingleThreadExecutor();

	private final WalletStore walletStore;
	private final int torSocksPort;
	private final BtcWalletSettings walletSettings;
	private final BtcNodeRouting nodeRouting;
	private final WalletCredential walletCredential;

	private final MutableLiveData<List<WalletRecord>> wallets =
			new MutableLiveData<>(new ArrayList<>());
	private final MutableLiveData<Long> btcBalanceSat = new MutableLiveData<>();
	private final MutableLiveData<String> btcReceiveAddress = new MutableLiveData<>();
	private final MutableLiveData<List<BtcWallet.TxSummary>> btcHistory =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> walletBusy = new MutableLiveData<>(false);
	private final MutableLiveData<Event<String>> walletError = new MutableLiveData<>();
	private final MutableLiveData<Event<String>> btcTxid = new MutableLiveData<>();
	private final MutableLiveData<Double> btcFeeRate = new MutableLiveData<>();
	private final MutableLiveData<double[]> btcFeeOptions = new MutableLiveData<>();
	private final MutableLiveData<com.professor.zerion.android.vault.wallet.btc
			.BtcPrice.Rates> btcRates = new MutableLiveData<>();
	private final MutableLiveData<char[]> walletSeedReveal = new MutableLiveData<>();
	private final MutableLiveData<Boolean> walletGateGranted = new MutableLiveData<>();
	private final MutableLiveData<Boolean> walletGateBusy = new MutableLiveData<>(false);
	private final MutableLiveData<String> walletAuthState = new MutableLiveData<>();
	private volatile boolean walletSectionUnlocked = false;
	private volatile long walletAuthGeneration = -1;

	@Nullable
	private volatile BtcWallet openBtc;
	@Nullable
	private volatile java.util.Set<String> lastTxids;
	@Nullable
	private volatile Long lastBalance;
	@Nullable
	private volatile List<BtcWallet.TxSummary> lastSummaries;

	@Inject
	public VaultViewModel(Application application, VaultManager vaultManager,
			@DatabaseExecutor Executor dbExecutor, WalletStore walletStore,
			com.professor.zerion.android.vault.net.TorSocksGate torGate) {
		super(application);
		this.vaultManager = vaultManager;
		this.dbExecutor = dbExecutor;
		this.walletStore = walletStore;
		this.torSocksPort = torGate.port();
		this.walletSettings = new BtcWalletSettings(walletStore);
		this.nodeRouting = new BtcNodeRouting(walletSettings);
		this.walletCredential = new WalletCredential(walletSettings,
				android.os.SystemClock::elapsedRealtime);
		vaultManager.setOnLockListener(() -> {
			resetWalletSession();
			vaultState.postValue(VaultState.LOCKED);
		});
		refreshVaultState();
	}

	public LiveData<List<WalletRecord>> getWallets() {
		return wallets;
	}

	public LiveData<Long> getBtcBalanceSat() {
		return btcBalanceSat;
	}

	public LiveData<String> getBtcReceiveAddress() {
		return btcReceiveAddress;
	}

	public LiveData<List<BtcWallet.TxSummary>> getBtcHistory() {
		return btcHistory;
	}

	public LiveData<Boolean> getWalletBusy() {
		return walletBusy;
	}

	public LiveData<Event<String>> getWalletError() {
		return walletError;
	}

	public LiveData<Event<String>> getBtcTxid() {
		return btcTxid;
	}

	public LiveData<Double> getBtcFeeRate() {
		return btcFeeRate;
	}

	public void loadWallets() {
		WALLET_EXECUTOR.execute(() -> {
			try {
				postBtcWallets();
			} catch (Exception e) {
				walletError.postValue(new Event<>(
						getApplication().getString(R.string.wallet_load_failed)));
			}
		});
	}

	private void postBtcWallets() throws Exception {
		List<WalletRecord> btc = new java.util.ArrayList<>();
		for (WalletRecord w : walletStore.listWallets()) {
			if (w.coin == com.professor.zerion.android.vault.wallet
					.WalletCoin.BTC) {
				String display = walletSettings.displayName(w.id, w.name);
				btc.add(display.equals(w.name) ? w
						: new WalletRecord(w.id, w.coin, display,
								w.createdTimestamp, w.hasPassword));
			}
		}
		wallets.postValue(btc);
	}

	public void createBtcWallet(String name, @Nullable char[] password) {
		walletBusy.postValue(true);
		CRYPTO_EXECUTOR.execute(() -> {
			char[] mnemonic = null;
			try {
				mnemonic = generateMnemonic();
				walletStore.createWallet(WalletCoin.BTC, name, mnemonic, password);
				postBtcWallets();
				walletSeedReveal.postValue(mnemonic.clone());
			} catch (Throwable e) {
				walletError.postValue(new Event<>(
						getApplication().getString(R.string.wallet_create_failed)));
			} finally {
				if (mnemonic != null) {
					SecureMemory.shred(mnemonic);
				}
				if (password != null) {
					SecureMemory.shred(password);
				}
				walletBusy.postValue(false);
			}
		});
	}

	public void importBtcWallet(String name, char[] mnemonic,
			@Nullable char[] password) {
		walletBusy.postValue(true);
		CRYPTO_EXECUTOR.execute(() -> {
			try {
				com.professor.zerion.android.vault.wallet.btc.Bip39Seed
						.check(mnemonic);
				walletStore.createWallet(WalletCoin.BTC, name, mnemonic, password);
				postBtcWallets();
			} catch (Throwable e) {
				walletError.postValue(new Event<>(
						getApplication().getString(R.string.wallet_import_invalid)));
			} finally {
				SecureMemory.shred(mnemonic);
				if (password != null) {
					SecureMemory.shred(password);
				}
				walletBusy.postValue(false);
			}
		});
	}

	public LiveData<double[]> getBtcFeeOptions() {
		return btcFeeOptions;
	}

	public LiveData<com.professor.zerion.android.vault.wallet.btc.BtcPrice.Rates>
			getBtcRates() {
		return btcRates;
	}

	private static final long PRICE_TTL_MS = 15L * 60L * 1000L;
	private final MutableLiveData<Boolean> priceStale =
			new MutableLiveData<>(false);

	public LiveData<Boolean> getPriceStale() {
		return priceStale;
	}

	public void loadPrice() {
		WALLET_EXECUTOR.execute(() -> {
			try {
				String id = currentWalletId;
				String priceTag = com.professor.zerion.android.vault.wallet.btc
						.TorIsolation.price(id != null ? id : "session");
				com.professor.zerion.android.vault.wallet.btc.BtcPrice.Rates r =
						com.professor.zerion.android.vault.wallet.btc.BtcPrice
								.fetch(torSocksPort, priceTag);
				boolean usable = r != null && !r.isEmpty();
				if (usable) {
					walletSettings.saveRates(r);
					priceStale.postValue(false);
					btcRates.postValue(r);
				}
			} catch (Throwable e) {
			}
		});
	}

	public void loadCachedPrice() {
		CRYPTO_EXECUTOR.execute(() -> {
			try {
				BtcWalletSettings.CachedPrice price =
						walletSettings.cachedPrice();
				String cached = price.ratesJson;
				long at = price.at;
				boolean found = !cached.isEmpty();
				if (found) {
					com.professor.zerion.android.vault.wallet.btc.BtcPrice.Rates r =
							com.professor.zerion.android.vault.wallet.btc.BtcPrice
									.Rates.fromJson(cached);
					if (!r.isEmpty()) {
						boolean stale = com.professor.zerion.android.vault
								.wallet.btc.FiatDisplay.isStale(at,
										System.currentTimeMillis(), PRICE_TTL_MS);
						priceStale.postValue(stale);
						btcRates.postValue(r);
						return;
					}
				}
			} catch (Throwable ignored) {
			}
		});
	}

	private final java.util.concurrent.atomic.AtomicInteger receiveIndex =
			new java.util.concurrent.atomic.AtomicInteger(0);

	public void newReceiveAddress() {
		BtcWallet w = openBtc;
		String id = currentWalletId;
		if (w == null) {
			return;
		}
		DERIVE_EXECUTOR.execute(() -> {
			try {
				int lastUsed = w.lastReceiveUsedIndex();
				int idx = receiveIndex.updateAndGet(cur -> {
					int next = cur + 1;
					if (lastUsed >= 0) {
						int cap = lastUsed + BtcWallet.GAP_LIMIT;
						if (next > cap) {
							next = cap;
						}
					}
					return Math.max(next, cur);
				});
				w.setMinReceiveProbe(idx);
				btcReceiveAddress.postValue(w.receiveAddressAt(idx));
				if (id != null && id.equals(currentWalletId) && openBtc == w) {
					walletSettings.raiseReceiveIndex(id, idx);
				}
			} catch (Throwable ignored) {
			}
		});
	}

	public void ensureReceiveAddress() {
		BtcWallet w = openBtc;
		if (w == null) {
			return;
		}
		DERIVE_EXECUTOR.execute(() -> {
			try {
				btcReceiveAddress.postValue(
						w.receiveAddressAt(receiveIndex.get()));
			} catch (Throwable ignored) {
			}
		});
	}

	private final MutableLiveData<Boolean> walletOnline =
			new MutableLiveData<>(true);

	public LiveData<Boolean> getWalletOnline() {
		return walletOnline;
	}

	private final MutableLiveData<String> walletCurrency =
			new MutableLiveData<>();

	public LiveData<String> getWalletCurrency() {
		return walletCurrency;
	}

	public void loadWalletCurrency() {
		WALLET_EXECUTOR.execute(() ->
				walletCurrency.postValue(walletSettings.currency()));
	}

	public void saveWalletCurrency(String cur) {
		WALLET_EXECUTOR.execute(() -> walletSettings.saveCurrency(cur));
	}

	public static final String DEFAULT_NODE = BtcNodeRouting.DEFAULT_NODE;

	public static final String DEFAULT_ONION_NODE =
			BtcNodeRouting.DEFAULT_ONION_NODE;

	public static final String DEFAULT_ONION_NODE_2 =
			BtcNodeRouting.DEFAULT_ONION_NODE_2;

	public static String preferredDefaultNode() {
		return BtcNodeRouting.preferredDefaultNode();
	}

	private final MutableLiveData<String> walletPinPrompt =
			new MutableLiveData<>();

	public LiveData<String> getWalletPinPrompt() {
		return walletPinPrompt;
	}

	public void captureNodePin(String node) {
		WALLET_EXECUTOR.execute(() -> {
			try {
				com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
						ep = nodeRouting.endpointFor(node);
				if (!ep.tls()) {
					walletPinPrompt.postValue(node + "|none");
					return;
				}
				ElectrumClient.CapturedCert cert =
						ElectrumClient.captureCert(ep, torSocksPort);
				walletPinPrompt.postValue(node + "|"
						+ (cert.caValid ? "ca" : "self") + "|" + cert.sha256);
			} catch (Throwable e) {
				walletPinPrompt.postValue(node + "|error");
			}
		});
	}

	public void confirmNodePin(String node, String pin) {
		WALLET_EXECUTOR.execute(() -> nodeRouting.savePin(node, pin));
	}

	private final MutableLiveData<java.util.List<String>> walletNodeList =
			new MutableLiveData<>();
	private final MutableLiveData<String> walletSelectedNode =
			new MutableLiveData<>();
	private final MutableLiveData<String> walletNodeHealth =
			new MutableLiveData<>();

	public LiveData<java.util.List<String>> getWalletNodeList() {
		return walletNodeList;
	}

	public LiveData<String> getWalletSelectedNode() {
		return walletSelectedNode;
	}

	public LiveData<String> getWalletNodeHealth() {
		return walletNodeHealth;
	}

	public void loadNodes() {
		CRYPTO_EXECUTOR.execute(() -> {
			BtcNodeRouting.NodeList nodes = nodeRouting.nodeList();
			walletNodeList.postValue(nodes.nodes);
			walletSelectedNode.postValue(nodes.selected);
		});
	}

	public void addNode(String node) {
		WALLET_EXECUTOR.execute(() -> {
			nodeRouting.addNode(node);
			loadNodes();
		});
	}

	public void selectNode(String node) {
		WALLET_EXECUTOR.execute(() -> {
			nodeRouting.selectNode(node);
			reopenActiveWalletEndpoints();
			loadNodes();
		});
	}

	private void reopenActiveWalletEndpoints() {
		BtcWallet w = openBtc;
		String id = currentWalletId;
		if (w == null || id == null) {
			return;
		}
		try {
			com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
					scanEp = nodeRouting.scanEndpoint(id);
			com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
					bcastEp = nodeRouting.broadcastEndpoint(id, scanEp);
			java.util.List<com.professor.zerion.android.vault.wallet.btc
					.ElectrumEndpoint> fbs = nodeRouting.fallbacks(id, scanEp);
			w.updateEndpoints(scanEp, bcastEp, fbs, fbs);
			scanOpenBtc(true);
		} catch (Throwable ignored) {
		}
	}

	public static final String ROUTING_TOR = BtcNodeRouting.ROUTING_TOR;
	public static final String ROUTING_DIRECT = BtcNodeRouting.ROUTING_DIRECT;
	public static final String ROUTING_LOCAL = BtcNodeRouting.ROUTING_LOCAL;

	public String readWalletRouting(String walletId) {
		return nodeRouting.routing(walletId);
	}

	public void setWalletRouting(String walletId, String mode) {
		WALLET_EXECUTOR.execute(() -> {
			nodeRouting.setRouting(walletId, mode);
			reopenActiveWalletEndpoints();
		});
	}

	public void deleteNode(String node) {
		if (node.equals(preferredDefaultNode())) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			nodeRouting.deleteNode(node);
			loadNodes();
		});
	}

	public void checkNode(String node) {
		WALLET_EXECUTOR.execute(() -> {
			boolean ok = false;
			try {
				try (ElectrumClient c = new ElectrumClient(
						nodeRouting.endpointFor(node), torSocksPort,
						com.professor.zerion.android.vault.wallet.btc
								.TorIsolation.ephemeral("nodecheck"))) {
					ok = c.blockHeight() > 0;
				}
			} catch (Throwable ignored) {
			}
			walletNodeHealth.postValue(node + "|" + (ok ? "ok" : "fail"));
		});
	}

	public void renameBtcWallet(String id, String newName, char[] password) {
		walletBusy.postValue(true);
		WALLET_EXECUTOR.execute(() -> {
			char[] mnemonic = null;
			try {
				mnemonic = walletStore.loadMnemonicChars(id, password);
				walletSettings.saveDisplayName(id, newName);
				postBtcWallets();
			} catch (Throwable e) {
				walletError.postValue(new Event<>(getApplication().getString(
						isWrongPassword(e) ? R.string.wallet_wrong_password
								: R.string.wallet_create_failed)));
			} finally {
				if (mnemonic != null) {
					SecureMemory.shred(mnemonic);
				}
				SecureMemory.shred(password);
				walletBusy.postValue(false);
			}
		});
	}

	public void changeBtcWalletPassword(String id, String name, char[] oldPw,
			char[] newPw) {
		walletBusy.postValue(true);
		WALLET_EXECUTOR.execute(() -> {
			char[] mnemonic = null;
			try {
				mnemonic = walletStore.loadMnemonicChars(id, oldPw);
				String newId = walletStore.createWallet(WalletCoin.BTC, name,
						mnemonic, newPw);
				replaceWalletItem(id, newId);
				postBtcWallets();
			} catch (Throwable e) {
				walletError.postValue(new Event<>(getApplication().getString(
						isWrongPassword(e) ? R.string.wallet_wrong_password
								: R.string.wallet_create_failed)));
			} finally {
				if (mnemonic != null) {
					SecureMemory.shred(mnemonic);
				}
				SecureMemory.shred(newPw);
				SecureMemory.shred(oldPw);
				walletBusy.postValue(false);
			}
		});
	}

	private static final int MAX_SP_BLOCKS_PER_SCAN = 1000;
	public static final String DEFAULT_SP_ORACLE =
			"https://silentpayments.dev/blindbit/mainnet";
	private final MutableLiveData<String> spAddress = new MutableLiveData<>();
	private final MutableLiveData<long[]> spInfo = new MutableLiveData<>();
	private final MutableLiveData<Boolean> spBusy = new MutableLiveData<>(false);
	private final MutableLiveData<Event<String>> spTxid = new MutableLiveData<>();
	private final SilentPaymentCoins spCoins = new SilentPaymentCoins();

	public LiveData<String> getSpAddress() {
		return spAddress;
	}

	public LiveData<long[]> getSpInfo() {
		return spInfo;
	}

	public LiveData<Boolean> getSpBusy() {
		return spBusy;
	}

	public LiveData<Event<String>> getSpTxid() {
		return spTxid;
	}

	public void loadSp() {
		WALLET_EXECUTOR.execute(() -> {
			try {
				BtcWallet w = openBtc;
				String id = currentWalletId;
				if (w != null) {
					spAddress.postValue(w.silentPaymentAddress());
				}
				if (id == null) {
					return;
				}
				org.json.JSONObject o = walletSettings.privacy(id);
				spCoins.load(id, o.optJSONArray("spUtxos"));
				postSpInfo(o);
			} catch (Throwable ignored) {
			}
		});
	}

	private void postSpInfo(org.json.JSONObject o) {
		long[] totals = spCoins.totals();
		int scanned = o.optInt("spScanned", 0);
		int birthday = o.optInt("spBirthday", 0);
		int oracleSet = o.optString("spOracle", "").isEmpty() ? 0 : 1;
		spInfo.postValue(new long[]{totals[0], totals[1], scanned, oracleSet,
				birthday});
	}

	public void saveSpConfig(String oracle, int birthday) {
		String id = currentWalletId;
		if (id == null) {
			return;
		}
		if (oracle.trim().regionMatches(true, 0, "http://", 0, 7)) {
			walletError.postValue(new Event<>(getApplication()
					.getString(R.string.wallet_sp_oracle_https)));
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			try {
				walletSettings.mutatePrivacy(id, w -> {
					w.put("spOracle", oracle.trim());
					w.put("spBirthday", Math.max(birthday, 0));
					if (w.optInt("spScanned", 0) < birthday - 1) {
						w.put("spScanned", Math.max(birthday - 1, 0));
					}
				});
				postSpInfo(walletSettings.privacy(id));
			} catch (Throwable ignored) {
			}
		});
	}

	public void scanSp() {
		BtcWallet w = openBtc;
		if (w == null) {
			return;
		}
		String id = currentWalletId;
		if (id == null || !isSpEnabled(id)) {
			return;
		}
		spBusy.postValue(true);
		WALLET_EXECUTOR.execute(() -> {
			try {
				org.json.JSONObject o = walletSettings.privacy(id);
				String oracle = o.optString("spOracle", "");
				if (oracle.isEmpty()) {
					oracle = DEFAULT_SP_ORACLE;
				}
				int birthday = o.optInt("spBirthday", 0);
				int scanned = o.optInt("spScanned", 0);
				int from = Math.max(Math.max(scanned + 1, birthday), 1);
				BtcWallet.SpScanResult r = w.scanSilentPayments(oracle, from,
						MAX_SP_BLOCKS_PER_SCAN);
				if (openBtc != w || !id.equals(currentWalletId)) {
					return;
				}
				org.json.JSONArray coins = spCoins.addFound(id, r.found);
				if (coins == null) {
					return;
				}
				walletSettings.mutatePrivacy(id, wp -> {
					wp.put("spScanned", r.scannedTo);
					wp.put("spUtxos", coins);
				});
				postSpInfo(walletSettings.privacy(id));
			} catch (Throwable e) {
				walletError.postValue(new Event<>(
						walletErrorText(e, R.string.wallet_network_failed)));
			} finally {
				spBusy.postValue(false);
			}
		});
	}

	public static final class SpSweepReview {
		public final String toAddress;
		public final long amountSat;
		public final long feeSat;
		public final int inputCount;
		public final String fingerprint;

		SpSweepReview(BtcWallet.SpSweepPlan p) {
			this.toAddress = p.toAddress;
			this.amountSat = p.amountSat;
			this.feeSat = p.feeSat;
			this.inputCount = p.outpoints.size();
			this.fingerprint = p.fingerprint;
		}
	}

	private final com.professor.zerion.android.vault.wallet.btc.SpSweepGate
			spSweepGate =
			new com.professor.zerion.android.vault.wallet.btc.SpSweepGate();
	private final MutableLiveData<Event<SpSweepReview>> spSweepReview =
			new MutableLiveData<>();

	public LiveData<Event<SpSweepReview>> getSpSweepReview() {
		return spSweepReview;
	}

	public void prepareSpSweep(String toAddress, double feeRate) {
		BtcWallet w = openBtc;
		if (w == null) return;
		spBusy.postValue(true);
		WALLET_EXECUTOR.execute(() -> {
			try {
				BtcWallet.SpSweepPlan plan = w.planSweepSilentPayments(
						spCoins.snapshot(w.walletId()), toAddress, feeRate);
				spSweepGate.prepare(plan, w.walletId());
				spSweepReview.postValue(new Event<>(new SpSweepReview(plan)));
			} catch (Throwable e) {
				walletError.postValue(new Event<>(
						walletErrorText(e, R.string.wallet_send_failed)));
			} finally {
				spBusy.postValue(false);
			}
		});
	}

	public void cancelSpSweep() {
		WALLET_EXECUTOR.execute(spSweepGate::clear);
	}

	public void authorizeSpSweep(char[] credential, String reviewedFingerprint) {
		if (!sending.compareAndSet(false, true)) {
			SecureMemory.shred(credential);
			return;
		}
		BtcWallet w = openBtc;
		if (w == null) {
			SecureMemory.shred(credential);
			sending.set(false);
			return;
		}
		spBusy.postValue(true);
		WALLET_EXECUTOR.execute(() -> {
			try {
				boolean authed = verifyWalletCredential(credential);
				BtcWallet.SpSweepPlan plan;
				try {
					plan = spSweepGate.authorize(reviewedFingerprint, authed,
							w.walletId());
				} catch (com.professor.zerion.android.vault.wallet.btc.SendGate
						.AuthorizationException e) {
					walletError.postValue(new Event<>(getApplication()
							.getString(R.string.wallet_auth_send_failed)));
					return;
				}
				if (!walletSessionValid()) {
					spSweepGate.clear();
					walletError.postValue(new Event<>(getApplication()
							.getString(R.string.wallet_send_failed)));
					return;
				}
				String id = currentWalletId;
				String txid = w.signSpSweep(plan);
				spCoins.spent();
				if (id != null) {
					walletSettings.mutatePrivacy(id, wp ->
							wp.put("spUtxos", new org.json.JSONArray()));
					postSpInfo(walletSettings.privacy(id));
				}
				spTxid.postValue(new Event<>(txid));
				scanOpenBtc(true);
			} catch (com.professor.zerion.android.vault.wallet.btc
					.BroadcastUncertainException e) {
				walletError.postValue(new Event<>(getApplication()
						.getString(R.string.wallet_send_uncertain)));
				scanOpenBtc(true);
			} catch (Throwable e) {
				walletError.postValue(new Event<>(
						walletErrorText(e, R.string.wallet_send_failed)));
			} finally {
				spBusy.postValue(false);
				sending.set(false);
			}
		});
	}

	public void loadFeeOptions() {
		BtcWallet w = openBtc;
		if (w == null) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			try {
				btcFeeOptions.postValue(w.feeOptions());
			} catch (Throwable ignored) {
			}
		});
	}

	public LiveData<Boolean> getWalletGateGranted() {
		return walletGateGranted;
	}

	public LiveData<Boolean> getWalletGateBusy() {
		return walletGateBusy;
	}

	public void clearWalletGate() {
		walletGateGranted.postValue(null);
	}

	public LiveData<String> getWalletAuthState() {
		return walletAuthState;
	}

	public void clearWalletAuthState() {
		walletAuthState.postValue(null);
	}

	boolean walletSessionValid() {
		return com.professor.zerion.android.vault.wallet.WalletSessionGuard.valid(
				vaultManager.isUnlocked(), walletSectionUnlocked,
				walletAuthGeneration, vaultManager.getLockGeneration());
	}

	public void resetWalletSession() {
		walletSectionUnlocked = false;
		walletAuthGeneration = -1;
		closeBtcWallet();
	}

	public void beginWalletAccess() {
		if (walletSessionValid()) {
			walletGateGranted.postValue(true);
			return;
		}
		resetWalletSession();
		CRYPTO_EXECUTOR.execute(() -> {
			boolean hasAuth = walletCredential.exists();
			walletAuthState.postValue(hasAuth ? "verify" : "setup");
		});
	}

	public void setupWalletAuth(String type, char[] credential) {
		walletGateBusy.postValue(true);
		CRYPTO_EXECUTOR.execute(() -> {
			try {
				walletCredential.setup(type, credential);
				walletSectionUnlocked = true;
				walletAuthGeneration = vaultManager.getLockGeneration();
				walletGateGranted.postValue(true);
			} catch (Throwable e) {
				walletError.postValue(new Event<>(getApplication().getString(
						R.string.wallet_open_failed)));
			} finally {
				SecureMemory.shred(credential);
				walletGateBusy.postValue(false);
			}
		});
	}

	public boolean isWalletAuthPin() {
		return walletCredential.isPin();
	}

	public void verifyWalletAuth(char[] credential) {
		walletGateBusy.postValue(true);
		CRYPTO_EXECUTOR.execute(() -> {
			boolean ok = verifyWalletCredential(credential);
			if (ok) {
				walletSectionUnlocked = true;
				walletAuthGeneration = vaultManager.getLockGeneration();
			} else if (walletCredential.isThrottled()) {
				walletError.postValue(new Event<>(getApplication().getString(
						R.string.wallet_auth_too_many)));
			}
			walletGateGranted.postValue(ok);
			walletGateBusy.postValue(false);
		});
	}

	@Nullable
	private volatile String currentWalletId;

	private final MutableLiveData<Boolean> walletExtremeMode =
			new MutableLiveData<>();

	public LiveData<Boolean> getWalletExtremeMode() {
		return walletExtremeMode;
	}

	public boolean isExtremeMode(String walletId) {
		return walletSettings.privacy(walletId).optBoolean("extremeMode", false);
	}

	public void setExtremeMode(boolean on) {
		String id = currentWalletId;
		if (id == null) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			walletSettings.mutatePrivacy(id, w -> w.put("extremeMode", on));
			BtcWallet bw = openBtc;
			if (bw != null) {
				bw.setPrivacyPolicy(on
						? com.professor.zerion.android.vault.wallet.btc.privacy
								.PrivacyEngine.Policy.STRICT
						: com.professor.zerion.android.vault.wallet.btc.privacy
								.PrivacyEngine.Policy.STANDARD);
			}
			walletExtremeMode.postValue(on);
		});
	}

	private final MutableLiveData<Boolean> walletSpEnabled =
			new MutableLiveData<>();

	public LiveData<Boolean> getWalletSpEnabled() {
		return walletSpEnabled;
	}

	public boolean isSpEnabled(String walletId) {
		return walletSettings.privacy(walletId).optBoolean("spEnabled", false);
	}

	public void setSpEnabled(boolean on) {
		String id = currentWalletId;
		if (id == null) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			walletSettings.mutatePrivacy(id, w -> w.put("spEnabled", on));
			BtcWallet bw = openBtc;
			if (bw != null) {
				bw.setSilentPaymentsEnabled(on);
			}
			walletSpEnabled.postValue(on);
		});
	}

	private final MutableLiveData<java.util.List<
			com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyMeta>>
			walletCoins = new MutableLiveData<>();

	public LiveData<java.util.List<
			com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyMeta>>
			getWalletCoins() {
		return walletCoins;
	}

	public void loadCoins() {
		WALLET_EXECUTOR.execute(() -> {
			BtcWallet w = openBtc;
			if (w == null) {
				return;
			}
			try {
				walletCoins.postValue(w.coinControl());
			} catch (Throwable e) {
				walletError.postValue(new Event<>(getApplication()
						.getString(R.string.wallet_network_failed)));
			}
		});
	}

	public void setUtxoFrozen(String outpoint, boolean frozen) {
		String id = currentWalletId;
		if (id == null) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			walletSettings.privacyStore(id).setFrozen(outpoint, frozen);
			loadCoins();
		});
	}

	public void setUtxoLabel(String outpoint, @Nullable String label) {
		String id = currentWalletId;
		if (id == null) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			walletSettings.privacyStore(id).setLabel(outpoint, label);
			loadCoins();
		});
	}

	public LiveData<char[]> getWalletSeedReveal() {
		return walletSeedReveal;
	}

	public void clearSeedReveal() {
		walletSeedReveal.postValue(null);
	}

	public void revealSeed(String walletId, char[] password) {
		CRYPTO_EXECUTOR.execute(() -> {
			char[] mnemonic = null;
			try {
				mnemonic = walletStore.loadMnemonicChars(walletId, password);
				walletSeedReveal.postValue(mnemonic.clone());
			} catch (Exception e) {
				walletError.postValue(new Event<>(getApplication().getString(
						isWrongPassword(e) ? R.string.wallet_wrong_password
								: R.string.wallet_open_failed)));
			} finally {
				if (mnemonic != null) {
					SecureMemory.shred(mnemonic);
				}
				if (password != null) {
					SecureMemory.shred(password);
				}
			}
		});
	}

	private static boolean isWrongPassword(Throwable e) {
		return e instanceof SecurityException
				|| (e.getMessage() != null
						&& e.getMessage().toLowerCase().contains("password"));
	}

	private final MutableLiveData<Event<String>> walletDeleted =
			new MutableLiveData<>();

	public LiveData<Event<String>> getWalletDeleted() {
		return walletDeleted;
	}

	public void deleteWalletWithAuth(String walletId, char[] credential) {
		WALLET_EXECUTOR.execute(() -> {
			boolean authed = verifyWalletCredential(credential);
			if (!authed) {
				walletError.postValue(new Event<>(getApplication().getString(
						R.string.wallet_wrong_password)));
				return;
			}
			try {
				if (walletId.equals(currentWalletId)) {
					closeBtcWallet();
				}
				walletStore.deleteWallet(walletId);
				walletSettings.removeWalletRecords(walletId);
				postBtcWallets();
				walletDeleted.postValue(new Event<>(walletId));
			} catch (Throwable e) {
				walletError.postValue(new Event<>(getApplication().getString(
						R.string.wallet_delete_failed)));
			}
		});
	}

	private void replaceWalletItem(String oldId, String newId) throws Exception {
		try {
			walletSettings.moveWalletRecords(oldId, newId);
		} catch (Throwable me) {
			walletStore.deleteWallet(newId);
			throw me;
		}
		walletStore.deleteWallet(oldId);
	}

	private final MutableLiveData<Event<String>> walletOpened =
			new MutableLiveData<>();

	public LiveData<Event<String>> getWalletOpened() {
		return walletOpened;
	}

	public void openBtcWallet(String walletId, @Nullable char[] password) {
		walletBusy.postValue(true);
		CRYPTO_EXECUTOR.execute(() -> {
			char[] mnemonic = null;
			try {
				if (!walletSessionValid()) {
					walletBusy.postValue(false);
					return;
				}
				mnemonic = walletStore.loadMnemonicChars(walletId, password);
				com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
						scanEp = nodeRouting.scanEndpoint(walletId);
				com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
						bcastEp = nodeRouting.broadcastEndpoint(walletId, scanEp);
				BtcWallet w = new BtcWallet(mnemonic, 0, torSocksPort,
						scanEp, bcastEp, walletId);
				SecureMemory.shred(mnemonic);
				mnemonic = null;
				w.setPendingLog(walletSettings.pendingLog(walletId));
				w.setPrivacyStore(walletSettings.privacyStore(walletId));
				boolean epm = isExtremeMode(walletId);
				w.setPrivacyPolicy(epm
						? com.professor.zerion.android.vault.wallet.btc.privacy
								.PrivacyEngine.Policy.STRICT
						: com.professor.zerion.android.vault.wallet.btc.privacy
								.PrivacyEngine.Policy.STANDARD);
				boolean spOn = isSpEnabled(walletId);
				w.setSilentPaymentsEnabled(spOn);
				java.util.List<com.professor.zerion.android.vault.wallet.btc
						.ElectrumEndpoint> fbs = nodeRouting.fallbacks(walletId, scanEp);
				w.setFallbacks(fbs, fbs);
				int persistedIndex = walletSettings.receiveIndex(walletId);
				w.setMinReceiveProbe(persistedIndex);
				w.setMinChangeProbe(walletSettings.changeIndex(walletId));
				String firstAddr = w.receiveAddressAt(persistedIndex);

				if (!walletSessionValid()) {
					w.close();
					walletBusy.postValue(false);
					return;
				}
				dropReviewedPlans();
				BtcWallet previous = openBtc;
				openBtc = w;
				if (previous != null && previous != w) previous.close();
				currentWalletId = walletId;
				lastTxids = null;
				lastSummaries = null;
				receiveIndex.set(persistedIndex);
				walletExtremeMode.postValue(epm);
				walletSpEnabled.postValue(spOn);
				btcReceiveAddress.postValue(firstAddr);

				walletOpened.postValue(new Event<>(walletId));
				WALLET_EXECUTOR.execute(() -> scanOpenBtc(true));
			} catch (Throwable e) {
				walletError.postValue(new Event<>(getApplication().getString(
						isWrongPassword(e) ? R.string.wallet_wrong_password
								: R.string.wallet_open_failed)));
				walletBusy.postValue(false);
			} finally {
				if (mnemonic != null) {
					SecureMemory.shred(mnemonic);
				}
				if (password != null) {
					SecureMemory.shred(password);
				}
			}
		});
	}

	public void refreshBtcWallet() {
		if (openBtc == null) {
			return;
		}
		walletBusy.postValue(true);
		WALLET_EXECUTOR.execute(() -> scanOpenBtc(true));
	}

	public void pollBtcWallet() {
		if (openBtc == null || scanInFlight.get()) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> scanOpenBtc(false));
	}

	void dropReviewedPlans() {
		sendGate.clear();
		spSweepGate.clear();
	}

	public void closeBtcWallet() {
		scanEpoch++;
		dropReviewedPlans();
		spCoins.clear();
		BtcWallet previous = openBtc;
		openBtc = null;
		if (previous != null) previous.close();
		currentWalletId = null;
		lastTxids = null;
		lastSummaries = null;
		walletBusy.postValue(false);
		btcBalanceSat.postValue(null);
		btcReceiveAddress.postValue(null);
		btcHistory.postValue(new ArrayList<>());
	}

	private final java.util.concurrent.atomic.AtomicBoolean sending =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	public static final class MergePrompt {
		public final String toAddress;
		public final long amountSat;
		public final double feeRate;
		public final boolean sweep;
		public final int clusterCount;

		MergePrompt(String toAddress, long amountSat, double feeRate,
				boolean sweep, int clusterCount) {
			this.toAddress = toAddress;
			this.amountSat = amountSat;
			this.feeRate = feeRate;
			this.sweep = sweep;
			this.clusterCount = clusterCount;
		}
	}

	private final MutableLiveData<MergePrompt> walletMergePrompt =
			new MutableLiveData<>();

	public LiveData<MergePrompt> getWalletMergePrompt() {
		return walletMergePrompt;
	}

	public static final class SendReview {
		public final String toAddress;
		public final long amountSat;
		public final long feeSat;
		public final boolean sweep;
		public final String fingerprint;
		public final double feeRateSatPerVb;
		public final int feePercentOfAmount;
		public final com.professor.zerion.android.vault.wallet.btc.privacy
				.PrivacyAnalyzer.Analysis analysis;

		SendReview(com.professor.zerion.android.vault.wallet.btc.BtcWallet
				.SendPlan p, com.professor.zerion.android.vault.wallet.btc
				.privacy.PrivacyAnalyzer.Analysis analysis) {
			this.toAddress = p.toAddress;
			this.amountSat = p.amountSat;
			this.feeSat = p.feeSat;
			this.sweep = p.sweep;
			this.fingerprint = p.fingerprint;
			this.feeRateSatPerVb = p.feeRateSatPerVb();
			this.feePercentOfAmount = p.feePercentOfAmount();
			this.analysis = analysis;
		}
	}

	private final com.professor.zerion.android.vault.wallet.btc.SendGate
			sendGate = new com.professor.zerion.android.vault.wallet.btc
			.SendGate();
	private final MutableLiveData<Event<SendReview>> walletSendReview =
			new MutableLiveData<>();

	public LiveData<Event<SendReview>> getWalletSendReview() {
		return walletSendReview;
	}

	private final MutableLiveData<Boolean> walletPreparing =
			new MutableLiveData<>(false);

	public LiveData<Boolean> getWalletPreparing() {
		return walletPreparing;
	}

	public void sendBtc(String toAddress, long amountSat, double feeRate,
			boolean sweep) {
		prepareSend(toAddress, amountSat, feeRate, sweep, null, false);
	}

	public void confirmSendMerge(MergePrompt p) {
		prepareSend(p.toAddress, p.amountSat, p.feeRate, p.sweep, null, true);
	}

	public void prepareSend(String toAddress, long amountSat, double feeRate,
			boolean sweep, @Nullable java.util.Set<String> manualOutpoints,
			boolean allowClusterMerge) {
		walletBusy.postValue(true);
		CRYPTO_EXECUTOR.execute(() -> {
			try {
				BtcWallet w = openBtc;
				if (w == null) {
					walletError.postValue(new Event<>(getApplication()
							.getString(R.string.wallet_open_failed)));
					return;
				}
				BtcWallet.ScanResult cached = w.cachedScan();
				BtcWallet.SendPlan plan;
				if (cached != null) {
					plan = w.planSend(cached, toAddress, amountSat,
							feeRate, sweep, manualOutpoints, allowClusterMerge);
				} else {
					walletPreparing.postValue(true);
					try {
						plan = w.planSend(toAddress, amountSat,
								feeRate, sweep, manualOutpoints, allowClusterMerge);
					} finally {
						walletPreparing.postValue(false);
					}
				}
				sendGate.prepare(plan, w.walletId());
				walletSendReview.postValue(new Event<>(
						new SendReview(plan, w.analyzePlan(plan))));
			} catch (com.professor.zerion.android.vault.wallet.btc.privacy
					.PrivacyMergeException e) {
				walletMergePrompt.postValue(new MergePrompt(toAddress, amountSat,
						feeRate, sweep, e.clusterCount));
			} catch (Throwable e) {
				walletError.postValue(new Event<>(
						walletErrorText(e, R.string.wallet_send_failed)));
			} finally {
				walletBusy.postValue(false);
			}
		});
	}

	public void cancelSend() {
		WALLET_EXECUTOR.execute(sendGate::clear);
	}

	public void authorizeAndSend(char[] credential, String reviewedFingerprint) {
		if (!sending.compareAndSet(false, true)) {
			SecureMemory.shred(credential);
			return;
		}
		walletBusy.postValue(true);
		WALLET_EXECUTOR.execute(() -> {
			BtcWallet.SendPlan plan = null;
			try {
				boolean authed = verifyWalletCredential(credential);
				BtcWallet w = openBtc;
				plan = sendGate.authorize(reviewedFingerprint, authed,
						w == null ? null : w.walletId());
				if (w == null) {
					walletError.postValue(new Event<>(getApplication()
							.getString(R.string.wallet_open_failed)));
					return;
				}
				if (!walletSessionValid()) {
					sendGate.clear();
					walletError.postValue(new Event<>(getApplication()
							.getString(R.string.wallet_send_failed)));
					walletBusy.postValue(false);
					return;
				}
				String txid = w.signPlan(plan);
				walletSettings.raiseChangeIndex(w.walletId(), w.minChangeProbe());
				w.invalidateCachedScan();
				btcTxid.postValue(new Event<>(txid));
				postLocalTxState(w, plan.netSat);
				scanOpenBtc(true);
			} catch (com.professor.zerion.android.vault.wallet.btc.SendGate
					.AuthorizationException e) {
				walletError.postValue(new Event<>(getApplication()
						.getString(R.string.wallet_auth_send_failed)));
				walletBusy.postValue(false);
			} catch (com.professor.zerion.android.vault.wallet.btc
					.BroadcastUncertainException e) {
				BtcWallet w = openBtc;
				if (w != null && plan != null) {
					walletSettings.raiseChangeIndex(w.walletId(),
							w.minChangeProbe());
					w.invalidateCachedScan();
					postLocalTxState(w, plan.netSat);
				}
				walletError.postValue(new Event<>(getApplication()
						.getString(R.string.wallet_send_uncertain)));
				scanOpenBtc(true);
			} catch (Throwable e) {
				walletError.postValue(new Event<>(
						walletErrorText(e, R.string.wallet_send_failed)));
				walletBusy.postValue(false);
			} finally {
				sending.set(false);
			}
		});
	}

	private final com.professor.zerion.android.vault.wallet.btc
			.PayjoinFlowController payjoinFlow =
			new com.professor.zerion.android.vault.wallet.btc
					.PayjoinFlowController();
	private final MutableLiveData<com.professor.zerion.android.vault.wallet.btc
			.PayjoinFlowController.State> payjoinState = new MutableLiveData<>();
	private final MutableLiveData<com.professor.zerion.android.vault.wallet.btc
			.PayjoinReviewData> payjoinReview = new MutableLiveData<>();
	private final MutableLiveData<PayjoinFailure> payjoinFailure =
			new MutableLiveData<>();
	@Nullable
	private volatile com.professor.zerion.android.vault.wallet.btc.payjoin
			.PayjoinTransport payjoinTransport;

	public static final class PayjoinFailure {
		public final String message;
		public final boolean offerNormalFallback;

		PayjoinFailure(String message, boolean offerNormalFallback) {
			this.message = message;
			this.offerNormalFallback = offerNormalFallback;
		}
	}

	public LiveData<com.professor.zerion.android.vault.wallet.btc
			.PayjoinFlowController.State> getPayjoinState() {
		return payjoinState;
	}

	public LiveData<com.professor.zerion.android.vault.wallet.btc
			.PayjoinReviewData> getPayjoinReview() {
		return payjoinReview;
	}

	public LiveData<PayjoinFailure> getPayjoinFailure() {
		return payjoinFailure;
	}

	public void startPayjoin(String uri, double feeRate, boolean sweep) {
		if (!com.professor.zerion.android.vault.wallet.btc.payjoin
				.PayjoinFeature.isEnabled()) {
			return;
		}
		com.professor.zerion.android.vault.wallet.btc.payjoin.PayjoinUri parsed =
				com.professor.zerion.android.vault.wallet.btc.payjoin.PayjoinUri
						.detect(uri);
		if (!parsed.isPayjoin()) {
			return;
		}
		walletBusy.postValue(true);
		WALLET_EXECUTOR.execute(() -> {
			try {
				payjoinFlow.reset();
				payjoinFlow.beginPreparing();
				payjoinState.postValue(payjoinFlow.state());
				BtcWallet w = openBtc;
				if (w == null) {
					failPayjoin(com.professor.zerion.android.vault.wallet.btc
							.payjoin.PayjoinSession.Status.FAILED,
							getApplication().getString(
									R.string.wallet_open_failed));
					return;
				}
				w.planSend(parsed.address, parsed.amountSat, feeRate, sweep, null,
						false);
				com.professor.zerion.android.vault.wallet.btc.payjoin
						.PayjoinTransport t = payjoinTransport;
				if (t == null) {
					failPayjoin(com.professor.zerion.android.vault.wallet.btc
							.payjoin.PayjoinSession.Status.FAILED, null);
					return;
				}
				failPayjoin(com.professor.zerion.android.vault.wallet.btc.payjoin
						.PayjoinSession.Status.FAILED, null);
			} catch (Throwable e) {
				failPayjoin(com.professor.zerion.android.vault.wallet.btc.payjoin
						.PayjoinSession.Status.FAILED,
						walletErrorText(e, R.string.payjoin_unavailable_message));
			} finally {
				walletBusy.postValue(false);
			}
		});
	}

	private void failPayjoin(com.professor.zerion.android.vault.wallet.btc
			.payjoin.PayjoinSession.Status status, @Nullable String reason) {
		com.professor.zerion.android.vault.wallet.btc.PayjoinOutcomeRouter.Action
				action = payjoinFlow.fail(status);
		payjoinState.postValue(payjoinFlow.state());
		payjoinFailure.postValue(new PayjoinFailure(
				reason != null ? reason : getApplication().getString(
						R.string.payjoin_unavailable_message),
				action == com.professor.zerion.android.vault.wallet.btc
						.PayjoinOutcomeRouter.Action.OFFER_NORMAL_FALLBACK));
	}

	public void authorizePayjoin(char[] credential, String reviewedFingerprint) {
		WALLET_EXECUTOR.execute(() -> {
			try {
				boolean authed = verifyWalletCredential(credential);
				com.professor.zerion.android.vault.wallet.btc.PayjoinFinalTx tx =
						payjoinFlow.authorize(reviewedFingerprint, authed);
				if (tx.buildSignedHexApproved(reviewedFingerprint) == null) {
					throw new com.professor.zerion.android.vault.wallet.btc
							.PayjoinGate.AuthorizationException(
							"the transaction changed; review again");
				}
				payjoinState.postValue(payjoinFlow.state());
			} catch (Throwable e) {
				payjoinFailure.postValue(new PayjoinFailure(getApplication()
						.getString(R.string.wallet_auth_send_failed), true));
			}
		});
	}

	public void cancelPayjoin() {
		WALLET_EXECUTOR.execute(() -> {
			payjoinFlow.cancel();
			payjoinState.postValue(payjoinFlow.state());
		});
	}

	public void onPayjoinInterrupted() {
		payjoinFlow.onInterrupted();
		payjoinState.postValue(payjoinFlow.state());
	}

	public void payjoinFallbackToNormal(String toAddress, long amountSat,
			double feeRate, boolean sweep) {
		WALLET_EXECUTOR.execute(() -> {
			payjoinFlow.reset();
			payjoinState.postValue(payjoinFlow.state());
		});
		prepareSend(toAddress, amountSat, feeRate, sweep, null, false);
	}

	private void postLocalTxState(BtcWallet w, long netSat) {
		try {
			List<BtcWallet.TxSummary> known = lastSummaries;
			List<BtcWallet.TxSummary> merged = BtcWallet.mergePending(
					known == null ? new java.util.ArrayList<>() : known,
					w.pendingSummaries());
			lastSummaries = merged;
			btcHistory.postValue(merged);
			Long balance = lastBalance;
			if (balance != null) {
				long next = balance + netSat;
				lastBalance = next;
				btcBalanceSat.postValue(next);
			}
		} catch (Throwable ignored) {
		}
	}

	private synchronized boolean verifyWalletCredential(char[] credential) {
		walletCredential.restoreFailures();
		if (walletCredential.isThrottled()) {
			SecureMemory.shred(credential);
			return false;
		}
		boolean ok = walletCredential.matches(credential);
		if (ok) {
			walletCredential.recordSuccess();
		} else {
			boolean relock = walletCredential.recordFailure();
			dropReviewedPlans();
			if (relock) resetWalletSession();
		}
		return ok;
	}

	private final java.util.concurrent.atomic.AtomicBoolean scanInFlight =
			new java.util.concurrent.atomic.AtomicBoolean(false);
	private volatile long scanEpoch = 0;

	private void scanOpenBtc(boolean force) {
		BtcWallet w = openBtc;
		if (w == null) {
			walletBusy.postValue(false);
			return;
		}
		if (!scanInFlight.compareAndSet(false, true)) {
			return;
		}
		long epoch = scanEpoch;
		try {
			BtcWallet.ScanResult r = force ? w.scan() : w.scanLight();
			if (scanEpoch != epoch || openBtc != w) {
				return;
			}
			java.util.Set<String> txids = new java.util.HashSet<>();
			for (ElectrumClient.HistItem h : r.history) {
				txids.add(h.txHash);
			}
			if (BtcWallet.emptiedAfterHistory(lastTxids, txids)) {
				walletOnline.postValue(false);
				walletError.postValue(new Event<>(getApplication()
						.getString(R.string.wallet_server_inconsistent)));
				return;
			}
			lastBalance = r.balanceSat;
			btcBalanceSat.postValue(r.balanceSat);

			int shown = receiveIndex.get();
			int next = r.usedReceiveIndexes.contains(shown)
					? BtcWallet.nextUnusedAtOrAbove(r.usedReceiveIndexes,
							shown + 1)
					: Math.max(shown, r.receiveIndex);
			if (next != shown) {
				receiveIndex.set(next);
				w.setMinReceiveProbe(next);
				btcReceiveAddress.postValue(w.receiveAddressAt(next));
				String scanId = currentWalletId;
				if (scanId != null && scanEpoch == epoch && openBtc == w) {
					walletSettings.raiseReceiveIndex(scanId, next);
				}
			}

			if (force || lastSummaries == null || !txids.equals(lastTxids)) {
				List<BtcWallet.TxSummary> summaries = BtcWallet.mergePending(
						w.history(r), w.pendingSummaries());
				if (scanEpoch != epoch || openBtc != w) {
					return;
				}
				lastSummaries = summaries;
				lastTxids = txids;
				btcHistory.postValue(summaries);
			}

			try {
				btcFeeRate.postValue(w.feeRateSatPerVb(4));
			} catch (Exception ignored) {
			}
			walletOnline.postValue(true);
		} catch (Throwable e) {
			if (scanEpoch == epoch && openBtc == w) {
				walletOnline.postValue(false);
				walletError.postValue(new Event<>(getApplication()
						.getString(R.string.wallet_network_failed)));
			}
		} finally {
			scanInFlight.set(false);
			walletBusy.postValue(false);
		}
	}

	private static char[] generateMnemonic() throws Exception {
		byte[] entropy = new byte[32];
		new SecureRandom().nextBytes(entropy);
		List<String> words = new MnemonicCode().toMnemonic(entropy);
		Arrays.fill(entropy, (byte) 0);
		int length = words.size() - 1;
		for (String w : words) length += w.length();
		char[] out = new char[length];
		int pos = 0;
		for (int i = 0; i < words.size(); i++) {
			if (i > 0) out[pos++] = ' ';
			String w = words.get(i);
			w.getChars(0, w.length(), out, pos);
			pos += w.length();
		}
		return out;
	}

	public LiveData<VaultState> getVaultState() {
		return vaultState;
	}

	public LiveData<List<VaultItem>> getVaultItems() {
		return vaultItems;
	}

	public LiveData<String> getErrorMessage() {
		return errorMessage;
	}

	public LiveData<String> getSuccessMessage() {
		return successMessage;
	}

	public LiveData<Boolean> getIsLoading() {
		return isLoading;
	}

	public LiveData<Integer> getProgressPercent() {
		return progressPercent;
	}

	public LiveData<String> getProgressMessage() {
		return progressMessage;
	}

	public void refreshVaultState() {
		VaultState newState;
		if (!vaultManager.vaultExists()) {
			newState = VaultState.NOT_CREATED;
		} else if (!vaultManager.isUnlocked()) {
			newState = VaultState.LOCKED;
		} else {
			newState = VaultState.UNLOCKED;
		}

		try {
			vaultState.setValue(newState);
		} catch (Exception e) {
			vaultState.postValue(newState);
		}
	}

	public void clearMessages() {
		if (android.os.Looper.myLooper()
				== android.os.Looper.getMainLooper()) {
			successMessage.setValue(null);
			errorMessage.setValue(null);
		} else {
			successMessage.postValue(null);
			errorMessage.postValue(null);
		}
	}

	public void clearSensitiveMemory() {
		vaultItems.postValue(new ArrayList<>());
		errorMessage.postValue(null);
		successMessage.postValue(null);
		progressMessage.postValue(null);
		progressPercent.postValue(0);
	}

	public void createVault(char[] password, char[] confirmPassword) {
		if (!Arrays.equals(password, confirmPassword)) {
			Arrays.fill(password, '\0');
			Arrays.fill(confirmPassword, '\0');
			errorMessage.postValue(
					getApplication().getString(
							R.string.vault_password_mismatch));
			return;
		}

		if (password.length < 8) {
			Arrays.fill(password, '\0');
			Arrays.fill(confirmPassword, '\0');
			errorMessage.postValue(
					getApplication().getString(R.string.password_min_8_chars));
			return;
		}

		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.createVault(password);
				vaultState.postValue(VaultState.UNLOCKED);
				successMessage.postValue(text(R.string.vault_msg_created));
				loadVaultItems();
			} catch (IllegalStateException e) {
				errorMessage.postValue(text(R.string.vault_msg_already_exists));
			} catch (SecurityException e) {
				errorMessage.postValue(text(R.string.vault_msg_security_error));
			} catch (IOException e) {
				errorMessage.postValue(text(R.string.vault_msg_storage_error));
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_create_failed));
			} finally {
				Arrays.fill(password, '\0');
				Arrays.fill(confirmPassword, '\0');
				isLoading.postValue(false);
			}
		});
	}

	private String text(@StringRes int id) {
		return getApplication().getString(id);
	}

	private String walletErrorText(Throwable e, @StringRes int fallback) {
		return walletErrorText(getApplication(), e, fallback);
	}

	static String walletErrorText(Context ctx, Throwable e,
			@StringRes int fallback) {
		String m = e.getMessage();
		if (m == null || m.isEmpty()) return ctx.getString(fallback);
		Integer known = WALLET_ERRORS.get(m);
		if (known != null) return ctx.getString(known);
		return ctx.getString(fallback) + ": " + m;
	}

	static boolean hasWalletErrorText(String message) {
		return WALLET_ERRORS.containsKey(message);
	}

	public boolean isMessage(@Nullable String message, @StringRes int id) {
		return message != null && message.equals(text(id));
	}

	public void unlockVault(char[] password) {
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				boolean success = vaultManager.unlockVault(password);
				if (success) {
					errorMessage.postValue(null);
					vaultState.postValue(VaultState.UNLOCKED);
					loadVaultItems();
				} else {
					errorMessage.postValue(text(R.string.vault_unlock_failed));
				}
			} catch (SecurityException e) {
				errorMessage.postValue(text(R.string.vault_unlock_failed));
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_unlock_failed));
			} finally{
				Arrays.fill(password, '\0');
				isLoading.postValue(false);
			}
		});
	}

	@Nullable
	public com.professor.zerion.android.vault.crypto.Argon2.Argon2Params
			vaultKdfParameters() {
		return vaultManager.kdfParameters();
	}

	public void lockVault() {
		resetWalletSession();
		vaultManager.lockVault();
		vaultState.postValue(VaultState.LOCKED);
		clearSensitiveMemory();
		com.professor.zerion.android.vault.ui.adapters.VaultGalleryAdapter
				.clearThumbnailCache();
	}

	public void loadVaultItems() {
		if (!vaultManager.vaultExists()) {
			vaultItems.postValue(new ArrayList<>());
			return;
		}
		if (!vaultManager.isUnlocked()) {
			vaultItems.postValue(new ArrayList<>());
			return;
		}

		dbExecutor.execute(() -> {
			try {
				List<VaultItem> items = vaultManager.listItems();
				vaultItems.postValue(items);
			} catch (SecurityException e) {
				vaultItems.postValue(new ArrayList<>());
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_load_failed));
			}
		});
	}

	public void addDocument(String fileName, byte[] content) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue(text(R.string.vault_msg_unlock_first));
			return;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			errorMessage.postValue(text(R.string.vault_msg_file_name_empty));
			return;
		}
		if (content == null || content.length == 0) {
			errorMessage.postValue(text(R.string.vault_msg_file_empty));
			return;
		}

		isLoading.postValue(true);
		dbExecutor.execute(() -> {
			try {
				VaultItem item = vaultManager.addItem(VaultItem.ItemType.DOCUMENT, fileName, content);
				loadVaultItems();
				isLoading.postValue(false);
			} catch (SecurityException e) {
				errorMessage.postValue(text(R.string.vault_locked));
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_add_document_failed));
				isLoading.postValue(false);
			}
		});
	}

	public void addDocumentWithPassword(String fileName, byte[] content, @Nullable char[] extraPassword) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue(text(R.string.vault_msg_unlock_first));
			return;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			errorMessage.postValue(text(R.string.vault_msg_file_name_empty));
			return;
		}
		if (content == null || content.length == 0) {
			errorMessage.postValue(text(R.string.vault_msg_file_empty));
			return;
		}

		isLoading.postValue(true);
		dbExecutor.execute(() -> {
			try {
				VaultItem item;
				if (extraPassword != null && extraPassword.length > 0) {
					item = vaultManager.addItemWithPassword(
							VaultItem.ItemType.DOCUMENT,
							fileName,
							content,
							extraPassword
					);
				} else {
					item = vaultManager.addItem(VaultItem.ItemType.DOCUMENT, fileName, content);
				}
				loadVaultItems();
				successMessage.postValue(text(R.string.vault_msg_document_saved));
				isLoading.postValue(false);
			} catch (SecurityException e) {
				errorMessage.postValue(text(R.string.vault_locked));
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_add_document_failed));
				isLoading.postValue(false);
			} finally {
				if (extraPassword != null) {
					Arrays.fill(extraPassword, '\0');
				}
			}
		});
	}

	public void updateDocument(String itemId, String fileName, byte[] content) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue(text(R.string.vault_msg_unlock_first));
			return;
		}
		if (itemId == null || itemId.trim().isEmpty()) {
			errorMessage.postValue(text(R.string.vault_msg_invalid_document));
			return;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			errorMessage.postValue(text(R.string.vault_msg_file_name_empty));
			return;
		}
		if (content == null || content.length == 0) {
			errorMessage.postValue(text(R.string.vault_msg_file_empty));
			return;
		}

		isLoading.postValue(true);
		dbExecutor.execute(() -> {
			try {
				vaultManager.deleteItem(itemId);
				vaultManager.addItem(VaultItem.ItemType.DOCUMENT, fileName, content);
				loadVaultItems();
				successMessage.postValue(text(R.string.vault_msg_document_updated));
				isLoading.postValue(false);
			} catch (SecurityException e) {
				errorMessage.postValue(text(R.string.vault_locked));
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_update_document_failed));
				isLoading.postValue(false);
			}
		});
	}

	public void savePassword(String title, String username, String password,
			String url, String notes) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue(text(R.string.vault_msg_unlock_first));
			return;
		}
		if (title.trim().isEmpty()) {
			errorMessage.postValue(text(R.string.vault_error_title_empty));
			return;
		}

		isLoading.postValue(true);
		dbExecutor.execute(() -> {
			try {
				byte[] content = PasswordEntryCodec.encode(title, username,
						password, url, notes);

				VaultItem item = vaultManager.addItem(VaultItem.ItemType.PASSWORD, title, content);

				loadVaultItems();
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_save_password_failed));
				isLoading.postValue(false);
			}
		});
	}

	public void getPassword(String itemId, PasswordCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError(text(R.string.vault_msg_unlock_first));
			});
			return;
		}
		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.getItemContent(itemId);
				String json = new String(content, StandardCharsets.UTF_8);

				PasswordEntry entry = PasswordEntryCodec.decode(json);

				Arrays.fill(content, (byte) 0);

				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onPasswordRetrieved(entry);
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError(text(R.string.vault_msg_retrieve_password_failed));
				});
			}
		});
	}

	public interface PasswordCallback {
		void onPasswordRetrieved(PasswordEntry entry);
		void onError(String error);
	}

	public void saveNote(String title, String content, @Nullable String existingId) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue(text(R.string.vault_msg_unlock_first));
			return;
		}
		if (title.trim().isEmpty()) {
			errorMessage.postValue(text(R.string.vault_msg_note_title_empty));
			return;
		}

		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);

				if (existingId != null) {
					vaultManager.deleteItem(existingId);
				}

				VaultItem item = vaultManager.addItem(
						VaultItem.ItemType.NOTE,
						title,
						contentBytes
				);

				successMessage.postValue(text(R.string.vault_msg_saved));
				loadVaultItems();

				Arrays.fill(contentBytes, (byte) 0);

			} catch (SecurityException e) {
				errorMessage.postValue(text(R.string.vault_msg_security_error));
			} catch (IOException e) {
				errorMessage.postValue(text(R.string.vault_msg_storage_error));
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_save_note_failed));
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void saveNoteWithPassword(String title, String content, char[] password,
			@Nullable String existingNoteId) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue(text(R.string.vault_msg_unlock_first));
			java.util.Arrays.fill(password, '\0');
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			char[] passwordChars = password;
			try {
				byte[] combined = ProtectedNoteCodec.seal(content,
						passwordChars);

				if (existingNoteId != null) {
					vaultManager.deleteItem(existingNoteId);
				}

				String protectedTitle = ProtectedNoteCodec.TITLE_PREFIX + title;
				VaultItem item = vaultManager.addItem(VaultItem.ItemType.NOTE, protectedTitle, combined);

				successMessage.postValue(text(R.string.vault_msg_saved));
				loadVaultItems();

			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_save_note_failed));
			} finally {
				Arrays.fill(passwordChars, '\0');
				isLoading.postValue(false);
			}
		});
	}

	public LiveData<String> loadNoteContent(String noteId) {
		MutableLiveData<String> content = new MutableLiveData<>();

		if (!vaultManager.isUnlocked()) {
			content.postValue("__RETRY__");
			return content;
		}

		dbExecutor.execute(() -> {
			try {
				if (!vaultManager.isUnlocked()) {
					content.postValue("__RETRY__");
					return;
				}

				List<VaultItem> items = vaultManager.listItems();

				VaultItem targetItem = null;
				for (VaultItem item : items) {
					if (item.id.equals(noteId)) {
						targetItem = item;
						break;
					}
				}

				if (targetItem == null) {
					content.postValue(null);
					errorMessage.postValue(text(R.string.vault_msg_note_not_found));
					return;
				}

				if (targetItem.name.startsWith(ProtectedNoteCodec.TITLE_PREFIX)) {
					content.postValue("__PASSWORD_REQUIRED__");
				} else {
					byte[] contentBytes = vaultManager.getItemContent(noteId);
					String noteContent = new String(contentBytes, java.nio.charset.StandardCharsets.UTF_8);
					content.postValue(noteContent);

					Arrays.fill(contentBytes, (byte) 0);
				}

			} catch (SecurityException e) {
				content.postValue("__RETRY__");
			} catch (IOException e) {
				content.postValue(null);
				errorMessage.postValue(text(R.string.vault_msg_storage_error));
			} catch (Exception e) {
				content.postValue(null);
				errorMessage.postValue(text(R.string.vault_msg_load_note_failed));
			}
		});

		return content;
	}

	public LiveData<String> loadPasswordProtectedNote(String noteId, char[] password) {
		MutableLiveData<String> content = new MutableLiveData<>();

		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue(text(R.string.vault_msg_unlock_first));
			java.util.Arrays.fill(password, '\0');
			return content;
		}

		dbExecutor.execute(() -> {
			char[] passwordChars = password;
			try {
				byte[] encryptedData = vaultManager.getItemContent(noteId);

				byte[] decryptedContent =
						ProtectedNoteCodec.open(encryptedData, passwordChars);
				if (decryptedContent == null) {
					Arrays.fill(encryptedData, (byte) 0);
					content.postValue(null);
					return;
				}
				String noteContent = new String(decryptedContent, java.nio.charset.StandardCharsets.UTF_8);
				content.postValue(noteContent);

				Arrays.fill(encryptedData, (byte) 0);
				Arrays.fill(decryptedContent, (byte) 0);
			} catch (SecurityException e) {
				content.postValue(null);
				errorMessage.postValue(text(R.string.vault_msg_unlock_first));
			} catch (Exception e) {
				content.postValue(null);
				errorMessage.postValue(text(R.string.vault_msg_decrypt_note_failed));
			} finally {
				Arrays.fill(passwordChars, '\0');
			}
		});

		return content;
	}

	public void addMediaToVault(VaultItem.ItemType type, String name, byte[] content, String mimeType) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue(text(R.string.vault_msg_unlock_first));
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.addMediaItem(type, name, content, mimeType);
				successMessage.postValue(text(R.string.vault_msg_added));
				loadVaultItems();
			} catch (SecurityException e) {
				errorMessage.postValue(text(R.string.vault_msg_security_error));
			} catch (IOException e) {
				errorMessage.postValue(text(R.string.vault_msg_storage_error));
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_add_failed));
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void deleteItem(String itemId) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue(text(R.string.vault_msg_unlock_first));
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.deleteItem(itemId);
				loadVaultItems();
				successMessage.postValue(text(R.string.vault_msg_item_deleted));
			} catch (SecurityException e) {
				errorMessage.postValue(text(R.string.vault_locked));
			} catch (IOException e) {
				errorMessage.postValue(text(R.string.vault_msg_storage_error));
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_delete_failed));
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void getMediaContent(String itemId, MediaContentCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError(text(R.string.vault_msg_unlock_first));
			});
			return;
		}
		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.getItemContent(itemId);

				if (content == null || content.length == 0) {
					new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
						callback.onError(text(R.string.vault_msg_file_empty));
					});
					return;
				}

				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onContentRetrieved(content);
				});
			} catch (SecurityException e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError(text(R.string.vault_locked));
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError(text(R.string.vault_msg_load_content_failed));
				});
			}
		});
	}

	public interface MediaContentCallback {
		void onContentRetrieved(byte[] content);
		void onError(String error);
	}

	public void getThumbnail(String itemId, ThumbnailCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError(text(R.string.vault_msg_unlock_first));
			});
			return;
		}
		dbExecutor.execute(() -> {
			try {
				byte[] thumbnail = vaultManager.getThumbnail(itemId);

				if (thumbnail == null) {
					thumbnail = vaultManager.getItemContent(itemId);
				}

				if (thumbnail == null || thumbnail.length == 0) {
					new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
						callback.onError(text(R.string.vault_msg_load_thumbnail_failed));
					});
					return;
				}

				byte[] finalThumbnail = thumbnail;
				THUMB_DECODE_EXECUTOR.execute(() ->
						callback.onThumbnailRetrieved(finalThumbnail));
			} catch (SecurityException e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError(text(R.string.vault_locked));
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError(text(R.string.vault_msg_load_thumbnail_failed));
				});
			}
		});
	}

	public interface ThumbnailCallback {
		void onThumbnailRetrieved(byte[] thumbnail);
		void onError(String error);
	}

	public interface DocumentCallback {
		void onLoaded(byte[] content, String mimeType);

		void onError(String error);
	}

	public void loadDocumentSecure(String itemId, DocumentCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError(text(R.string.vault_msg_unlock_first));
			});
			return;
		}

		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.loadDocumentContentSecure(itemId);

				if (content == null || content.length == 0) {
					new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
						callback.onError(text(R.string.vault_msg_document_empty));
					});
					return;
				}

				VaultItem item = null;
				for (VaultItem vaultItem : vaultManager.listItems()) {
					if (vaultItem.id.equals(itemId)) {
						item = vaultItem;
						break;
					}
				}

				String filename = item != null ? item.name : "";
				com.professor.zerion.android.vault.utils.MimeUtils.MimeType mimeType =
						com.professor.zerion.android.vault.utils.MimeUtils.detectMimeType(content, filename);

				byte[] finalContent = content;
				String finalMimeType = mimeType.mimeType;
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onLoaded(finalContent, finalMimeType);
				});

			} catch (SecurityException e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError(text(R.string.vault_locked));
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError(text(R.string.vault_msg_load_document_failed));
				});
			}
		});
	}

	public void loadDocumentWithPassword(String itemId, @Nullable char[] extraPassword,
			DocumentCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError(text(R.string.vault_msg_unlock_first));
			});
			return;
		}

		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.getItemContentWithPassword(itemId, extraPassword);

				if (content == null || content.length == 0) {
					new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
						callback.onError(text(R.string.vault_msg_document_empty));
					});
					return;
				}

				VaultItem item = null;
				for (VaultItem vaultItem : vaultManager.listItems()) {
					if (vaultItem.id.equals(itemId)) {
						item = vaultItem;
						break;
					}
				}

				String filename = item != null ? item.name : "";
				com.professor.zerion.android.vault.utils.MimeUtils.MimeType mimeType =
						com.professor.zerion.android.vault.utils.MimeUtils.detectMimeType(content, filename);

				byte[] finalContent = content;
				String finalMimeType = mimeType.mimeType;
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onLoaded(finalContent, finalMimeType);
				});

			} catch (SecurityException e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError(text(R.string.vault_incorrect_password));
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError(text(R.string.vault_msg_decrypt_failed));
				});
			} finally {
				if (extraPassword != null) {
					Arrays.fill(extraPassword, '\0');
				}
			}
		});
	}

	public void checkAutoLock() {
		vaultManager.checkAutoLock();
	}

	public void lockIfUnlocked() {
		if (vaultManager.isUnlocked()) {
			lockVault();
		} else {
			resetWalletSession();
		}
	}

	public void wipeVault() {
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.wipeVault();
				vaultState.postValue(VaultState.NOT_CREATED);
				clearSensitiveMemory();
				successMessage.postValue(text(R.string.vault_msg_wiped));
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_wipe_failed));
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void changePassword(char[] currentPassword, char[] newPassword) {
		if (!vaultManager.vaultExists()) {
			errorMessage.postValue(text(R.string.vault_msg_no_vault));
			return;
		}
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue(text(R.string.vault_msg_unlock_first));
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.changePassword(currentPassword, newPassword);
				successMessage.postValue(text(R.string.vault_settings_password_changed));
			} catch (SecurityException e) {
				String m = e.getMessage();
				errorMessage.postValue(m != null
						&& m.startsWith(VaultManager.TOO_MANY_ATTEMPTS)
						? text(R.string.vault_msg_too_many_attempts)
						: text(R.string.vault_msg_invalid_current_password));
			} catch (Exception e) {
				errorMessage.postValue(text(R.string.vault_msg_change_password_failed));
			} finally {
				Arrays.fill(currentPassword, '\0');
				Arrays.fill(newPassword, '\0');
				isLoading.postValue(false);
			}
		});
	}

	public void exportVault(char[] exportPassword, ExportCallback callback) {
		if (!vaultManager.isUnlocked()) {
			callback.onExportError(text(R.string.vault_msg_unlock_first));
			return;
		}
		dbExecutor.execute(() -> {
			try {
				byte[] exportData = vaultManager.exportVault(exportPassword);
				callback.onExportSuccess(exportData);
			} catch (Exception e) {
				callback.onExportError(text(R.string.vault_document_export_generic_failed));
			} finally {
				Arrays.fill(exportPassword, '\0');
			}
		});
	}

	public interface ExportCallback {
		void onExportSuccess(byte[] data);
		void onExportError(String error);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		clearSensitiveMemory();
		if (vaultManager.isUnlocked()) {
			vaultManager.lockVault();
		}
	}
}