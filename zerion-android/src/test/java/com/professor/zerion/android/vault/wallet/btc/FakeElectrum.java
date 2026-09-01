package com.professor.zerion.android.vault.wallet.btc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FakeElectrum implements ElectrumRpc {

	final Map<String, List<ElectrumClient.HistItem>> history = new HashMap<>();
	final Map<String, List<ElectrumClient.Utxo>> unspent = new HashMap<>();
	final Map<String, String> txs = new HashMap<>();
	final List<String> broadcasts = new ArrayList<>();
	int tip = 800000;
	int getHistoryCalls = 0;
	double feeBtcPerKb = 0.0002;

	void resetCounters() {
		getHistoryCalls = 0;
	}
	boolean rejectBroadcast = false;
	IOException broadcastError = null;
	boolean returnWrongTxid = false;

	String lastBroadcastTxid() {
		return new org.bitcoinj.core.Transaction(BtcKeys.PARAMS,
				org.bitcoinj.core.Utils.HEX.decode(
						broadcasts.get(broadcasts.size() - 1)))
				.getTxId().toString();
	}

	void addUtxo(String scriptHash, String txid, int vout, long value) {
		history.computeIfAbsent(scriptHash, k -> new ArrayList<>())
				.add(new ElectrumClient.HistItem(txid, tip - 3));
		unspent.computeIfAbsent(scriptHash, k -> new ArrayList<>())
				.add(new ElectrumClient.Utxo(txid, vout, tip - 3, value));
	}

	void addHistoryOnly(String scriptHash, String txid) {
		history.computeIfAbsent(scriptHash, k -> new ArrayList<>())
				.add(new ElectrumClient.HistItem(txid, tip - 3));
	}

	@Override
	public int blockHeight() {
		return tip;
	}

	@Override
	public List<ElectrumClient.HistItem> getHistory(String scriptHash) {
		getHistoryCalls++;
		List<ElectrumClient.HistItem> h = history.get(scriptHash);
		return h == null ? new ArrayList<>() : new ArrayList<>(h);
	}

	@Override
	public List<ElectrumClient.Utxo> listUnspent(String scriptHash) {
		List<ElectrumClient.Utxo> u = unspent.get(scriptHash);
		return u == null ? new ArrayList<>() : new ArrayList<>(u);
	}

	boolean transportErrorOnGetTransaction = false;

	@Override
	public String getTransaction(String txid) throws IOException {
		if (transportErrorOnGetTransaction) {
			throw new IOException("connection reset");
		}
		String t = txs.get(txid);
		if (t == null) {
			throw new ElectrumClient.ServerRejectedException("no tx " + txid);
		}
		return t;
	}

	@Override
	public String broadcast(String rawHex) throws IOException {
		broadcasts.add(rawHex);
		if (broadcastError != null) {
			throw broadcastError;
		}
		if (rejectBroadcast) {
			throw new IOException("Broadcast rejected: bad-txns");
		}
		if (returnWrongTxid) {
			return "2222222222222222222222222222222222222222"
					+ "222222222222222222222222";
		}
		return new org.bitcoinj.core.Transaction(BtcKeys.PARAMS,
				org.bitcoinj.core.Utils.HEX.decode(rawHex))
				.getTxId().toString();
	}

	@Override
	public double estimateFeeBtcPerKb(int blocks) {
		return feeBtcPerKb;
	}

	@Override
	public void close() {
	}

	static final class RecordingFactory implements ElectrumRpc.Factory {
		final FakeElectrum backend;
		String lastHost;
		int lastPort;
		int lastSocksPort;
		String lastIsolationTag;
		int opens = 0;
		final List<String> tags = new ArrayList<>();
		final List<ElectrumEndpoint> endpoints = new ArrayList<>();

		RecordingFactory(FakeElectrum backend) {
			this.backend = backend;
		}

		@Override
		public ElectrumRpc open(ElectrumEndpoint endpoint, int socksPort,
				String isolationTag) {
			this.lastHost = endpoint.host;
			this.lastPort = endpoint.port;
			this.lastSocksPort = socksPort;
			this.lastIsolationTag = isolationTag;
			this.opens++;
			this.tags.add(isolationTag);
			this.endpoints.add(endpoint);
			return backend;
		}
	}
}
