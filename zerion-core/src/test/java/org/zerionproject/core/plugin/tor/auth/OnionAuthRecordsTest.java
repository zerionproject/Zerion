package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfList;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * The wire records round-trip through their encoding and every malformed
 * shape is refused, so a peer cannot smuggle an unexpected field, key
 * length or address through the activation channel.
 */
public class OnionAuthRecordsTest {

	private static final String ONION =
			"i66iurfyjjh5tqpqhq7luu6defbb5rs7mxzvqgryaabqkzkgmjhql7qd";

	private static byte[] bytes(int len, int fill) {
		byte[] b = new byte[len];
		Arrays.fill(b, (byte) fill);
		return b;
	}

	@Test
	public void testOfferRoundTripsWithAndWithoutAnAddress() throws Exception {
		OnionAuthRecords.Record r = OnionAuthRecords.parse(
				OnionAuthRecords.offer(3, ONION, bytes(32, 1)));
		assertEquals(OnionAuthRecords.TYPE_OFFER, r.type);
		assertEquals(3, r.keyVersion);
		assertEquals(ONION, r.onion);
		assertArrayEquals(bytes(32, 1), r.publicKey);
		r = OnionAuthRecords.parse(OnionAuthRecords.offer(3, null, bytes(32, 1)));
		assertNull(r.onion);
	}

	@Test
	public void testCommitCarriesOnionAndFingerprint() throws Exception {
		OnionAuthRecords.Record r = OnionAuthRecords.parse(
				OnionAuthRecords.commit(7, ONION, bytes(32, 9)));
		assertEquals(OnionAuthRecords.TYPE_COMMIT, r.type);
		assertEquals(7, r.keyVersion);
		assertEquals(ONION, r.onion);
		assertArrayEquals(bytes(32, 9), r.fingerprint);
	}

	@Test
	public void testRotateNeedsAnAddressOrAKey() throws Exception {
		OnionAuthRecords.Record r = OnionAuthRecords.parse(
				OnionAuthRecords.rotate(2, ONION, null));
		assertEquals(ONION, r.onion);
		assertNull(r.publicKey);
		r = OnionAuthRecords.parse(OnionAuthRecords.rotate(2, null, bytes(32, 4)));
		assertNull(r.onion);
		assertArrayEquals(bytes(32, 4), r.publicKey);
		try {
			OnionAuthRecords.parse(OnionAuthRecords.rotate(2, null, null));
			fail();
		} catch (FormatException expected) {
		}
	}

	@Test
	public void testMalformedRecordsAreRefused() {
		BdfList[] bad = {
				BdfList.of(OnionAuthRecords.TYPE_OFFER, 1, 1, ONION, bytes(31, 1)),
				BdfList.of(OnionAuthRecords.TYPE_OFFER, 1, 1, "short", bytes(32, 1)),
				BdfList.of(OnionAuthRecords.TYPE_OFFER, 1, 1, ONION.toUpperCase(), bytes(32, 1)),
				BdfList.of(OnionAuthRecords.TYPE_OFFER, 2, 1, ONION, bytes(32, 1)),
				BdfList.of(OnionAuthRecords.TYPE_OFFER, 1, -1, ONION, bytes(32, 1)),
				BdfList.of(OnionAuthRecords.TYPE_COMMIT, 1, 1, ONION, bytes(16, 1)),
				BdfList.of(OnionAuthRecords.TYPE_READY, 1, 1, "extra"),
				BdfList.of(99, 1, 1),
				BdfList.of(OnionAuthRecords.TYPE_READY, 1),
		};
		for (BdfList b : bad) {
			try {
				OnionAuthRecords.parse(b);
				fail(b.toString());
			} catch (FormatException expected) {
			}
		}
	}
}
