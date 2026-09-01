package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.script.Script;
import org.briarproject.nullsafety.NotNullByDefault;

import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyEngine;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyMeta;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyStore;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@NotNullByDefault
public class BtcWallet {

	public static final int GAP_LIMIT = 20;

	private volatile int minReceiveProbe = -1;

	public void setMinReceiveProbe(int index) {
		if (index > minReceiveProbe) minReceiveProbe = index;
	}

	public int lastReceiveUsedIndex() {
		return lastReceiveUsed;
	}
	private static final long DUST = 294L;
	private static final double MIN_FEE_RATE = 2.0;
	private static final double MAX_FEE_RATE = 1000.0;
	private static final long PENDING_GRACE_MS = 30L * 60L * 1000L;
	private static final long SENT_VISIBILITY_MS = 2L * 60L * 60L * 1000L;
	private static final int PENDING_FAIL_MISSES = 3;

	private final Map<String, Integer> pendingMisses =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static final int POLL_LOOKAHEAD = 3;

	public static final class OwnedUtxo {
		public final String txHash;
		public final int txPos;
		public final long value;
		final DeterministicKey key;
		public final String address;
		public final com.professor.zerion.android.vault.wallet.btc.privacy
				.UtxoOrigin origin;

		OwnedUtxo(String txHash, int txPos, long value, DeterministicKey key,
				String address, com.professor.zerion.android.vault.wallet.btc
						.privacy.UtxoOrigin origin) {
			this.txHash = txHash;
			this.txPos = txPos;
			this.value = value;
			this.key = key;
			this.address = address;
			this.origin = origin;
		}
	}

	public static final class ScanResult {
		public final long balanceSat;
		public final String receiveAddress;
		public final int receiveIndex;
		public final String changeAddress;
		public final List<ElectrumClient.HistItem> history;
		public final List<OwnedUtxo> utxos;
		public final Set<String> ownedAddresses;

		ScanResult(long balanceSat, String receiveAddress, int receiveIndex,
				String changeAddress,
				List<ElectrumClient.HistItem> history, List<OwnedUtxo> utxos,
				Set<String> ownedAddresses) {
			this.balanceSat = balanceSat;
			this.receiveAddress = receiveAddress;
			this.receiveIndex = receiveIndex;
			this.changeAddress = changeAddress;
			this.history = history;
			this.utxos = utxos;
			this.ownedAddresses = ownedAddresses;
		}
	}

	public static final String STATE_BROADCASTING = "broadcasting";
	public static final String STATE_POSSIBLY_SENT = "possibly_sent";
	public static final String STATE_PENDING = "pending";
	public static final String STATE_CONFIRMED = "confirmed";
	public static final String STATE_FAILED = "failed";

	public static final class TxSummary {
		public final String txid;
		public final int height;
		public final long netSat;
		public final int confirmations;
		public final boolean netKnown;
		public final String state;

		TxSummary(String txid, int height, long netSat, int confirmations,
				boolean netKnown, String state) {
			this.txid = txid;
			this.height = height;
			this.netSat = netSat;
			this.confirmations = confirmations;
			this.netKnown = netKnown;
			this.state = state;
		}

		public boolean isPending() {
			return height <= 0;
		}
	}

	private final String mnemonic;
	private final int account;
	private final int socksPort;
	private volatile ElectrumEndpoint scanEndpoint;
	private volatile ElectrumEndpoint broadcastEndpoint;
	private final String isolationTag;
	private final ElectrumRpc.Factory electrumFactory;
	private final SilentPaymentScanner.Fetcher spFetcher;
	private final Map<String, Transaction> txCache =
			new java.util.concurrent.ConcurrentHashMap<>();
	@Nullable
	private volatile ScanResult lastScan;
	private PendingLog pendingLog = PendingLog.NONE;
	private volatile int lastReceiveUsed = -1;
	private volatile int lastChangeUsed = -1;
	private volatile List<ElectrumEndpoint> scanFallbacks =
			new ArrayList<>();
	private volatile List<ElectrumEndpoint> broadcastFallbacks =
			new ArrayList<>();
	private PrivacyStore privacyStore = PrivacyStore.NONE;
	private PrivacyEngine.Policy privacyPolicy = PrivacyEngine.Policy.STANDARD;
	private volatile boolean silentPaymentsEnabled = false;

	public void setPendingLog(PendingLog log) {
		this.pendingLog = log;
	}

	public void setSilentPaymentsEnabled(boolean enabled) {
		this.silentPaymentsEnabled = enabled;
	}

	public boolean isSilentPaymentsEnabled() {
		return silentPaymentsEnabled;
	}

	public void setPrivacyStore(PrivacyStore store) {
		this.privacyStore = store;
	}

	public void setPrivacyPolicy(PrivacyEngine.Policy policy) {
		this.privacyPolicy = policy;
	}

