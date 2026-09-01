package com.professor.zerion.android.vault.ui;

import android.app.Application;

import com.professor.zerion.R;
import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.model.PasswordEntry;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.utils.SecureMemory;
import com.professor.zerion.android.vault.wallet.WalletCoin;
import com.professor.zerion.android.vault.wallet.WalletRecord;
import com.professor.zerion.android.vault.wallet.WalletStore;
import com.professor.zerion.android.vault.wallet.btc.BtcWallet;
import com.professor.zerion.android.vault.wallet.btc.ElectrumClient;
import com.professor.zerion.android.vault.wallet.btc.SilentPaymentScanner;

import org.bitcoinj.crypto.MnemonicCode;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.plugin.TorSocksPort;

@NotNullByDefault
public class VaultViewModel extends AndroidViewModel {

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
	private static final String ELECTRUM_HOST = "electrum.blockstream.info";
	private static final int ELECTRUM_PORT = 50002;

	private final WalletStore walletStore;
	private final int torSocksPort;

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
	private final MutableLiveData<String> walletSeedReveal = new MutableLiveData<>();
	private final MutableLiveData<Boolean> walletGateGranted = new MutableLiveData<>();
	private final MutableLiveData<Boolean> walletGateBusy = new MutableLiveData<>(false);
	private final MutableLiveData<String> walletAuthState = new MutableLiveData<>();
	private volatile boolean walletSectionUnlocked = false;
	private volatile long walletAuthGeneration = -1;

	@Nullable
	private volatile BtcWallet openBtc;
	@Nullable
	private java.util.Set<String> lastTxids;
	@Nullable
	private Long lastBalance;
	@Nullable
	private List<BtcWallet.TxSummary> lastSummaries;

	@Inject
	public VaultViewModel(Application application, VaultManager vaultManager,
			@DatabaseExecutor Executor dbExecutor, WalletStore walletStore,
			@TorSocksPort int torSocksPort) {
		super(application);
		this.vaultManager = vaultManager;
		this.dbExecutor = dbExecutor;
		this.walletStore = walletStore;
		this.torSocksPort = torSocksPort;
		vaultManager.setOnLockListener(this::resetWalletSession);
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

	/**
	 * Post the Bitcoin wallet list. The Bitcoin screen shows Bitcoin wallets
	 * only: coin identity comes from the persisted {@link WalletRecord#coin},
	 * never the name, address, or navigation state, and any other/unknown coin is
	 * never shown here. Every place that refreshes the Bitcoin list routes
	 * through this so no path can leak a Monero wallet into it.
	 */
	private void postBtcWallets() throws Exception {
		List<WalletRecord> btc = new java.util.ArrayList<>();
		for (WalletRecord w : walletStore.listWallets()) {
			if (w.coin == com.professor.zerion.android.vault.wallet
					.WalletCoin.BTC) {
				String display = readBtcDisplayName(w.id, w.name);
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
				walletSeedReveal.postValue(new String(mnemonic));
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
				List<String> words = Arrays.asList(
						new String(mnemonic).trim().split("\\s+"));
				new MnemonicCode().check(words);
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
					persistRates(r);
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
				org.json.JSONObject o = settingsObject();
				String cached = o.optString("priceRates", "");
				long at = o.optLong("priceAt", 0);
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

	private void persistRates(com.professor.zerion.android.vault.wallet.btc
			.BtcPrice.Rates r) {
		try {
			synchronized (walletStore.settingsLock) {
				org.json.JSONObject o = settingsObject();
				o.put("priceRates", r.toJson());
				o.put("priceAt", System.currentTimeMillis());
				walletStore.writeSettings(o.toString());
			}
		} catch (Throwable ignored) {
		}
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
					persistReceiveIndex(id, idx);
				}
			} catch (Throwable ignored) {
			}
		});
	}

	private int readReceiveIndex(String walletId) {
		try {
			org.json.JSONObject recv = settingsObject().optJSONObject("recv");
			if (recv != null) {
				return Math.max(0, recv.optInt(walletId, 0));
			}
		} catch (Throwable ignored) {
		}
		return 0;
	}

	private void persistReceiveIndex(String walletId, int index) {
		try {
			synchronized (walletStore.settingsLock) {
				org.json.JSONObject o = settingsObject();
				org.json.JSONObject recv = o.optJSONObject("recv");
				if (recv == null) {
					recv = new org.json.JSONObject();
				}
				if (index > recv.optInt(walletId, 0)) {
					recv.put(walletId, index);
					o.put("recv", recv);
					walletStore.writeSettings(o.toString());
				}
			}
		} catch (Throwable ignored) {
		}
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
		WALLET_EXECUTOR.execute(() -> {
			String cur = "EUR";
			try {
				String json = walletStore.readSettings();
				if (json != null) {
					cur = new org.json.JSONObject(json)
							.optString("currency", "EUR");
				}
			} catch (Throwable ignored) {
			}
			walletCurrency.postValue(cur);
		});
	}

	public void saveWalletCurrency(String cur) {
		WALLET_EXECUTOR.execute(() -> {
			try {
				synchronized (walletStore.settingsLock) {
					org.json.JSONObject o = settingsObject();
					o.put("currency", cur);
					walletStore.writeSettings(o.toString());
				}
			} catch (Throwable ignored) {
			}
		});
	}

	public static final String DEFAULT_NODE =
			ELECTRUM_HOST + ":" + ELECTRUM_PORT;
	private static final String LEGACY_DEFAULT_NODE =
			ELECTRUM_HOST + ":50001";

	public static final String DEFAULT_ONION_NODE =
			"kittycp2gatrqhlwpmbczk5rblw62enrpo2rzwtkfrrr27hq435d4vid.onion"
					+ ":50001";

	public static final String DEFAULT_ONION_NODE_2 = "";

	public static String preferredDefaultNode() {
		return com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
				.preferredDefaultSpec(DEFAULT_ONION_NODE, DEFAULT_NODE);
	}

	private static String normalizeNode(String node) {
		return LEGACY_DEFAULT_NODE.equals(node) ? preferredDefaultNode() : node;
	}

	private final MutableLiveData<String> walletPinPrompt =
			new MutableLiveData<>();

	public LiveData<String> getWalletPinPrompt() {
		return walletPinPrompt;
	}

