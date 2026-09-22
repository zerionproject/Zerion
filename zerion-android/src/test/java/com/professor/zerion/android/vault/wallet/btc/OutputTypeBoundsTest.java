package com.professor.zerion.android.vault.wallet.btc;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A2-BTC-04 and A2-BTC-05: the fee estimate and the dust threshold follow
 * the destination's script type instead of assuming every output is P2WPKH.
 */
public class OutputTypeBoundsTest {

	private static final String P2PKH = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2";
	private static final String P2SH = "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy";
	private static final String P2WPKH =
			"bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq";
	private static final String P2WSH =
			"bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3";
	private static final String P2TR =
			"bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0";

	@Test
	public void outputSizesFollowTheScriptType() {
		assertEquals(34, BtcTx.outputVBytes(P2PKH));
		assertEquals(32, BtcTx.outputVBytes(P2SH));
		assertEquals(31, BtcTx.outputVBytes(P2WPKH));
		assertEquals(43, BtcTx.outputVBytes(P2WSH));
		assertEquals(43, BtcTx.outputVBytes(P2TR));
		assertEquals("unknown is sized as the largest", 43,
				BtcTx.outputVBytes("not an address"));
	}

	@Test
	public void dustThresholdsFollowTheScriptType() {
		assertEquals(546L, BtcTx.dustThresholdSat(P2PKH));
		assertEquals(540L, BtcTx.dustThresholdSat(P2SH));
		assertEquals(294L, BtcTx.dustThresholdSat(P2WPKH));
		assertEquals(330L, BtcTx.dustThresholdSat(P2WSH));
		assertEquals(330L, BtcTx.dustThresholdSat(P2TR));
		assertEquals(546L, BtcTx.dustThresholdSat("not an address"));
	}

	@Test
	public void theTypedEstimateMatchesTheFlatOneForSegwitOutputsOnly() {
		assertEquals(BtcTx.estimateVBytes(2, 2), BtcTx.estimateVBytes(2,
				Arrays.asList(new BtcTx.Output(P2WPKH, 1),
						new BtcTx.Output(P2WPKH, 1))));
		assertTrue(BtcTx.estimateVBytes(2, Arrays.asList(
				new BtcTx.Output(P2TR, 1), new BtcTx.Output(P2WPKH, 1)))
				> BtcTx.estimateVBytes(2, 2));
	}

	/** A payment to a legacy address is refused below its own dust limit. */
	@Test
	public void aLegacyDestinationUsesItsOwnDustLimitAndSize()
			throws Exception {
		String mnemonic = "abandon abandon abandon abandon abandon abandon "
				+ "abandon abandon abandon abandon abandon about";
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(TestKeys.scriptHash(mnemonic, 0, 0),
				"2222222222222222222222222222222222222222222222222222222222222222",
				0, 100000);
		BtcWallet w = new BtcWallet(mnemonic.toCharArray(), 0, 9999, "host",
				50001, "walletA", new FakeElectrum.RecordingFactory(e),
				(url, tag) -> null);
		try {
			w.planSend(P2PKH, 500, 2.0, false, null, false);
			throw new AssertionError("500 sat to P2PKH is dust");
		} catch (java.io.IOException expected) {
		}
		BtcWallet.SendPlan legacy = w.planSend(P2PKH, 10000, 2.0, false,
				null, false);
		BtcWallet.SendPlan segwit = w.planSend(P2WPKH, 10000, 2.0, false,
				null, false);
		assertEquals("three more bytes at two sat per byte",
				segwit.feeSat + 6, legacy.feeSat);
	}
}