	public void setFallback(@Nullable ElectrumEndpoint scanFb,
			@Nullable ElectrumEndpoint broadcastFb) {
		List<ElectrumEndpoint> s = new ArrayList<>();
		if (scanFb != null) {
			s.add(scanFb);
		}
		List<ElectrumEndpoint> b = new ArrayList<>();
		if (broadcastFb != null) {
			b.add(broadcastFb);
		}
		setFallbacks(s, b);
	}

	public void setFallbacks(List<ElectrumEndpoint> scanFbs,
			List<ElectrumEndpoint> broadcastFbs) {
		this.scanFallbacks = new ArrayList<>(scanFbs);
		this.broadcastFallbacks = new ArrayList<>(broadcastFbs);
	}

	public void updateEndpoints(ElectrumEndpoint scan,
			ElectrumEndpoint broadcast, List<ElectrumEndpoint> scanFbs,
			List<ElectrumEndpoint> broadcastFbs) {
		this.scanEndpoint = scan;
		this.broadcastEndpoint = broadcast;
		setFallbacks(scanFbs, broadcastFbs);
		this.lastScan = null;
	}

	private ElectrumRpc openScan() throws IOException {
		return openWithFallback(scanEndpoint, scanFallbacks, isolationTag);
	}

	private ElectrumRpc openWithFallback(ElectrumEndpoint primary,
			List<ElectrumEndpoint> fallbacks, String tag) throws IOException {
		IOException last;
		try {
			return electrumFactory.open(primary, socksPort, tag);
		} catch (IOException e) {
			last = e;
		}
		for (ElectrumEndpoint fb : fallbacks) {
			if (fb == null || fb.equals(primary)) {
				continue;
			}
			try {
				return electrumFactory.open(fb, socksPort, tag);
			} catch (IOException e) {
				last = e;
			}
		}
		throw last;
	}

	public BtcWallet(String mnemonic, int account, int socksPort, String host,
			int port, String isolationTag) {
		this(mnemonic, account, socksPort,
				ElectrumEndpoint.parse(host + ":" + port),
				ElectrumEndpoint.parse(host + ":" + port), isolationTag);
	}

	public BtcWallet(String mnemonic, int account, int socksPort,
			ElectrumEndpoint scanEndpoint, ElectrumEndpoint broadcastEndpoint,
			String isolationTag) {
		this(mnemonic, account, socksPort, scanEndpoint, broadcastEndpoint,
				isolationTag, ElectrumClient::new,
				(url, tag) -> TorHttp.get(url, socksPort, tag));
	}

	BtcWallet(String mnemonic, int account, int socksPort, String host,
			int port, String isolationTag, ElectrumRpc.Factory electrumFactory,
			SilentPaymentScanner.Fetcher spFetcher) {
		this(mnemonic, account, socksPort,
				ElectrumEndpoint.parse(host + ":" + port),
				ElectrumEndpoint.parse(host + ":" + port), isolationTag,
				electrumFactory, spFetcher);
	}

	BtcWallet(String mnemonic, int account, int socksPort,
			ElectrumEndpoint scanEndpoint, ElectrumEndpoint broadcastEndpoint,
			String isolationTag, ElectrumRpc.Factory electrumFactory,
			SilentPaymentScanner.Fetcher spFetcher) {
		this.mnemonic = mnemonic;
		this.account = account;
		this.socksPort = socksPort;
		this.scanEndpoint = scanEndpoint;
		this.broadcastEndpoint = broadcastEndpoint;
		this.isolationTag = isolationTag;
		this.electrumFactory = electrumFactory;
		this.spFetcher = spFetcher;
	}

	public String firstReceiveAddress() {
		return BtcKeys.address(mnemonic, account, 0);
	}

	public String receiveAddressAt(int index) {
		return BtcKeys.address(mnemonic, account, index);
	}

	public ScanResult scan() throws IOException {
		return doScan(-1, -1);
	}

	public ScanResult scanLight() throws IOException {
		if (lastReceiveUsed < 0) {
			return scan();
		}
		return doScan(Math.max(lastReceiveUsed + POLL_LOOKAHEAD,
				minReceiveProbe), lastChangeUsed + POLL_LOOKAHEAD);
	}

