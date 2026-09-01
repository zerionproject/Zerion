package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

@NotNullByDefault
public interface ElectrumRpc extends Closeable {

	int blockHeight() throws IOException;

	List<ElectrumClient.HistItem> getHistory(String scriptHash)
			throws IOException;

	List<ElectrumClient.Utxo> listUnspent(String scriptHash) throws IOException;

	String getTransaction(String txid) throws IOException;

	String broadcast(String rawHex) throws IOException;

	double estimateFeeBtcPerKb(int blocks) throws IOException;

	@Override
	void close();

	@NotNullByDefault
	interface Factory {
		ElectrumRpc open(ElectrumEndpoint endpoint, int socksPort,
				String isolationTag) throws IOException;
	}
}
