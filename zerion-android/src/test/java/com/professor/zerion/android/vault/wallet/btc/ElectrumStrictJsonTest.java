package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class ElectrumStrictJsonTest {

	private static final String A =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String B =
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	@Test
	public void nestedObjectDoesNotHideAHistoryItem() throws IOException {
		String r = "{\"id\":1,\"result\":[{\"tx_hash\":\"" + A
				+ "\",\"status\":{\"height\":5},\"height\":800000}]}";
		List<ElectrumClient.HistItem> h = ElectrumClient.parseHistory(r);
		assertEquals(1, h.size());
		assertEquals(A, h.get(0).txHash);
		assertEquals(800000, h.get(0).height);
	}

	@Test
	public void nestedObjectDoesNotHideAnUnspentOutput() throws IOException {
		String r = "{\"id\":1,\"result\":[{\"tx_hash\":\"" + A
				+ "\",\"tx_pos\":1,\"meta\":{\"value\":999},\"value\":5000,"
				+ "\"height\":10}]}";
		List<ElectrumClient.Utxo> u = ElectrumClient.parseUnspent(r);
		assertEquals(1, u.size());
		assertEquals(5000L, u.get(0).value);
		assertEquals(1, u.get(0).txPos);
		assertEquals(10, u.get(0).height);
	}

	@Test(expected = IOException.class)
	public void duplicatedHeightRefusesTheReply() throws IOException {
		ElectrumClient.parseHistory("{\"id\":1,\"result\":[{\"height\":5,"
				+ "\"tx_hash\":\"" + A + "\",\"height\":800000}]}");
	}

	@Test(expected = IOException.class)
	public void duplicatedValueRefusesTheReply() throws IOException {
		ElectrumClient.parseUnspent("{\"id\":1,\"result\":[{\"tx_hash\":\""
				+ A + "\",\"tx_pos\":0,\"value\":1,"
				+ "\"value\":2100000000000000}]}");
	}

	@Test
	public void feeComesFromTheTopLevelResult() {
		assertEquals(0.0001, ElectrumClient.parseFee(
				"{\"id\":1,\"meta\":{\"result\":0.5},\"result\":0.0001}"),
				1e-12);
		assertEquals(0.0, ElectrumClient.parseFee(
				"{\"id\":1,\"result\":0.0001,\"result\":0.5}"), 0.0);
		assertEquals(0.0, ElectrumClient.parseFee(
				"{\"id\":1,\"result\":{\"result\":0.5}}"), 0.0);
	}

	@Test
	public void broadcastTxidComesFromTheTopLevelResult() throws IOException {
		assertEquals(A, ElectrumClient.parseBroadcast("{\"id\":1,\"meta\":"
				+ "{\"result\":\"" + B + "\"},\"result\":\"" + A + "\"}"));
	}

	@Test(expected = IOException.class)
	public void broadcastWithDuplicatedResultIsRefused() throws IOException {
		ElectrumClient.parseBroadcast("{\"id\":1,\"result\":\"" + A
				+ "\",\"result\":\"" + B + "\"}");
	}

	@Test
	public void heightComesFromTheResultObject() throws IOException {
		assertEquals(800000, ElectrumClient.parseHeight("{\"id\":1,\"meta\":"
				+ "{\"height\":1},\"result\":{\"hex\":\"00\",\"height\":800000}}"));
	}

	@Test(expected = IOException.class)
	public void duplicatedHeightInTheTipIsRefused() throws IOException {
		ElectrumClient.parseHeight(
				"{\"id\":1,\"result\":{\"height\":1,\"height\":800000}}");
	}

	@Test(expected = IOException.class)
	public void missingTipHeightIsRefused() throws IOException {
		ElectrumClient.parseHeight("{\"id\":1,\"result\":{\"hex\":\"00\"}}");
	}

	@Test
	public void balanceReadsOnlyTheResultObject() throws IOException {
		assertEquals(150L, ElectrumClient.parseBalance("{\"id\":1,\"meta\":"
				+ "{\"confirmed\":999999},\"result\":{\"confirmed\":100,"
				+ "\"unconfirmed\":50}}"));
	}

	@Test(expected = IOException.class)
	public void duplicatedConfirmedBalanceIsRefused() throws IOException {
		ElectrumClient.parseBalance("{\"id\":1,\"result\":{\"confirmed\":1,"
				+ "\"confirmed\":2100000000000000}}");
	}

	@Test
	public void escapedKeyInsideAStringIsNotAField() throws IOException {
		String r = "{\"id\":1,\"result\":[{\"tx_hash\":\"" + A
				+ "\",\"note\":\"\\\"height\\\":5\",\"height\":7}]}";
		List<ElectrumClient.HistItem> h = ElectrumClient.parseHistory(r);
		assertEquals(1, h.size());
		assertEquals(7, h.get(0).height);
	}

	@Test
	public void outOfRangeOrFractionalNumbersAreNotTrusted() throws IOException {
		String r = "{\"id\":1,\"result\":[{\"tx_hash\":\"" + A
				+ "\",\"tx_pos\":1.5,\"value\":1},{\"tx_hash\":\"" + B
				+ "\",\"tx_pos\":0,\"value\":2100000000000001}]}";
		assertTrue(ElectrumClient.parseUnspent(r).isEmpty());
	}
}