	private synchronized ScanResult doScan(int receiveBound, int changeBound)
			throws IOException {
		try (ElectrumRpc c = openScan()) {
			List<OwnedUtxo> utxos = new ArrayList<>();
			List<ElectrumClient.HistItem> history = new ArrayList<>();
			long[] balance = {0};

			int[] r = probeChain(c, false, receiveBound, utxos, history,
					balance);
			int freshReceive = r[0];
			int receiveProbed = r[1];
			int[] ch = probeChain(c, true, changeBound, utxos, history,
					balance);
			Set<String> seenTx = new java.util.HashSet<>();
			List<ElectrumClient.HistItem> dedup = new ArrayList<>();
			for (ElectrumClient.HistItem h : history) {
				if (seenTx.add(h.txHash)) {
					dedup.add(h);
				}
			}
			history.clear();
			history.addAll(dedup);
			int freshChange = ch[0];
			int changeProbed = ch[1];

			if (receiveBound < 0) {
				lastReceiveUsed = r[2];
				lastChangeUsed = ch[2];
			}

			Set<String> owned = BtcKeys.ownedAddresses(mnemonic, account,
					receiveProbed, changeProbed);

			long total = balance[0];
			Set<String> reserved = reconcilePending(c, utxos);
			if (!reserved.isEmpty()) {
				List<OwnedUtxo> spendable = new ArrayList<>();
				long available = 0;
				for (OwnedUtxo u : utxos) {
					if (reserved.contains(u.txHash + ":" + u.txPos)) {
						continue;
					}
					spendable.add(u);
					available += u.value;
				}
				utxos = spendable;
				total = available;
			}

			ScanResult result = new ScanResult(total,
					BtcKeys.address(mnemonic, account, freshReceive),
					freshReceive,
					BtcKeys.changeAddress(mnemonic, account, freshChange),
					history, utxos, owned);
			lastScan = result;
			return result;
		}
	}

	@Nullable
	public ScanResult cachedScan() {
		return lastScan;
	}

	public void invalidateCachedScan() {
		lastScan = null;
	}

	private int[] probeChain(ElectrumRpc c, boolean change, int bound,
			List<OwnedUtxo> utxos, @Nullable List<ElectrumClient.HistItem> hist,
			long[] balance) throws IOException {
		int fresh = -1;
		int probed = 0;
		int maxUsed = -1;
		int gap = 0;
		for (int i = 0; bound < 0
				? (gap < GAP_LIMIT || (!change && i <= minReceiveProbe))
				: i <= bound; i++) {
			probed = i + 1;
			String sh = change ? BtcKeys.changeScriptHash(mnemonic, account, i)
					: BtcKeys.scriptHash(mnemonic, account, i);
			List<ElectrumClient.HistItem> h = c.getHistory(sh);
			if (h.isEmpty()) {
				if (fresh < 0) {
					fresh = i;
				}
				gap++;
			} else {
				gap = 0;
				maxUsed = i;
				if (hist != null) {
					hist.addAll(h);
				}
				String addr = change
						? BtcKeys.changeAddress(mnemonic, account, i)
						: BtcKeys.address(mnemonic, account, i);
				com.professor.zerion.android.vault.wallet.btc.privacy.UtxoOrigin
						origin = change
						? com.professor.zerion.android.vault.wallet.btc.privacy
								.UtxoOrigin.CHANGE
						: com.professor.zerion.android.vault.wallet.btc.privacy
								.UtxoOrigin.RECEIVE;
				for (ElectrumClient.Utxo u : c.listUnspent(sh)) {
					utxos.add(new OwnedUtxo(u.txHash, u.txPos, u.value,
							change ? BtcKeys.changeKey(mnemonic, account, i)
									: BtcKeys.receiveKey(mnemonic, account, i),
							addr, origin));
					balance[0] += u.value;
				}
			}
		}
		if (fresh < 0) {
			fresh = bound < 0 ? 0 : bound + 1;
		}
		return new int[]{fresh, probed, maxUsed};
	}

	private Boolean confirmOnBroadcastEndpoint(String txid) {
		try (ElectrumRpc bc = openWithFallback(broadcastEndpoint,
				broadcastFallbacks, TorIsolation.broadcast(isolationTag))) {
			bc.getTransaction(txid);
			return Boolean.TRUE;
		} catch (ElectrumClient.ServerRejectedException e) {
			return Boolean.FALSE;
		} catch (IOException e) {
			return null;
		}
	}

	private Set<String> reconcilePending(ElectrumRpc c, List<OwnedUtxo> live) {
		List<PendingTx> all = pendingLog.all();
		Set<String> reserved = new java.util.HashSet<>();
		if (all.isEmpty()) {
			return reserved;
		}
		Set<String> liveOutpoints = new java.util.HashSet<>();
		for (OwnedUtxo u : live) {
			liveOutpoints.add(u.txHash + ":" + u.txPos);
		}
		long now = System.currentTimeMillis();
		for (PendingTx p : all) {
			boolean anyInputStillLive = false;
			for (String op : p.outpoints) {
				if (liveOutpoints.contains(op)) {
					anyInputStillLive = true;
					break;
				}
			}
			if (PendingTx.SENT.equals(p.state)) {
				if (anyInputStillLive
						&& now - p.createdAt < SENT_VISIBILITY_MS) {
					for (String op : p.outpoints) {
						if (liveOutpoints.contains(op)) {
							reserved.add(op);
						}
					}
				}
				continue;
			}
			if (PendingTx.FAILED.equals(p.state)) {
				continue;
			}
			Boolean onChain;
			try {
				c.getTransaction(p.txid);
				onChain = Boolean.TRUE;
			} catch (ElectrumClient.ServerRejectedException e) {
				onChain = Boolean.FALSE;
			} catch (IOException e) {
				onChain = null;
			}
			if (Boolean.TRUE.equals(onChain) || !anyInputStillLive) {
				pendingMisses.remove(p.txid);
				safePut(p.withState(PendingTx.SENT));
				continue;
			}
			if (Boolean.FALSE.equals(onChain)) {
				Integer prev = pendingMisses.get(p.txid);
				int misses = prev == null ? 1 : prev + 1;
				pendingMisses.put(p.txid, misses);
				if (misses >= PENDING_FAIL_MISSES
						&& now - p.createdAt > PENDING_GRACE_MS) {
					Boolean onBroadcast = confirmOnBroadcastEndpoint(p.txid);
					if (Boolean.TRUE.equals(onBroadcast)) {
						pendingMisses.remove(p.txid);
						safePut(p.withState(PendingTx.SENT));
						continue;
					}
					if (Boolean.FALSE.equals(onBroadcast)) {
						pendingMisses.remove(p.txid);
						safePut(p.withState(PendingTx.FAILED));
						continue;
					}
				}
			}
			reserved.addAll(p.outpoints);
		}
		return reserved;
	}

