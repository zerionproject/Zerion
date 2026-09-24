package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import static org.junit.Assert.assertFalse;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class ElectrumParserTest {

	private static final String TXID =
			"1111111111111111111111111111111111111111111111111111111111111111";

	@Test
	public void parsesHistory() {
		String resp = "{\"jsonrpc\":\"2.0\",\"result\":[{\"tx_hash\":\""
				+ TXID + "\",\"height\":800000},{\"tx_hash\":\"" + TXID
				+ "\",\"height\":0}],\"id\":1}";
		List<ElectrumClient.HistItem> items = ElectrumClient.parseHistory(resp);
		assertEquals(2, items.size());
		assertEquals(800000, items.get(0).height);
		assertEquals(0, items.get(1).height);
	}

	@Test
	public void historyMissingHashIsSkipped() {
		String resp = "{\"result\":[{\"height\":800000}]}";
		assertTrue(ElectrumClient.parseHistory(resp).isEmpty());
	}

	@Test
	public void historyGarbageIsEmptyNotCrash() {
		assertTrue(ElectrumClient.parseHistory("total garbage").isEmpty());
		assertTrue(ElectrumClient.parseHistory("").isEmpty());
		assertTrue(ElectrumClient.parseHistory("{}").isEmpty());
	}

	/** BTC-08: a hash that is not 64 hex characters never reaches a request. */
	@Test
	public void historyEntryWithMalformedHashIsSkipped() {
		String r = "{\"result\":[{\"tx_hash\":\"zz\",\"height\":1},"
				+ "{\"tx_hash\":\"" + "a".repeat(64) + "\",\"height\":2},"
				+ "{\"tx_hash\":\"\\\"],\\\"method\\\":\\\"x\",\"height\":3}]}";
		List<ElectrumClient.HistItem> h = ElectrumClient.parseHistory(r);
		assertEquals(1, h.size());
		assertEquals("a".repeat(64), h.get(0).txHash);
		assertFalse(ElectrumClient.isTxid("a".repeat(63)));
		assertFalse(ElectrumClient.isTxid("a".repeat(64) + "\""));
		assertTrue(ElectrumClient.isTxid("A".repeat(64)));
	}

	@Test
	public void unspentEntryWithMalformedFieldsIsSkipped() {
		String good = "b".repeat(64);
		String r = "{\"result\":[{\"tx_hash\":\"nothex\",\"tx_pos\":0,\"value\":1},"
				+ "{\"tx_hash\":\"" + good + "\",\"tx_pos\":-1,\"value\":1},"
				+ "{\"tx_hash\":\"" + good + "\",\"tx_pos\":0,\"value\":-5},"
				+ "{\"tx_hash\":\"" + good + "\",\"tx_pos\":1,\"value\":7}]}";
		List<ElectrumClient.Utxo> u = ElectrumClient.parseUnspent(r);
		assertEquals(1, u.size());
		assertEquals(1, u.get(0).txPos);
		assertEquals(7, u.get(0).value);
	}

	@Test
	public void listsAreBounded() {
		StringBuilder sb = new StringBuilder("{\"result\":[");
		for (int i = 0; i < ElectrumClient.MAX_LIST_ITEMS + 100; i++) {
			if (i > 0) sb.append(',');
			sb.append("{\"tx_hash\":\"").append(String.format("%064x", i))
					.append("\",\"height\":1}");
		}
		sb.append("]}");
		assertEquals(ElectrumClient.MAX_LIST_ITEMS,
				ElectrumClient.parseHistory(sb.toString()).size());
	}

	@Test
	public void parsesUnspent() {
		String resp = "{\"result\":[{\"tx_hash\":\"" + TXID
				+ "\",\"tx_pos\":2,\"height\":799990,\"value\":123456}]}";
		List<ElectrumClient.Utxo> u = ElectrumClient.parseUnspent(resp);
		assertEquals(1, u.size());
		assertEquals(2, u.get(0).txPos);
		assertEquals(123456L, u.get(0).value);
	}

	@Test
	public void unspentMalformedIsSkipped() {
		String resp = "{\"result\":[{\"tx_pos\":2,\"value\":100}]}";
		assertTrue(ElectrumClient.parseUnspent(resp).isEmpty());
	}

	@Test
	public void parsesFee() {
		assertEquals(0.00012, ElectrumClient.parseFee(
				"{\"result\":0.00012,\"id\":1}"), 1e-12);
	}

	@Test
	public void feeMissingOrBadIsZero() {
		assertEquals(0.0, ElectrumClient.parseFee("{\"id\":1}"), 0.0);
		assertEquals(0.0, ElectrumClient.parseFee("{\"result\":\"oops\"}"), 0.0);
		assertEquals(0.0, ElectrumClient.parseFee("garbage"), 0.0);
	}

	@Test
	public void broadcastAcceptsTxid() throws IOException {
		assertEquals(TXID, ElectrumClient.parseBroadcast(
				"{\"result\":\"" + TXID + "\",\"id\":1}"));
	}

	@Test
	public void broadcastRejectsNonTxid() {
		assertThrows(IOException.class, () -> ElectrumClient.parseBroadcast(
				"{\"result\":\"not-a-txid\"}"));
	}

	@Test
	public void broadcastNullResultThrows() {
		assertThrows(IOException.class, () -> ElectrumClient.parseBroadcast(
				"{\"result\":null,\"error\":\"bad\"}"));
	}
}