	@Nullable
	private String pinFor(String node) {
		try {
			org.json.JSONObject pins = settingsObject().optJSONObject("pins");
			if (pins != null) {
				String p = pins.optString(node, "");
				return p.isEmpty() ? null : p;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	public void captureNodePin(String node) {
		WALLET_EXECUTOR.execute(() -> {
			try {
				com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
						ep = endpointFromNodeString(node);
				if (!ep.tls()) {
					walletPinPrompt.postValue(node + "|none");
					return;
				}
				String fp = ElectrumClient.captureCertSha256(ep, torSocksPort);
				walletPinPrompt.postValue(node + "|" + fp);
			} catch (Throwable e) {
				walletPinPrompt.postValue(node + "|error");
			}
		});
	}

	public void confirmNodePin(String node, String pin) {
		WALLET_EXECUTOR.execute(() -> {
			try {
				synchronized (walletStore.settingsLock) {
					org.json.JSONObject o = settingsObject();
					org.json.JSONObject pins = o.optJSONObject("pins");
					if (pins == null) {
						pins = new org.json.JSONObject();
					}
					pins.put(node, pin);
					o.put("pins", pins);
					walletStore.writeSettings(o.toString());
				}
			} catch (Throwable ignored) {
			}
		});
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

	private String[] selectedNodeHostPort() {
		String node = preferredDefaultNode();
		try {
			String json = walletStore.readSettings();
			if (json != null) {
				node = new org.json.JSONObject(json).optString("node",
						preferredDefaultNode());
			}
		} catch (Throwable ignored) {
		}
		node = normalizeNode(node);
		int i = node.lastIndexOf(':');
		if (i <= 0) {
			return new String[]{ELECTRUM_HOST, String.valueOf(ELECTRUM_PORT)};
		}
		return new String[]{node.substring(0, i), node.substring(i + 1)};
	}

	private com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
			endpointFromNodeString(String node) {
		int i = node.lastIndexOf(':');
		String host = i <= 0 ? ELECTRUM_HOST : node.substring(0, i);
		int port = ELECTRUM_PORT;
		try {
			port = Integer.parseInt(node.substring(i + 1).trim());
		} catch (Exception ignored) {
		}
		return com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
				.fromUserInput(host, port, pinFor(node));
	}

	private com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
			selectedScanEndpoint() {
		String[] hp = selectedNodeHostPort();
		return endpointFromNodeString(hp[0] + ":" + hp[1]);
	}

	private com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
			broadcastEndpointFor(
			com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint scan) {
		try {
			String json = walletStore.readSettings();
			if (json != null) {
				org.json.JSONObject o = new org.json.JSONObject(json);
				String selected = o.optString("node", preferredDefaultNode());
				java.util.List<String> candidates = new java.util.ArrayList<>();
				candidates.add(preferredDefaultNode());
				org.json.JSONArray arr = o.optJSONArray("nodes");
				if (arr != null) {
					for (int i = 0; i < arr.length(); i++) {
						candidates.add(arr.getString(i));
					}
				}
				for (String n : candidates) {
					if (!n.equals(selected)) {
						return endpointFromNodeString(n);
					}
				}
			}
		} catch (Throwable ignored) {
		}
		return scan;
	}

	public void loadNodes() {
		CRYPTO_EXECUTOR.execute(() -> {
			java.util.List<String> list = new java.util.ArrayList<>();
			list.add(preferredDefaultNode());
			String selected = preferredDefaultNode();
			try {
				String json = walletStore.readSettings();
				if (json != null) {
					org.json.JSONObject o = new org.json.JSONObject(json);
					org.json.JSONArray arr = o.optJSONArray("nodes");
					if (arr != null) {
						for (int i = 0; i < arr.length(); i++) {
							String n = arr.getString(i);
							if (!list.contains(n)) {
								list.add(n);
							}
						}
					}
					selected = normalizeNode(o.optString("node", preferredDefaultNode()));
				}
			} catch (Throwable ignored) {
			}
			if (!list.contains(selected)) {
				selected = preferredDefaultNode();
			}
			walletNodeList.postValue(list);
			walletSelectedNode.postValue(selected);
		});
	}

	public void addNode(String node) {
		WALLET_EXECUTOR.execute(() -> {
			try {
				synchronized (walletStore.settingsLock) {
					org.json.JSONObject o = settingsObject();
					org.json.JSONArray arr = o.optJSONArray("nodes");
					if (arr == null) {
						arr = new org.json.JSONArray();
					}
					boolean exists = node.equals(preferredDefaultNode());
					for (int i = 0; i < arr.length(); i++) {
						if (arr.getString(i).equals(node)) {
							exists = true;
						}
					}
					if (!exists) {
						arr.put(node);
					}
					o.put("nodes", arr);
					walletStore.writeSettings(o.toString());
				}
			} catch (Throwable ignored) {
			}
			loadNodes();
		});
	}

	public void selectNode(String node) {
		WALLET_EXECUTOR.execute(() -> {
			try {
				synchronized (walletStore.settingsLock) {
					org.json.JSONObject o = settingsObject();
					o.put("node", node);
					walletStore.writeSettings(o.toString());
				}
			} catch (Throwable ignored) {
			}
			reopenActiveWalletEndpoints();
			loadNodes();
		});
	}

	private java.util.List<com.professor.zerion.android.vault.wallet.btc
			.ElectrumEndpoint> fallbackChainFor(
			com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
					scanEp) {
		java.util.List<com.professor.zerion.android.vault.wallet.btc
				.ElectrumEndpoint> fbs = new java.util.ArrayList<>();
		String scanSpec = scanEp.host + ":" + scanEp.port;
		if (scanSpec.equals(DEFAULT_ONION_NODE)
				&& !DEFAULT_ONION_NODE.isEmpty()) {
			if (!DEFAULT_ONION_NODE_2.isEmpty()) {
				fbs.add(endpointFromNodeString(DEFAULT_ONION_NODE_2));
			}
			fbs.add(endpointFromNodeString(DEFAULT_NODE));
		}
		return fbs;
	}

	private void reopenActiveWalletEndpoints() {
		BtcWallet w = openBtc;
		String id = currentWalletId;
		if (w == null || id == null) {
			return;
		}
		try {
			com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
					scanEp = routedScanEndpoint(id);
			com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
					bcastEp = routedBroadcastEndpoint(id, scanEp);
			java.util.List<com.professor.zerion.android.vault.wallet.btc
					.ElectrumEndpoint> fbs = routedFallbacks(id, scanEp);
			w.updateEndpoints(scanEp, bcastEp, fbs, fbs);
			scanOpenBtc(true);
		} catch (Throwable ignored) {
		}
	}

	public static final String ROUTING_TOR = "tor";
	public static final String ROUTING_DIRECT = "direct";
	public static final String ROUTING_LOCAL = "local";

	public String readWalletRouting(String walletId) {
		String r = readWalletPrivacy(walletId).optString("routing", ROUTING_TOR);
		if (ROUTING_DIRECT.equals(r) || ROUTING_LOCAL.equals(r)) {
			return r;
		}
		return ROUTING_TOR;
	}

	public void setWalletRouting(String walletId, String mode) {
		WALLET_EXECUTOR.execute(() -> {
			mutateWalletPrivacy(walletId, w -> w.put("routing", mode));
			reopenActiveWalletEndpoints();
		});
	}

	private com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
			routedScanEndpoint(String walletId) {
		if (ROUTING_DIRECT.equals(readWalletRouting(walletId))) {
			return endpointFromNodeString(DEFAULT_NODE).asDirect();
		}
		return selectedScanEndpoint();
	}

	private com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
			routedBroadcastEndpoint(String walletId,
			com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint scanEp) {
		if (ROUTING_DIRECT.equals(readWalletRouting(walletId))) {
			return scanEp;
		}
		return broadcastEndpointFor(scanEp);
	}

	private java.util.List<com.professor.zerion.android.vault.wallet.btc
			.ElectrumEndpoint> routedFallbacks(String walletId,
			com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint scanEp) {
		String routing = readWalletRouting(walletId);
		if (ROUTING_DIRECT.equals(routing) || ROUTING_LOCAL.equals(routing)) {
			return new java.util.ArrayList<>();
		}
		return fallbackChainFor(scanEp);
	}

	public void deleteNode(String node) {
		if (node.equals(preferredDefaultNode())) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			try {
				synchronized (walletStore.settingsLock) {
					org.json.JSONObject o = settingsObject();
					org.json.JSONArray arr = o.optJSONArray("nodes");
					org.json.JSONArray keep = new org.json.JSONArray();
					if (arr != null) {
						for (int i = 0; i < arr.length(); i++) {
							String n = arr.getString(i);
							if (!n.equals(node)) {
								keep.put(n);
							}
						}
					}
					o.put("nodes", keep);
					if (node.equals(o.optString("node", preferredDefaultNode()))) {
						o.put("node", preferredDefaultNode());
					}
					walletStore.writeSettings(o.toString());
				}
			} catch (Throwable ignored) {
			}
			loadNodes();
		});
	}

	public void checkNode(String node) {
		WALLET_EXECUTOR.execute(() -> {
			boolean ok = false;
			try {
				try (ElectrumClient c = new ElectrumClient(
						endpointFromNodeString(node), torSocksPort,
						"nodecheck")) {
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
				persistBtcDisplayName(id, newName);
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

	private String readBtcDisplayName(String walletId, String fallback) {
		try {
			org.json.JSONObject btc = settingsObject().optJSONObject("btc");
			if (btc != null) {
				org.json.JSONObject w = btc.optJSONObject(walletId);
				if (w != null) {
					String nm = w.optString("nm", "");
					if (!nm.isEmpty()) {
						return nm;
					}
				}
			}
		} catch (Throwable ignored) {
		}
		return fallback;
	}

	private void persistBtcDisplayName(String walletId, String name)
			throws Exception {
		synchronized (walletStore.settingsLock) {
			org.json.JSONObject o = settingsObject();
			org.json.JSONObject btc = o.optJSONObject("btc");
			if (btc == null) {
				btc = new org.json.JSONObject();
			}
			org.json.JSONObject w = btc.optJSONObject(walletId);
			if (w == null) {
				w = new org.json.JSONObject();
			}
			w.put("nm", name);
			btc.put(walletId, w);
			o.put("btc", btc);
			walletStore.writeSettings(o.toString());
		}
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
	private final java.util.List<SilentPaymentScanner.Found> spUtxos =
			new java.util.ArrayList<>();

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
				org.json.JSONObject o = readWalletPrivacy(id);
				spUtxos.clear();
				org.json.JSONArray arr = o.optJSONArray("spUtxos");
				if (arr != null) {
					for (int i = 0; i < arr.length(); i++) {
						org.json.JSONObject u = arr.getJSONObject(i);
						spUtxos.add(new SilentPaymentScanner.Found(
								u.getString("t"), u.getInt("v"), u.getLong("val"),
								spHex(u.getString("x")), spHex(u.getString("w"))));
					}
				}
				postSpInfo(o);
			} catch (Throwable ignored) {
			}
		});
	}

	private void postSpInfo(org.json.JSONObject o) {
		long bal = 0;
		for (SilentPaymentScanner.Found f : spUtxos) {
			bal += f.valueSat;
		}
		int scanned = o.optInt("spScanned", 0);
		int birthday = o.optInt("spBirthday", 0);
		int oracleSet = o.optString("spOracle", "").isEmpty() ? 0 : 1;
		spInfo.postValue(new long[]{bal, spUtxos.size(), scanned, oracleSet,
				birthday});
	}

	public void saveSpConfig(String oracle, int birthday) {
		String id = currentWalletId;
		if (id == null) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			try {
				mutateWalletPrivacy(id, w -> {
					w.put("spOracle", oracle.trim());
					w.put("spBirthday", Math.max(birthday, 0));
					if (w.optInt("spScanned", 0) < birthday - 1) {
						w.put("spScanned", Math.max(birthday - 1, 0));
					}
				});
				postSpInfo(readWalletPrivacy(id));
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
				org.json.JSONObject o = readWalletPrivacy(id);
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
				for (SilentPaymentScanner.Found f : r.found) {
					boolean dup = false;
					for (SilentPaymentScanner.Found e : spUtxos) {
						if (e.txid.equals(f.txid) && e.vout == f.vout) {
							dup = true;
							break;
						}
					}
					if (!dup) {
						spUtxos.add(f);
					}
				}
				mutateWalletPrivacy(id, wp -> {
					wp.put("spScanned", r.scannedTo);
					wp.put("spUtxos", spUtxosJson());
				});
				postSpInfo(readWalletPrivacy(id));
			} catch (Throwable e) {
				walletError.postValue(new Event<>(e.getMessage() != null ? e.getMessage()
						: getApplication().getString(R.string.wallet_network_failed)));
			} finally {
				spBusy.postValue(false);
			}
		});
	}

	public void sweepSp(String toAddress, double feeRate, char[] credential) {
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
				if (!verifyWalletCredential(credential)) {
					walletError.postValue(new Event<>(getApplication()
							.getString(R.string.wallet_auth_send_failed)));
					return;
				}
				String id = currentWalletId;
				String txid = w.sweepSilentPayments(
						new java.util.ArrayList<>(spUtxos), toAddress, feeRate);
				spUtxos.clear();
				if (id != null) {
					mutateWalletPrivacy(id, wp ->
							wp.put("spUtxos", new org.json.JSONArray()));
					postSpInfo(readWalletPrivacy(id));
				}
				spTxid.postValue(new Event<>(txid));
				scanOpenBtc(true);
			} catch (com.professor.zerion.android.vault.wallet.btc
					.BroadcastUncertainException e) {
				walletError.postValue(new Event<>(getApplication()
						.getString(R.string.wallet_send_uncertain)));
				scanOpenBtc(true);
			} catch (Throwable e) {
				walletError.postValue(new Event<>(e.getMessage() != null ? e.getMessage()
						: getApplication().getString(R.string.wallet_send_failed)));
			} finally {
				spBusy.postValue(false);
				sending.set(false);
			}
		});
	}

	private org.json.JSONArray spUtxosJson() throws org.json.JSONException {
		org.json.JSONArray arr = new org.json.JSONArray();
		for (SilentPaymentScanner.Found f : spUtxos) {
			org.json.JSONObject u = new org.json.JSONObject();
			u.put("t", f.txid);
			u.put("v", f.vout);
			u.put("val", f.valueSat);
			u.put("x", spHexStr(f.xonly));
			u.put("w", spHexStr(f.tweak));
			arr.put(u);
		}
		return arr;
	}

	private static byte[] spHex(String h) {
		byte[] out = new byte[h.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static String spHexStr(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) {
			sb.append(Character.forDigit((x >> 4) & 0xF, 16));
			sb.append(Character.forDigit(x & 0xF, 16));
		}
		return sb.toString();
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
			boolean hasAuth = false;
			try {
				String json = walletStore.readSettings();
				hasAuth = json != null
						&& new org.json.JSONObject(json).has("authHash");
			} catch (Throwable ignored) {
			}
			walletAuthState.postValue(hasAuth ? "verify" : "setup");
		});
	}

	public void setupWalletAuth(String type, char[] credential) {
		walletGateBusy.postValue(true);
		CRYPTO_EXECUTOR.execute(() -> {
			try {
				if (vaultManager.verifyMasterPassword(credential)) {
					walletError.postValue(new Event<>(getApplication().getString(
							R.string.wallet_auth_same_as_vault)));
					return;
				}
				byte[] salt = new byte[16];
				new SecureRandom().nextBytes(salt);
				int iter = 120_000;
				byte[] hash = pbkdf2(credential, salt, iter, 32);
				synchronized (walletStore.settingsLock) {
					org.json.JSONObject o = settingsObject();
					o.put("authType", type);
					o.put("authSalt", android.util.Base64.encodeToString(salt,
							android.util.Base64.NO_WRAP));
					o.put("authHash", android.util.Base64.encodeToString(hash,
							android.util.Base64.NO_WRAP));
					o.put("authIter", iter);
					walletStore.writeSettings(o.toString());
				}
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

	private volatile int walletGateFailures = 0;
	private volatile long walletGateBackoffUntil = 0;

	public void verifyWalletAuth(char[] credential) {
		walletGateBusy.postValue(true);
		CRYPTO_EXECUTOR.execute(() -> {
			if (System.currentTimeMillis() < walletGateBackoffUntil) {
				SecureMemory.shred(credential);
				walletError.postValue(new Event<>(getApplication().getString(
						R.string.wallet_auth_too_many)));
				walletGateGranted.postValue(false);
				walletGateBusy.postValue(false);
				return;
			}
			boolean ok = false;
			try {
				org.json.JSONObject o = settingsObject();
				byte[] salt = android.util.Base64.decode(
						o.getString("authSalt"), android.util.Base64.NO_WRAP);
				int iter = o.getInt("authIter");
				byte[] expected = android.util.Base64.decode(
						o.getString("authHash"), android.util.Base64.NO_WRAP);
				byte[] hash = pbkdf2(credential, salt, iter, expected.length);
				ok = java.security.MessageDigest.isEqual(hash, expected);
			} catch (Throwable ignored) {
			} finally {
				SecureMemory.shred(credential);
			}
			if (ok) {
				walletGateFailures = 0;
				walletGateBackoffUntil = 0;
				walletSectionUnlocked = true;
				walletAuthGeneration = vaultManager.getLockGeneration();
			} else {
				walletGateFailures++;
				if (walletGateFailures >= 3) {
					long delay = Math.min(300_000L,
							1000L << Math.min(walletGateFailures - 3, 8));
					walletGateBackoffUntil =
							System.currentTimeMillis() + delay;
				}
			}
			walletGateGranted.postValue(ok);
			walletGateBusy.postValue(false);
		});
	}

	private org.json.JSONObject settingsObject() throws Exception {
		String json = walletStore.readSettings();
		return json == null ? new org.json.JSONObject()
				: new org.json.JSONObject(json);
	}

	private org.json.JSONObject readWalletPrivacy(String walletId) {
		try {
			org.json.JSONObject priv =
					settingsObject().optJSONObject("privacy");
			if (priv != null) {
				org.json.JSONObject w = priv.optJSONObject(walletId);
				if (w != null) {
					return w;
				}
			}
		} catch (Throwable ignored) {
		}
		return new org.json.JSONObject();
	}

	private interface PrivacyMutation {
		void apply(org.json.JSONObject walletPrivacy) throws Exception;
	}

	private void mutateWalletPrivacy(String walletId, PrivacyMutation mutation) {
		try {
			synchronized (walletStore.settingsLock) {
				org.json.JSONObject o = settingsObject();
				org.json.JSONObject priv = o.optJSONObject("privacy");
				if (priv == null) {
					priv = new org.json.JSONObject();
				}
				org.json.JSONObject w = priv.optJSONObject(walletId);
				if (w == null) {
					w = new org.json.JSONObject();
				}
				mutation.apply(w);
				priv.put(walletId, w);
				o.put("privacy", priv);
				walletStore.writeSettings(o.toString());
			}
		} catch (Throwable ignored) {
		}
	}

	private com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyStore
			privacyStoreFor(String walletId) {
		return new com.professor.zerion.android.vault.wallet.btc.privacy
				.PrivacyStore() {
			@Override
			public java.util.Set<String> frozen() {
				java.util.Set<String> s = new java.util.HashSet<>();
				org.json.JSONArray a =
						readWalletPrivacy(walletId).optJSONArray("frozen");
				if (a != null) {
					for (int i = 0; i < a.length(); i++) {
						s.add(a.optString(i));
					}
				}
				return s;
			}

			@Override
			public java.util.Map<String, String> labels() {
				return toMap(readWalletPrivacy(walletId)
						.optJSONObject("labels"));
			}

			@Override
			public java.util.Map<String, String> originHints() {
				return toMap(readWalletPrivacy(walletId)
						.optJSONObject("originHints"));
			}

			@Override
			public void setFrozen(String outpoint, boolean frozen) {
				mutateWalletPrivacy(walletId, w -> {
					org.json.JSONArray a = w.optJSONArray("frozen");
					org.json.JSONArray next = new org.json.JSONArray();
					if (a != null) {
						for (int i = 0; i < a.length(); i++) {
							if (!a.optString(i).equals(outpoint)) {
								next.put(a.optString(i));
							}
						}
					}
					if (frozen) {
						next.put(outpoint);
					}
					w.put("frozen", next);
				});
			}

			@Override
			public void setLabel(String outpoint,
					@Nullable String label) {
				mutateWalletPrivacy(walletId, w -> {
					org.json.JSONObject labels = w.optJSONObject("labels");
					if (labels == null) {
						labels = new org.json.JSONObject();
					}
					if (label == null || label.isEmpty()) {
						labels.remove(outpoint);
					} else {
						labels.put(outpoint, label);
					}
					w.put("labels", labels);
				});
			}

			@Override
			public void putOriginHint(String address, String clusterId) {
				mutateWalletPrivacy(walletId, w -> {
					org.json.JSONObject hints = w.optJSONObject("originHints");
					if (hints == null) {
						hints = new org.json.JSONObject();
					}
					hints.put(address, clusterId);
					w.put("originHints", hints);
				});
			}
		};
	}

	private static java.util.Map<String, String> toMap(
			@Nullable org.json.JSONObject o) {
		java.util.Map<String, String> m = new java.util.HashMap<>();
		if (o != null) {
			java.util.Iterator<String> it = o.keys();
			while (it.hasNext()) {
				String k = it.next();
				m.put(k, o.optString(k));
			}
		}
		return m;
	}

	@Nullable
	private volatile String currentWalletId;

	private final MutableLiveData<Boolean> walletExtremeMode =
			new MutableLiveData<>();

	public LiveData<Boolean> getWalletExtremeMode() {
		return walletExtremeMode;
	}

	public boolean isExtremeMode(String walletId) {
		return readWalletPrivacy(walletId).optBoolean("extremeMode", false);
	}

	public void setExtremeMode(boolean on) {
		String id = currentWalletId;
		if (id == null) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			mutateWalletPrivacy(id, w -> w.put("extremeMode", on));
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
		return readWalletPrivacy(walletId).optBoolean("spEnabled", false);
	}

	public void setSpEnabled(boolean on) {
		String id = currentWalletId;
		if (id == null) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			mutateWalletPrivacy(id, w -> w.put("spEnabled", on));
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
			privacyStoreFor(id).setFrozen(outpoint, frozen);
			loadCoins();
		});
	}

	public void setUtxoLabel(String outpoint, @Nullable String label) {
		String id = currentWalletId;
		if (id == null) {
			return;
		}
		WALLET_EXECUTOR.execute(() -> {
			privacyStoreFor(id).setLabel(outpoint, label);
			loadCoins();
		});
	}

	private static final long PENDING_RETENTION_MS = 7L * 24 * 3600 * 1000;

	private static org.json.JSONObject pendingToJson(
			com.professor.zerion.android.vault.wallet.btc.PendingTx tx)
			throws org.json.JSONException {
		org.json.JSONObject r = new org.json.JSONObject();
		r.put("id", tx.id);
		r.put("txid", tx.txid);
		r.put("rawHex", tx.rawHex);
		r.put("state", tx.state);
		r.put("createdAt", tx.createdAt);
		r.put("netSat", tx.netSat);
		org.json.JSONArray ops = new org.json.JSONArray();
		for (String op : tx.outpoints) {
			ops.put(op);
		}
		r.put("outpoints", ops);
		return r;
	}

	private com.professor.zerion.android.vault.wallet.btc.PendingLog
			pendingLogFor(String walletId) {
		return new com.professor.zerion.android.vault.wallet.btc.PendingLog() {
			@Override
			public java.util.List<
					com.professor.zerion.android.vault.wallet.btc.PendingTx>
					all() {
				java.util.List<
						com.professor.zerion.android.vault.wallet.btc.PendingTx>
						out = new java.util.ArrayList<>();
				try {
					org.json.JSONObject byWallet =
							settingsObject().optJSONObject("pending");
					if (byWallet == null) {
						return out;
					}
					org.json.JSONArray arr = byWallet.optJSONArray(walletId);
					if (arr == null) {
						return out;
					}
					for (int i = 0; i < arr.length(); i++) {
						org.json.JSONObject r = arr.getJSONObject(i);
						java.util.List<String> ops = new java.util.ArrayList<>();
						org.json.JSONArray oa = r.optJSONArray("outpoints");
						if (oa != null) {
							for (int j = 0; j < oa.length(); j++) {
								ops.add(oa.getString(j));
							}
						}
						out.add(new com.professor.zerion.android.vault.wallet
								.btc.PendingTx(r.optString("id"),
								r.optString("txid"), r.optString("rawHex"), ops,
								r.optString("state"), r.optLong("createdAt"),
								r.optLong("netSat")));
					}
				} catch (Throwable ignored) {
				}
				return out;
			}

			@Override
			public void put(
					com.professor.zerion.android.vault.wallet.btc.PendingTx tx) {
				try {
					synchronized (walletStore.settingsLock) {
						org.json.JSONObject o = settingsObject();
						org.json.JSONObject byWallet = o.optJSONObject("pending");
						if (byWallet == null) {
							byWallet = new org.json.JSONObject();
						}
						org.json.JSONArray arr = byWallet.optJSONArray(walletId);
						org.json.JSONArray next = new org.json.JSONArray();
						long cutoff = System.currentTimeMillis()
								- PENDING_RETENTION_MS;
						boolean replaced = false;
						if (arr != null) {
							for (int i = 0; i < arr.length(); i++) {
								org.json.JSONObject r = arr.getJSONObject(i);
								if (tx.id.equals(r.optString("id"))) {
									next.put(pendingToJson(tx));
									replaced = true;
									continue;
								}
								String st = r.optString("state");
								boolean resolved = "sent".equals(st)
										|| "failed".equals(st);
								if (resolved && r.optLong("createdAt") < cutoff) {
									continue;
								}
								next.put(r);
							}
						}
						if (!replaced) {
							next.put(pendingToJson(tx));
						}
						byWallet.put(walletId, next);
						o.put("pending", byWallet);
						walletStore.writeSettings(o.toString());
					}
				} catch (Throwable e) {
					throw new RuntimeException("pending persist failed", e);
				}
			}
		};
	}

	private static byte[] decryptNoteV2(byte[] encryptedData, char[] password)
			throws Exception {
		byte[] salt = new byte[32];
		byte[] iv = new byte[12];
		byte[] encryptedContent = new byte[encryptedData.length - 45];
		System.arraycopy(encryptedData, 1, salt, 0, 32);
		System.arraycopy(encryptedData, 33, iv, 0, 12);
		System.arraycopy(encryptedData, 45, encryptedContent, 0,
				encryptedContent.length);
		com.professor.zerion.android.vault.crypto.Argon2 argon2 =
				new com.professor.zerion.android.vault.crypto.Argon2();
		byte[] key = argon2.deriveKey(password, salt,
				com.professor.zerion.android.vault.crypto.Argon2.Argon2Params
						.getDefault());
		try {
			return aesGcmDecrypt(key, iv, encryptedContent);
		} finally {
			Arrays.fill(key, (byte) 0);
			Arrays.fill(salt, (byte) 0);
		}
	}

	private static byte[] decryptNoteV1(byte[] encryptedData, char[] password)
			throws Exception {
		byte[] salt = new byte[16];
		byte[] iv = new byte[12];
		byte[] encryptedContent = new byte[encryptedData.length - 28];
		System.arraycopy(encryptedData, 0, salt, 0, 16);
		System.arraycopy(encryptedData, 16, iv, 0, 12);
		System.arraycopy(encryptedData, 28, encryptedContent, 0,
				encryptedContent.length);
		byte[] key = pbkdf2(password, salt, 200000, 32);
		try {
			return aesGcmDecrypt(key, iv, encryptedContent);
		} finally {
			Arrays.fill(key, (byte) 0);
			Arrays.fill(salt, (byte) 0);
		}
	}

	private static byte[] aesGcmDecrypt(byte[] key, byte[] iv, byte[] data)
			throws Exception {
		javax.crypto.Cipher cipher =
				javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
				new javax.crypto.spec.SecretKeySpec(key, "AES"),
				new javax.crypto.spec.GCMParameterSpec(128, iv));
		return cipher.doFinal(data);
	}

	private static byte[] pbkdf2(char[] pw, byte[] salt, int iter, int len)
			throws Exception {
		javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
				pw, salt, iter, len * 8);
		try {
			return javax.crypto.SecretKeyFactory
					.getInstance("PBKDF2WithHmacSHA256")
					.generateSecret(spec).getEncoded();
		} finally {
			spec.clearPassword();
		}
	}

	public LiveData<String> getWalletSeedReveal() {
		return walletSeedReveal;
	}

	public void clearSeedReveal() {
		walletSeedReveal.postValue(null);
	}

	public void revealSeed(String walletId, char[] password) {
		CRYPTO_EXECUTOR.execute(() -> {
			try {
				String mnemonic = walletStore.loadMnemonic(walletId, password);
				walletSeedReveal.postValue(mnemonic);
			} catch (Exception e) {
				walletError.postValue(new Event<>(getApplication().getString(
						isWrongPassword(e) ? R.string.wallet_wrong_password
								: R.string.wallet_open_failed)));
			} finally {
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
				removeWalletMetadata(walletId);
				postBtcWallets();
				walletDeleted.postValue(new Event<>(walletId));
			} catch (Throwable e) {
				walletError.postValue(new Event<>(getApplication().getString(
						R.string.wallet_delete_failed)));
			}
		});
	}

	private void migrateWalletMetadata(String oldId, String newId)
			throws Exception {
		synchronized (walletStore.settingsLock) {
			org.json.JSONObject o = settingsObject();
			boolean changed = false;
			for (String section : new String[] {"privacy", "recv",
					"pending", "btc"}) {
				org.json.JSONObject sub = o.optJSONObject(section);
				if (sub != null && sub.has(oldId)) {
					sub.put(newId, sub.get(oldId));
					sub.remove(oldId);
					o.put(section, sub);
					changed = true;
				}
			}
			if (changed) {
				walletStore.writeSettings(o.toString());
			}
		}
	}

	private void replaceWalletItem(String oldId, String newId) throws Exception {
		try {
			migrateWalletMetadata(oldId, newId);
		} catch (Throwable me) {
			walletStore.deleteWallet(newId);
			throw me;
		}
		walletStore.deleteWallet(oldId);
	}

	private void removeWalletMetadata(String walletId) {
		try {
			synchronized (walletStore.settingsLock) {
				org.json.JSONObject o = settingsObject();
				boolean changed = false;
				for (String section : new String[] {"privacy", "recv", "pending", "btc"}) {
					org.json.JSONObject sub = o.optJSONObject(section);
					if (sub != null && sub.has(walletId)) {
						sub.remove(walletId);
						o.put(section, sub);
						changed = true;
					}
				}
				if (changed) {
					walletStore.writeSettings(o.toString());
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private final MutableLiveData<Event<String>> walletOpened =
			new MutableLiveData<>();

	public LiveData<Event<String>> getWalletOpened() {
		return walletOpened;
	}

	public void openBtcWallet(String walletId, @Nullable char[] password) {
		walletBusy.postValue(true);
		CRYPTO_EXECUTOR.execute(() -> {
			String mnemonic = null;
			try {
				if (!walletSessionValid()) {
					walletBusy.postValue(false);
					return;
				}
				mnemonic = walletStore.loadMnemonic(walletId, password);
				com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
						scanEp = routedScanEndpoint(walletId);
				com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint
						bcastEp = routedBroadcastEndpoint(walletId, scanEp);
				BtcWallet w = new BtcWallet(mnemonic, 0, torSocksPort,
						scanEp, bcastEp, walletId);
				w.setPendingLog(pendingLogFor(walletId));
				w.setPrivacyStore(privacyStoreFor(walletId));
				boolean epm = isExtremeMode(walletId);
				w.setPrivacyPolicy(epm
						? com.professor.zerion.android.vault.wallet.btc.privacy
								.PrivacyEngine.Policy.STRICT
						: com.professor.zerion.android.vault.wallet.btc.privacy
								.PrivacyEngine.Policy.STANDARD);
				boolean spOn = isSpEnabled(walletId);
				w.setSilentPaymentsEnabled(spOn);
				java.util.List<com.professor.zerion.android.vault.wallet.btc
						.ElectrumEndpoint> fbs = routedFallbacks(walletId, scanEp);
				w.setFallbacks(fbs, fbs);
				int persistedIndex = readReceiveIndex(walletId);
				w.setMinReceiveProbe(persistedIndex);
				String firstAddr = w.receiveAddressAt(persistedIndex);

				if (!walletSessionValid()) {
					walletBusy.postValue(false);
					return;
				}
				sendGate.clear();
				openBtc = w;
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

	public void closeBtcWallet() {
		scanEpoch++;
		sendGate.clear();
		spUtxos.clear();
		openBtc = null;
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
				sendGate.prepare(plan);
				walletSendReview.postValue(new Event<>(
						new SendReview(plan, w.analyzePlan(plan))));
			} catch (com.professor.zerion.android.vault.wallet.btc.privacy
					.PrivacyMergeException e) {
				walletMergePrompt.postValue(new MergePrompt(toAddress, amountSat,
						feeRate, sweep, e.clusterCount));
			} catch (Throwable e) {
				walletError.postValue(new Event<>(e.getMessage() != null ? e.getMessage()
						: getApplication().getString(R.string.wallet_send_failed)));
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
				plan = sendGate.authorize(reviewedFingerprint, authed);
				BtcWallet w = openBtc;
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
					w.invalidateCachedScan();
					postLocalTxState(w, plan.netSat);
				}
				walletError.postValue(new Event<>(getApplication()
						.getString(R.string.wallet_send_uncertain)));
				scanOpenBtc(true);
			} catch (Throwable e) {
				walletError.postValue(new Event<>(e.getMessage() != null ? e.getMessage()
						: getApplication().getString(R.string.wallet_send_failed)));
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
						.PayjoinSession.Status.FAILED, e.getMessage());
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
				tx.buildSignedHex();
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
			List<BtcWallet.TxSummary> merged = BtcWallet.mergePending(
					lastSummaries == null ? new java.util.ArrayList<>()
							: lastSummaries, w.pendingSummaries());
			lastSummaries = merged;
			btcHistory.postValue(merged);
			if (lastBalance != null) {
				lastBalance = lastBalance + netSat;
				btcBalanceSat.postValue(lastBalance);
			}
		} catch (Throwable ignored) {
		}
	}

	private boolean verifyWalletCredential(char[] credential) {
		boolean ok = false;
		try {
			org.json.JSONObject o = settingsObject();
			byte[] salt = android.util.Base64.decode(
					o.getString("authSalt"), android.util.Base64.NO_WRAP);
			int iter = o.getInt("authIter");
			byte[] expected = android.util.Base64.decode(
					o.getString("authHash"), android.util.Base64.NO_WRAP);
			byte[] hash = pbkdf2(credential, salt, iter, expected.length);
			ok = java.security.MessageDigest.isEqual(hash, expected);
		} catch (Throwable ignored) {
		} finally {
			SecureMemory.shred(credential);
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
			lastBalance = r.balanceSat;
			btcBalanceSat.postValue(r.balanceSat);

			if (r.receiveIndex > receiveIndex.get()) {
				receiveIndex.set(r.receiveIndex);
				btcReceiveAddress.postValue(r.receiveAddress);
				String scanId = currentWalletId;
				if (scanId != null && scanEpoch == epoch && openBtc == w) {
					persistReceiveIndex(scanId, r.receiveIndex);
				}
			}

			java.util.Set<String> txids = new java.util.HashSet<>();
			for (ElectrumClient.HistItem h : r.history) {
				txids.add(h.txHash);
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
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < words.size(); i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(words.get(i));
		}
		char[] out = new char[sb.length()];
		sb.getChars(0, sb.length(), out, 0);
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
		successMessage.postValue(null);
		errorMessage.postValue(null);
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
				successMessage.postValue("Vault created successfully");
				loadVaultItems();
			} catch (IllegalStateException e) {
				errorMessage.postValue("Vault already exists");
			} catch (SecurityException e) {
				errorMessage.postValue("Security error");
			} catch (IOException e) {
				errorMessage.postValue("Storage error");
			} catch (Exception e) {
				errorMessage.postValue("Failed to create vault");
			} finally {
				Arrays.fill(password, '\0');
				Arrays.fill(confirmPassword, '\0');
				isLoading.postValue(false);
			}
		});
	}

	public void unlockVault(char[] password) {
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				boolean success = vaultManager.unlockVault(password);
				if (success) {
					vaultState.postValue(VaultState.UNLOCKED);
					loadVaultItems();
				} else {
					errorMessage.postValue("Invalid password");
				}
			} catch (SecurityException e) {
				errorMessage.postValue("Invalid password");
			} catch (Exception e) {
				errorMessage.postValue("Failed to unlock vault");
			} finally{
				Arrays.fill(password, '\0');
				isLoading.postValue(false);
			}
		});
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
				errorMessage.postValue("Failed to load vault items");
			}
		});
	}

	public void addDocument(String fileName, byte[] content) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			errorMessage.postValue("File name cannot be empty");
			return;
		}
		if (content == null || content.length == 0) {
			errorMessage.postValue("File content is empty");
			return;
		}

		isLoading.postValue(true);
		dbExecutor.execute(() -> {
			try {
				VaultItem item = vaultManager.addItem(VaultItem.ItemType.DOCUMENT, fileName, content);
				loadVaultItems();
				isLoading.postValue(false);
			} catch (SecurityException e) {
				errorMessage.postValue("Vault is locked");
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue("Failed to add document");
				isLoading.postValue(false);
			}
		});
	}

	public void addDocumentWithPassword(String fileName, byte[] content, @Nullable char[] extraPassword) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			errorMessage.postValue("File name cannot be empty");
			return;
		}
		if (content == null || content.length == 0) {
			errorMessage.postValue("File content is empty");
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
				successMessage.postValue("Document saved securely");
				isLoading.postValue(false);
			} catch (SecurityException e) {
				errorMessage.postValue("Vault is locked");
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue("Failed to add document");
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
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		if (itemId == null || itemId.trim().isEmpty()) {
			errorMessage.postValue("Invalid document ID");
			return;
		}
		if (fileName == null || fileName.trim().isEmpty()) {
			errorMessage.postValue("File name cannot be empty");
			return;
		}
		if (content == null || content.length == 0) {
			errorMessage.postValue("File content is empty");
			return;
		}

		isLoading.postValue(true);
		dbExecutor.execute(() -> {
			try {
				vaultManager.deleteItem(itemId);
				vaultManager.addItem(VaultItem.ItemType.DOCUMENT, fileName, content);
				loadVaultItems();
				successMessage.postValue("Document updated");
				isLoading.postValue(false);
			} catch (SecurityException e) {
				errorMessage.postValue("Vault is locked");
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue("Failed to update document");
				isLoading.postValue(false);
			}
		});
	}

	public void savePassword(String title, String username, String password,
			String url, String notes) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		if (title.trim().isEmpty()) {
			errorMessage.postValue("Title cannot be empty");
			return;
		}

		isLoading.postValue(true);
		dbExecutor.execute(() -> {
			try {
				PasswordEntry entry = new PasswordEntry(title, username, password, url, notes);

				StringBuilder json = new StringBuilder();
				json.append("{");
				json.append("\"title\":\"").append(escapeJson(title)).append("\",");
				json.append("\"username\":\"").append(escapeJson(username)).append("\",");
				json.append("\"password\":\"").append(escapeJson(password)).append("\",");
				json.append("\"url\":\"").append(escapeJson(url)).append("\",");
				json.append("\"notes\":\"").append(escapeJson(notes)).append("\"");
				json.append("}");
				byte[] content = json.toString().getBytes(StandardCharsets.UTF_8);

				json.setLength(0);

				VaultItem item = vaultManager.addItem(VaultItem.ItemType.PASSWORD, title, content);

				loadVaultItems();
				isLoading.postValue(false);
			} catch (Exception e) {
				errorMessage.postValue("Failed to save password");
				isLoading.postValue(false);
			}
		});
	}

	public void getPassword(String itemId, PasswordCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError("Please unlock your vault first");
			});
			return;
		}
		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.getItemContent(itemId);
				String json = new String(content, StandardCharsets.UTF_8);

				PasswordEntry entry = parsePasswordEntry(json);

				Arrays.fill(content, (byte) 0);

				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onPasswordRetrieved(entry);
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Failed to retrieve password");
				});
			}
		});
	}

	private String escapeJson(String value) {
		if (value == null) return "";
		return value.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	private PasswordEntry parsePasswordEntry(String json) {
		String title = extractJsonValue(json, "title");
		String username = extractJsonValue(json, "username");
		String password = extractJsonValue(json, "password");
		String url = extractJsonValue(json, "url");
		String notes = extractJsonValue(json, "notes");
		return new PasswordEntry(title, username, password, url, notes);
	}

	private String extractJsonValue(String json, String key) {
		String searchKey = "\"" + key + "\":\"";
		int startIdx = json.indexOf(searchKey);
		if (startIdx == -1) return "";
		startIdx += searchKey.length();
		int endIdx = json.indexOf("\"", startIdx);
		if (endIdx == -1) return "";
		String value = json.substring(startIdx, endIdx);
		return value.replace("\\\"", "\"")
				.replace("\\\\", "\\")
				.replace("\\n", "\n")
				.replace("\\r", "\r")
				.replace("\\t", "\t");
	}

	public interface PasswordCallback {
		void onPasswordRetrieved(PasswordEntry entry);
		void onError(String error);
	}

	public void saveNote(String title, String content, @Nullable String existingId) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		if (title.trim().isEmpty()) {
			errorMessage.postValue("Note title cannot be empty");
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

				successMessage.postValue("Saved");
				loadVaultItems();

				Arrays.fill(contentBytes, (byte) 0);

			} catch (SecurityException e) {
				errorMessage.postValue("Security error");
			} catch (IOException e) {
				errorMessage.postValue("Storage error");
			} catch (Exception e) {
				errorMessage.postValue("Failed to save note");
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void saveNoteWithPassword(String title, String content, char[] password,
			@Nullable String existingNoteId) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			java.util.Arrays.fill(password, '\0');
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			char[] passwordChars = password;
			try {
				byte[] salt = new byte[32];
				new java.security.SecureRandom().nextBytes(salt);

				com.professor.zerion.android.vault.crypto.Argon2 argon2 =
						new com.professor.zerion.android.vault.crypto.Argon2();
				com.professor.zerion.android.vault.crypto.Argon2.Argon2Params params =
						com.professor.zerion.android.vault.crypto.Argon2
								.Argon2Params.getDefault();
				byte[] passwordKey = argon2.deriveKey(passwordChars, salt, params);

				javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
				javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(passwordKey, "AES");
				cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec);

				byte[] contentBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				byte[] iv = cipher.getIV();
				byte[] encryptedContent = cipher.doFinal(contentBytes);

				byte[] combined = new byte[1 + salt.length + iv.length + encryptedContent.length];
				combined[0] = 0x02;
				System.arraycopy(salt, 0, combined, 1, salt.length);
				System.arraycopy(iv, 0, combined, 1 + salt.length, iv.length);
				System.arraycopy(encryptedContent, 0, combined, 1 + salt.length + iv.length, encryptedContent.length);

				if (existingNoteId != null) {
					vaultManager.deleteItem(existingNoteId);
				}

				String protectedTitle = "🔒 " + title;
				VaultItem item = vaultManager.addItem(VaultItem.ItemType.NOTE, protectedTitle, combined);

				successMessage.postValue("Saved");
				loadVaultItems();

				Arrays.fill(contentBytes, (byte) 0);
				Arrays.fill(passwordKey, (byte) 0);
				Arrays.fill(encryptedContent, (byte) 0);
				Arrays.fill(salt, (byte) 0);

			} catch (Exception e) {
				errorMessage.postValue("Failed to save note");
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
					errorMessage.postValue("Note not found");
					return;
				}

				if (targetItem.name.startsWith("🔒 ")) {
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
				errorMessage.postValue("Storage error");
			} catch (Exception e) {
				content.postValue(null);
				errorMessage.postValue("Failed to load note");
			}
		});

		return content;
	}

	public LiveData<String> loadPasswordProtectedNote(String noteId, char[] password) {
		MutableLiveData<String> content = new MutableLiveData<>();

		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			java.util.Arrays.fill(password, '\0');
			return content;
		}

		dbExecutor.execute(() -> {
			char[] passwordChars = password;
			try {
				byte[] encryptedData = vaultManager.getItemContent(noteId);

				byte[] decryptedContent = null;
				if (encryptedData.length > 45 && encryptedData[0] == 0x02) {
					try {
						decryptedContent =
								decryptNoteV2(encryptedData, passwordChars);
					} catch (javax.crypto.BadPaddingException e) {
						decryptedContent = null;
					}
				}
				if (decryptedContent == null && encryptedData.length > 28) {
					try {
						decryptedContent =
								decryptNoteV1(encryptedData, passwordChars);
					} catch (javax.crypto.BadPaddingException e) {
						decryptedContent = null;
					}
				}
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
				errorMessage.postValue("Please unlock your vault first");
			} catch (Exception e) {
				content.postValue(null);
				errorMessage.postValue("Failed to decrypt note");
			} finally {
				Arrays.fill(passwordChars, '\0');
			}
		});

		return content;
	}

	public void addMediaToVault(VaultItem.ItemType type, String name, byte[] content, String mimeType) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.addMediaItem(type, name, content, mimeType);
				successMessage.postValue("Added to vault");
				loadVaultItems();
			} catch (SecurityException e) {
				errorMessage.postValue("Security error");
			} catch (IOException e) {
				errorMessage.postValue("Storage error");
			} catch (Exception e) {
				errorMessage.postValue("Failed to add to vault");
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void deleteItem(String itemId) {
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.deleteItem(itemId);
				loadVaultItems();
				successMessage.postValue("Item deleted");
			} catch (SecurityException e) {
				errorMessage.postValue("Vault locked");
			} catch (IOException e) {
				errorMessage.postValue("Storage error");
			} catch (Exception e) {
				errorMessage.postValue("Delete failed");
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void getMediaContent(String itemId, MediaContentCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError("Please unlock your vault first");
			});
			return;
		}
		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.getItemContent(itemId);

				if (content == null || content.length == 0) {
					new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
						callback.onError("Empty content");
					});
					return;
				}

				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onContentRetrieved(content);
				});
			} catch (SecurityException e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Vault is locked");
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Failed to load content");
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
				callback.onError("Please unlock your vault first");
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
						callback.onError("Empty thumbnail");
					});
					return;
				}

				byte[] finalThumbnail = thumbnail;
				THUMB_DECODE_EXECUTOR.execute(() ->
						callback.onThumbnailRetrieved(finalThumbnail));
			} catch (SecurityException e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Vault is locked");
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Failed to load thumbnail");
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
				callback.onError("Please unlock your vault first");
			});
			return;
		}

		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.loadDocumentContentSecure(itemId);

				if (content == null || content.length == 0) {
					new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
						callback.onError("Document is empty");
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
					callback.onError("Vault is locked");
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Failed to load document");
				});
			}
		});
	}

	public void loadDocumentWithPassword(String itemId, @Nullable char[] extraPassword,
			DocumentCallback callback) {
		if (!vaultManager.isUnlocked()) {
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				callback.onError("Please unlock your vault first");
			});
			return;
		}

		dbExecutor.execute(() -> {
			try {
				byte[] content = vaultManager.getItemContentWithPassword(itemId, extraPassword);

				if (content == null || content.length == 0) {
					new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
						callback.onError("Document is empty");
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
					callback.onError("Incorrect password");
				});
			} catch (Exception e) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					callback.onError("Failed to decrypt");
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
				successMessage.postValue("Vault wiped");
			} catch (Exception e) {
				errorMessage.postValue("Failed to wipe vault");
			} finally {
				isLoading.postValue(false);
			}
		});
	}

	public void changePassword(char[] currentPassword, char[] newPassword) {
		if (!vaultManager.vaultExists()) {
			errorMessage.postValue("No vault exists");
			return;
		}
		if (!vaultManager.isUnlocked()) {
			errorMessage.postValue("Please unlock your vault first");
			return;
		}
		isLoading.postValue(true);

		dbExecutor.execute(() -> {
			try {
				vaultManager.changePassword(currentPassword, newPassword);
				successMessage.postValue("Password changed successfully");
			} catch (SecurityException e) {
				errorMessage.postValue("Invalid current password");
			} catch (Exception e) {
				errorMessage.postValue("Failed to change password");
			} finally {
				Arrays.fill(currentPassword, '\0');
				Arrays.fill(newPassword, '\0');
				isLoading.postValue(false);
			}
		});
	}

	public void exportVault(char[] exportPassword, ExportCallback callback) {
		if (!vaultManager.isUnlocked()) {
			callback.onExportError("Please unlock your vault first");
			return;
		}
		dbExecutor.execute(() -> {
			try {
				byte[] exportData = vaultManager.exportVault(exportPassword);
				callback.onExportSuccess(exportData);
			} catch (Exception e) {
				callback.onExportError("Export failed");
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