	public double feeRateSatPerVb(int blocks) throws IOException {
		try (ElectrumRpc c = openScan()) {
			return rateFor(c, blocks);
		}
	}

	public double[] feeOptions() throws IOException {
		try (ElectrumRpc c = openScan()) {
			double priority = rateFor(c, 1);
			double normal = rateFor(c, 3);
			double economy = rateFor(c, 6);
			if (normal > priority) {
				normal = priority;
			}
			if (economy > normal) {
				economy = normal;
			}
			return new double[]{economy, normal, priority};
		}
	}

	static double rateFor(ElectrumRpc c, int blocks) throws IOException {
		double btcPerKb = c.estimateFeeBtcPerKb(blocks);
		double rate = btcPerKb * 1e8 / 1000.0;
		if (rate < MIN_FEE_RATE) {
			rate = MIN_FEE_RATE;
		}
		if (rate > MAX_FEE_RATE) {
			rate = MAX_FEE_RATE;
		}
		return rate;
	}

	public List<TxSummary> history(ScanResult scan) throws IOException {
		try (ElectrumRpc c = openScan()) {
			int tip = c.blockHeight();
			LinkedHashMap<String, Integer> heights = new LinkedHashMap<>();
			for (ElectrumClient.HistItem h : scan.history) {
				int hh = h.height > 0 ? h.height : 0;
				Integer prev = heights.get(h.txHash);
				if (prev == null || (hh > 0 && (prev == 0 || hh < prev))) {
					heights.put(h.txHash, hh);
				}
			}

			Map<String, Transaction> parsed = new HashMap<>();
			Map<String, Long> ownedOutputs = new HashMap<>();
			for (String txid : heights.keySet()) {
				Transaction t = fetchTx(c, txid);
				parsed.put(txid, t);
				List<TransactionOutput> outs = t.getOutputs();
				for (int i = 0; i < outs.size(); i++) {
					String a = addressOf(outs.get(i).getScriptPubKey());
					if (a != null && scan.ownedAddresses.contains(a)) {
						ownedOutputs.put(txid + ':' + i, outs.get(i).getValue().value);
					}
				}
			}

			List<TxSummary> out = new ArrayList<>();
			for (Map.Entry<String, Integer> e : heights.entrySet()) {
				Transaction t = parsed.get(e.getKey());
				long received = 0;
				List<TransactionOutput> outs = t.getOutputs();
				for (int i = 0; i < outs.size(); i++) {
					Long v = ownedOutputs.get(e.getKey() + ':' + i);
					if (v != null) {
						received += v;
					}
				}
				long spent = 0;
				for (TransactionInput in : t.getInputs()) {
					if (in.isCoinBase()) {
						continue;
					}
					TransactionOutPoint op = in.getOutpoint();
					Long v = ownedOutputs.get(
							op.getHash().toString() + ':' + op.getIndex());
					if (v != null) {
						spent += v;
					}
				}
				int height = e.getValue();
				int conf = height > 0 ? Math.max(0, tip - height + 1) : 0;
				out.add(new TxSummary(e.getKey(), height, received - spent,
						conf, true, height > 0 ? STATE_CONFIRMED : STATE_PENDING));
			}
			out.sort((a, b) -> {
				boolean ap = a.height <= 0;
				boolean bp = b.height <= 0;
				if (ap != bp) {
					return ap ? -1 : 1;
				}
				return Integer.compare(b.height, a.height);
			});
			return out;
		}
	}

	public List<TxSummary> pendingSummaries() {
		List<TxSummary> out = new ArrayList<>();
		for (PendingTx p : pendingLog.all()) {
			String state;
			if (PendingTx.BROADCASTING.equals(p.state)) {
				state = STATE_BROADCASTING;
			} else if (PendingTx.POSSIBLY_SENT.equals(p.state)) {
				state = STATE_POSSIBLY_SENT;
			} else if (PendingTx.SENT.equals(p.state)) {
				state = STATE_PENDING;
			} else if (PendingTx.FAILED.equals(p.state)) {
				state = STATE_FAILED;
			} else {
				continue;
			}
			out.add(new TxSummary(p.txid, 0, p.netSat, 0, true, state));
		}
		return out;
	}

	public static List<TxSummary> mergePending(List<TxSummary> electrum,
			List<TxSummary> pending) {
		Set<String> seen = new java.util.HashSet<>();
		for (TxSummary t : electrum) {
			seen.add(t.txid);
		}
		List<TxSummary> out = new ArrayList<>();
		for (TxSummary p : pending) {
			if (!seen.contains(p.txid)) {
				out.add(p);
			}
		}
		out.addAll(electrum);
		return out;
	}

	private Transaction fetchTx(ElectrumRpc c, String txid)
			throws IOException {
		Transaction t = txCache.get(txid);
		if (t == null) {
			t = new Transaction(BtcKeys.PARAMS,
					Utils.HEX.decode(c.getTransaction(txid)));
			txCache.put(txid, t);
		}
		return t;
	}

	@Nullable
	private static String addressOf(Script script) {
		try {
			return script.getToAddress(BtcKeys.PARAMS, true).toString();
		} catch (Exception e) {
			return null;
		}
	}

	public List<PrivacyMeta> coinControl() throws IOException {
		ScanResult scan = scan();
		return PrivacyEngine.classify(toViews(scan.utxos), privacyStore);
	}

	private static List<PrivacyEngine.UtxoView> toViews(List<OwnedUtxo> utxos) {
		List<PrivacyEngine.UtxoView> views = new ArrayList<>();
		for (OwnedUtxo u : utxos) {
			views.add(new PrivacyEngine.UtxoView(u.txHash + ":" + u.txPos,
					u.value, u.address, u.origin));
		}
		return views;
	}

	private List<OwnedUtxo> orderedCandidates(List<OwnedUtxo> utxos,
			long amountSat, @Nullable Set<String> manualOutpoints)
			throws IOException {
		Map<String, OwnedUtxo> byOutpoint = new LinkedHashMap<>();
		for (OwnedUtxo u : utxos) {
			byOutpoint.put(u.txHash + ":" + u.txPos, u);
		}
		List<PrivacyMeta> metas =
				PrivacyEngine.classify(toViews(utxos), privacyStore);
		List<PrivacyMeta> ordered;
		if (manualOutpoints != null && !manualOutpoints.isEmpty()) {
			ordered = new ArrayList<>();
			for (PrivacyMeta m : metas) {
				if (manualOutpoints.contains(m.outpoint)) {
					if (m.frozen) {
						throw new IOException("A frozen coin cannot be spent");
					}
					ordered.add(m);
				}
			}
			if (ordered.size() != manualOutpoints.size()) {
				throw new IOException("A selected coin is no longer available");
			}
			ordered.sort(java.util.Comparator.comparingLong(
					(PrivacyMeta m) -> m.valueSat).reversed());
		} else {
			ordered = PrivacyEngine.orderForSelection(metas, privacyPolicy,
					amountSat);
		}
		List<OwnedUtxo> out = new ArrayList<>();
		for (PrivacyMeta m : ordered) {
			OwnedUtxo u = byOutpoint.get(m.outpoint);
			if (u != null) {
				out.add(u);
			}
		}
		return out;
	}

	public static final class SendPlan {
		public final String toAddress;
		public final long amountSat;
		public final long feeSat;
		public final long netSat;
		public final boolean sweep;
		public final List<String> outpoints;
		public final String fingerprint;
		final List<BtcTx.Input> inputs;
		final List<BtcTx.Output> outputs;
		final List<PrivacyMeta> inputMetas;
		final boolean hasChange;
		@Nullable
		final String changeCluster;
		final Set<String> reusedOutpoints;
		final boolean manual;

		SendPlan(String toAddress, long amountSat, long feeSat, long netSat,
				boolean sweep, List<String> outpoints, String fingerprint,
				List<BtcTx.Input> inputs, List<BtcTx.Output> outputs,
				List<PrivacyMeta> inputMetas, boolean hasChange,
				@Nullable String changeCluster, Set<String> reusedOutpoints,
				boolean manual) {
			this.toAddress = toAddress;
			this.amountSat = amountSat;
			this.feeSat = feeSat;
			this.netSat = netSat;
			this.sweep = sweep;
			this.outpoints = outpoints;
			this.fingerprint = fingerprint;
			this.inputs = inputs;
			this.outputs = outputs;
			this.inputMetas = inputMetas;
			this.hasChange = hasChange;
			this.changeCluster = changeCluster;
			this.reusedOutpoints = reusedOutpoints;
			this.manual = manual;
		}
	}

	public String send(String toAddress, long amountSat, double feeRate,
			boolean sweep) throws IOException {
		return send(toAddress, amountSat, feeRate, sweep, null, false);
	}

	public String send(String toAddress, long amountSat, double feeRate,
			boolean sweep, @Nullable Set<String> manualOutpoints)
			throws IOException {
		return send(toAddress, amountSat, feeRate, sweep, manualOutpoints,
				false);
	}

	public String send(String toAddress, long amountSat, double feeRate,
			boolean sweep, @Nullable Set<String> manualOutpoints,
			boolean allowClusterMerge) throws IOException {
		return signPlan(planSend(toAddress, amountSat, feeRate, sweep,
				manualOutpoints, allowClusterMerge));
	}

	public SendPlan planSend(String toAddress, long amountSat, double feeRate,
			boolean sweep, @Nullable Set<String> manualOutpoints,
			boolean allowClusterMerge) throws IOException {
		return planSend(scan(), toAddress, amountSat, feeRate, sweep,
				manualOutpoints, allowClusterMerge);
	}

	public SendPlan planSend(ScanResult scan, String toAddress, long amountSat,
			double feeRate, boolean sweep, @Nullable Set<String> manualOutpoints,
			boolean allowClusterMerge) throws IOException {
		if (!BtcKeys.isValidAddress(toAddress)) {
			throw new IOException("Not a valid Bitcoin address");
		}
		double rate = Math.max(feeRate, 1.0);
		List<OwnedUtxo> sorted =
				orderedCandidates(scan.utxos, amountSat, manualOutpoints);

		List<BtcTx.Input> inputs = new ArrayList<>();
		List<BtcTx.Output> outputs = new ArrayList<>();
		long inSat = 0;
		long feeSat;
		long externalSat;
		long changeSat = 0;

		if (sweep) {
			for (OwnedUtxo u : sorted) {
				inputs.add(toInput(u));
				inSat += u.value;
			}
			if (inputs.isEmpty()) {
				throw new IOException("No spendable coins");
			}
			feeSat = (long) Math.ceil(
					BtcTx.estimateVBytes(inputs.size(), 1) * rate);
			externalSat = inSat - feeSat;
			if (externalSat <= DUST) {
				throw new IOException("Balance is too low to send after the fee");
			}
			outputs.add(new BtcTx.Output(toAddress, externalSat));
		} else {
			if (amountSat <= DUST) {
				throw new IOException("Amount is below the dust limit");
			}
			externalSat = amountSat;
			boolean useAllInputs =
					manualOutpoints != null && !manualOutpoints.isEmpty();
			if (useAllInputs) {
				for (OwnedUtxo u : sorted) {
					inputs.add(toInput(u));
					inSat += u.value;
				}
				feeSat = (long) Math.ceil(
						BtcTx.estimateVBytes(inputs.size(), 2) * rate);
				if (inSat < amountSat + feeSat) {
					throw new IOException(
							"Insufficient balance for amount plus fee");
				}
			} else {
				feeSat = 0;
				while (true) {
					int numInputs = Math.max(inputs.size(), 1);
					feeSat = (long) Math.ceil(
							BtcTx.estimateVBytes(numInputs, 2) * rate);
					if (inSat >= amountSat + feeSat) {
						break;
					}
					if (inputs.size() >= sorted.size()) {
						throw new IOException(
								"Insufficient balance for amount plus fee");
					}
					OwnedUtxo next = sorted.get(inputs.size());
					inputs.add(toInput(next));
					inSat += next.value;
				}
			}
			outputs.add(new BtcTx.Output(toAddress, amountSat));
			changeSat = inSat - amountSat - feeSat;
			if (changeSat > DUST) {
				outputs.add(new BtcTx.Output(scan.changeAddress, changeSat));
				recordChangeIsolation(scan, inputs);
			} else {
				changeSat = 0;
			}
		}

		if (privacyPolicy == PrivacyEngine.Policy.STRICT
				&& !allowClusterMerge) {
			Set<String> clusters = clustersOfInputs(scan, inputs);
			if (clusters.size() > 1) {
				throw new com.professor.zerion.android.vault.wallet.btc.privacy
						.PrivacyMergeException(clusters.size());
			}
		}

		List<String> outpoints = new ArrayList<>();
		for (BtcTx.Input in : inputs) {
			outpoints.add(in.txHash + ":" + in.txPos);
		}
		long netSat = changeSat - inSat;
		String fingerprint = planFingerprint(outpoints, outputs);

		List<PrivacyMeta> allMetas =
				PrivacyEngine.classify(toViews(scan.utxos), privacyStore);
		Map<String, PrivacyMeta> byOp = new HashMap<>();
		Map<String, Integer> addrCount = new HashMap<>();
		for (PrivacyMeta m : allMetas) {
			byOp.put(m.outpoint, m);
			Integer n = addrCount.get(m.address);
			addrCount.put(m.address, n == null ? 1 : n + 1);
		}
		List<PrivacyMeta> inputMetas = new ArrayList<>();
		Set<String> reused = new java.util.HashSet<>();
		for (String op : outpoints) {
			PrivacyMeta m = byOp.get(op);
			if (m != null) {
				inputMetas.add(m);
				Integer n = addrCount.get(m.address);
				if (m.origin != com.professor.zerion.android.vault.wallet.btc
						.privacy.UtxoOrigin.SILENT_PAYMENT && n != null
						&& n > 1) {
					reused.add(op);
				}
			}
		}
		Set<String> inClusters = clustersOfInputs(scan, inputs);
		boolean hasChange = changeSat > 0;
		String changeCluster = hasChange && inClusters.size() == 1
				? inClusters.iterator().next() : null;
		boolean manual =
				manualOutpoints != null && !manualOutpoints.isEmpty();

		return new SendPlan(toAddress, externalSat, feeSat, netSat, sweep,
				outpoints, fingerprint, inputs, outputs, inputMetas, hasChange,
				changeCluster, reused, manual);
	}

	public com.professor.zerion.android.vault.wallet.btc.privacy
			.PrivacyAnalyzer.Analysis analyzePlan(SendPlan plan) {
		try {
			List<com.professor.zerion.android.vault.wallet.btc.privacy
					.PrivacyAnalyzer.InputCoin> coins = new ArrayList<>();
			for (PrivacyMeta m : plan.inputMetas) {
				coins.add(new com.professor.zerion.android.vault.wallet.btc
						.privacy.PrivacyAnalyzer.InputCoin(m.outpoint, m.address,
						m.origin, m.clusterId, m.valueSat,
						plan.reusedOutpoints.contains(m.outpoint)));
			}
			com.professor.zerion.android.vault.wallet.btc.privacy
					.PrivacyAnalyzer.AnalysisInput in =
					new com.professor.zerion.android.vault.wallet.btc.privacy
							.PrivacyAnalyzer.AnalysisInput(coins, plan.hasChange,
							plan.changeCluster, plan.manual,
							plan.amountSat + plan.feeSat);
			return com.professor.zerion.android.vault.wallet.btc.privacy
					.PrivacyAnalyzer.analyze(in);
		} catch (Throwable t) {
			return com.professor.zerion.android.vault.wallet.btc.privacy
					.PrivacyAnalyzer.Analysis.unavailable();
		}
	}

	public String signPlan(SendPlan plan) throws IOException {
		String rawHex = BtcTx.buildAndSign(plan.inputs, plan.outputs);
		return broadcastTracked(rawHex, plan.outpoints, plan.netSat);
	}

	private static String planFingerprint(List<String> outpoints,
			List<BtcTx.Output> outputs) {
		List<String> ops = new ArrayList<>(outpoints);
		java.util.Collections.sort(ops);
		StringBuilder sb = new StringBuilder();
		for (String o : ops) {
			sb.append(o).append(',');
		}
		sb.append('#');
		for (BtcTx.Output o : outputs) {
			sb.append(o.address).append(':').append(o.valueSat).append(',');
		}
		return TlsTrust.sha256Hex(
				sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private Set<String> clustersOfInputs(ScanResult scan,
			List<BtcTx.Input> inputs) {
		Map<String, String> op2cluster = new HashMap<>();
		for (PrivacyMeta m : PrivacyEngine.classify(toViews(scan.utxos),
				privacyStore)) {
			op2cluster.put(m.outpoint, m.clusterId);
		}
		Set<String> clusters = new java.util.HashSet<>();
		for (BtcTx.Input in : inputs) {
			String c = op2cluster.get(in.txHash + ":" + in.txPos);
			if (c != null) {
				clusters.add(c);
			}
		}
		return clusters;
	}

	private void recordChangeIsolation(ScanResult scan,
			List<BtcTx.Input> inputs) {
		if (clustersOfInputs(scan, inputs).size() == 1) {
			privacyStore.putOriginHint(scan.changeAddress,
					clustersOfInputs(scan, inputs).iterator().next());
		}
	}

	private String broadcastTracked(String rawHex, List<String> outpoints,
			long netSat) throws IOException {
		String localTxid = txidOf(rawHex);
		PendingTx pending = new PendingTx(
				java.util.UUID.randomUUID().toString(), localTxid, rawHex,
				outpoints, PendingTx.BROADCASTING, System.currentTimeMillis(),
				netSat);
		pendingLog.put(pending);
		try (ElectrumRpc c = openWithFallback(broadcastEndpoint,
				broadcastFallbacks, TorIsolation.broadcast(isolationTag))) {
			String accepted = c.broadcast(rawHex);
			if (!localTxid.equalsIgnoreCase(accepted)) {
				safePut(pending.withState(PendingTx.POSSIBLY_SENT));
				throw new BroadcastUncertainException(localTxid,
						new IOException("broadcast txid mismatch"));
			}
			safePut(pending.withState(PendingTx.SENT));
			return localTxid;
		} catch (ElectrumClient.ServerRejectedException e) {
			safePut(pending.withState(PendingTx.FAILED));
			throw e;
		} catch (IOException e) {
			safePut(pending.withState(PendingTx.POSSIBLY_SENT));
			throw new BroadcastUncertainException(localTxid, e);
		}
	}

	private void safePut(PendingTx tx) {
		try {
			pendingLog.put(tx);
		} catch (Throwable ignored) {
		}
	}

	private static String txidOf(String rawHex) {
		return new Transaction(BtcKeys.PARAMS, Utils.HEX.decode(rawHex))
				.getTxId().toString();
	}

	private static BtcTx.Input toInput(OwnedUtxo u) {
		return new BtcTx.Input(u.txHash, u.txPos, u.value, u.key);
	}

	public static final class SpScanResult {
		public final int scannedTo;
		public final List<SilentPaymentScanner.Found> found;

		SpScanResult(int scannedTo, List<SilentPaymentScanner.Found> found) {
			this.scannedTo = scannedTo;
			this.found = found;
		}
	}

	public String silentPaymentAddress() {
		return BtcKeys.silentPaymentAddress(mnemonic, account);
	}

	public SpScanResult scanSilentPayments(String oracle, int fromHeight,
			int maxBlocks) throws IOException {
		if (!silentPaymentsEnabled) {
			return new SpScanResult(Math.max(fromHeight - 1, 0),
					new ArrayList<>());
		}
		Integer tip = SilentPaymentScanner.tipHeight(oracle,
				TorIsolation.silentPayment(isolationTag), spFetcher);
		if (tip == null) {
			throw new IOException("Could not reach the oracle over Tor");
		}
		byte[] scanPriv = BtcKeys.silentScanPriv(mnemonic, account);
		byte[] spendPub = BtcKeys.silentSpendPub(mnemonic, account);
		int from = Math.max(fromHeight, 1);
		int cap = Math.min(tip, from + maxBlocks - 1);
		List<SilentPaymentScanner.Found> found = new ArrayList<>();
		int scannedTo = from - 1;
		for (int h = from; h <= cap; h++) {
			found.addAll(SilentPaymentScanner.scanBlock(oracle, h, scanPriv,
					spendPub, TorIsolation.silentPayment(isolationTag),
					spFetcher));
			scannedTo = h;
		}
		return new SpScanResult(scannedTo, found);
	}

	public String sweepSilentPayments(List<SilentPaymentScanner.Found> found,
			String toAddress, double feeRate) throws IOException {
		if (!BtcKeys.isValidAddress(toAddress)) {
			throw new IOException("Not a valid Bitcoin address");
		}
		if (!SilentPayment.selfTest() || !TaprootSign.selfTest()) {
			throw new IOException("Silent Payments self-check failed");
		}
		java.math.BigInteger spendPriv =
				BtcKeys.silentSpendPriv(mnemonic, account);
		java.math.BigInteger curveN = org.bitcoinj.core.ECKey.CURVE.getN();
		double rate = Math.max(feeRate, 1.0);
		String rawHex;
		List<String> outpoints = new ArrayList<>();
		long spNetSat = 0;
		try (ElectrumRpc c = openScan()) {
			List<BtcTx.TaprootInput> inputs = new ArrayList<>();
			long sumIn = 0;
			for (SilentPaymentScanner.Found u : found) {
				byte[] spk = new byte[2 + u.xonly.length];
				spk[0] = 0x51;
				spk[1] = 0x20;
				System.arraycopy(u.xonly, 0, spk, 2, u.xonly.length);
				String sh = BtcKeys.scriptHashOfBytes(spk);
				boolean stillUnspent = false;
				for (ElectrumClient.Utxo x : c.listUnspent(sh)) {
					if (x.txHash.equals(u.txid) && x.txPos == u.vout) {
						stillUnspent = true;
						break;
					}
				}
				if (!stillUnspent) {
					continue;
				}
				java.math.BigInteger priv = spendPriv.add(
						new java.math.BigInteger(1, u.tweak)).mod(curveN);
				inputs.add(new BtcTx.TaprootInput(u.txid, u.vout, u.valueSat,
						spk, priv));
				outpoints.add(u.txid + ":" + u.vout);
				sumIn += u.valueSat;
			}
			if (inputs.isEmpty()) {
				throw new IOException("No unspent silent payments found");
			}
			int vbytes = 11 + inputs.size() * 58 + 31;
			long fee = (long) Math.ceil(vbytes * rate);
			long swept = sumIn - fee;
			if (swept <= DUST) {
				throw new IOException("Amount is too low to send after the fee");
			}
			List<BtcTx.Output> outputs = new ArrayList<>();
			outputs.add(new BtcTx.Output(toAddress, swept));
			rawHex = BtcTx.buildAndSignTaproot(inputs, outputs);
			spNetSat = -sumIn;
		}
		return broadcastTracked(rawHex, outpoints, spNetSat);
	}
}
