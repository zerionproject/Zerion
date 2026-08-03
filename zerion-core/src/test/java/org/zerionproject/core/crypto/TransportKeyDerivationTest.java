package org.zerionproject.core.crypto;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.TransportCrypto;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.transport.IncomingKeys;
import org.zerionproject.core.api.transport.OutgoingKeys;
import org.zerionproject.core.api.transport.TransportKeys;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TransportKeyDerivationTest extends BrambleTestCase {

	private final CryptoComponent crypto =
			new CryptoComponentImpl(new TestSecureRandomProvider(), null);
	private final TransportCrypto transportCrypto =
			new TransportCryptoImpl(crypto);
	private final TransportId transportId = getTransportId();
	private final SecretKey rootKey = getSecretKey();

	@Test
	public void testRotationKeysAreDistinct() {
		TransportKeys kA = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, true, true);
		TransportKeys kB = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, false, true);
		assertAllDifferent(kA);
		assertAllDifferent(kB);
	}

	@Test
	public void testRotationKeysAreNotRotatedToPreviousPeriod() {
		TransportKeys k = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, true, true);
		TransportKeys k1 = transportCrypto.updateTransportKeys(k, 122);
		assertSame(k, k1);
	}

	@Test
	public void testRotationKeysAreNotRotatedToCurrentPeriod() {
		TransportKeys k = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, true, true);
		TransportKeys k1 = transportCrypto.updateTransportKeys(k, 123);
		assertSame(k, k1);
	}

	@Test
	public void testRotationKeysAreRotatedByOnePeriod() {
		TransportKeys k = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, true, true);
		TransportKeys k1 = transportCrypto.updateTransportKeys(k, 124);
		assertSame(k.getCurrentIncomingKeys(), k1.getPreviousIncomingKeys());
		assertSame(k.getNextIncomingKeys(), k1.getCurrentIncomingKeys());
	}

	@Test
	public void testRotationKeysAreRotatedByTwoPeriods() {
		TransportKeys k = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, true, true);
		TransportKeys k1 = transportCrypto.updateTransportKeys(k, 125);
		assertSame(k.getNextIncomingKeys(), k1.getPreviousIncomingKeys());
	}

	@Test
	public void testRotationKeysAreRotatedByThreePeriods() {
		TransportKeys k = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, true, true);
		TransportKeys k1 = transportCrypto.updateTransportKeys(k, 126);
		assertAllDifferent(k, k1);
	}

	@Test
	public void testCurrentRotationKeysMatchContact() {

		TransportKeys kA = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, true, true);
		TransportKeys kB = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, false, true);

		assertMatches(kA.getCurrentIncomingKeys(), kB.getCurrentOutgoingKeys());

		assertMatches(kB.getCurrentIncomingKeys(), kA.getCurrentOutgoingKeys());

		kA = transportCrypto.updateTransportKeys(kA, 456);
		kB = transportCrypto.updateTransportKeys(kB, 456);

		assertMatches(kA.getCurrentIncomingKeys(), kB.getCurrentOutgoingKeys());

		assertMatches(kB.getCurrentIncomingKeys(), kA.getCurrentOutgoingKeys());
	}

	@Test
	public void testPreviousRotationKeysMatchContact() {

		TransportKeys kA = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, true, true);
		TransportKeys kB = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, false, true);

		kA = transportCrypto.updateTransportKeys(kA, 456);
		kB = transportCrypto.updateTransportKeys(kB, 455);

		assertMatches(kA.getPreviousIncomingKeys(),
				kB.getCurrentOutgoingKeys());

		kB = transportCrypto.updateTransportKeys(kB, 457);

		assertMatches(kB.getPreviousIncomingKeys(),
				kA.getCurrentOutgoingKeys());
	}

	@Test
	public void testNextRotationKeysMatchContact() {

		TransportKeys kA = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, true, true);
		TransportKeys kB = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, false, true);

		kA = transportCrypto.updateTransportKeys(kA, 456);
		kB = transportCrypto.updateTransportKeys(kB, 455);

		assertMatches(kB.getNextIncomingKeys(), kA.getCurrentOutgoingKeys());

		kB = transportCrypto.updateTransportKeys(kB, 457);

		assertMatches(kA.getNextIncomingKeys(), kB.getCurrentOutgoingKeys());
	}

	@Test
	public void testRootKeyAffectsRotationKeyDerivation() {
		SecretKey rootKey1 = getSecretKey();
		assertFalse(Arrays.equals(rootKey.getBytes(), rootKey1.getBytes()));
		TransportKeys k = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, true, true);
		TransportKeys k1 = transportCrypto.deriveRotationKeys(transportId,
				rootKey1, 123, true, true);
		assertAllDifferent(k, k1);
	}

	@Test
	public void testTransportIdAffectsRotationKeyDerivation() {
		TransportId transportId1 = getTransportId();
		assertNotEquals(transportId.getString(), transportId1.getString());
		TransportKeys k = transportCrypto.deriveRotationKeys(transportId,
				rootKey, 123, true, true);
		TransportKeys k1 = transportCrypto.deriveRotationKeys(transportId1,
				rootKey, 123, true, true);
		assertAllDifferent(k, k1);
	}

	@Test
	public void testHandshakeKeysAreDistinct() {
		TransportKeys kA = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, true);
		TransportKeys kB = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, false);
		assertAllDifferent(kA);
		assertAllDifferent(kB);
	}

	@Test
	public void testHandshakeKeysAreNotUpdatedToPreviousPeriod() {
		TransportKeys k = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, true);
		TransportKeys k1 = transportCrypto.updateTransportKeys(k, 122);
		assertSame(k, k1);
	}

	@Test
	public void testHandshakeKeysAreNotUpdatedToCurrentPeriod() {
		TransportKeys k = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, true);
		TransportKeys k1 = transportCrypto.updateTransportKeys(k, 123);
		assertSame(k, k1);
	}

	@Test
	public void testHandshakeKeysAreUpdatedByOnePeriod() {
		TransportKeys k = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, true);
		TransportKeys k1 = transportCrypto.updateTransportKeys(k, 124);
		assertSame(k.getCurrentIncomingKeys(), k1.getPreviousIncomingKeys());
		assertSame(k.getNextIncomingKeys(), k1.getCurrentIncomingKeys());
	}

	@Test
	public void testHandshakeKeysAreUpdatedByTwoPeriods() {
		TransportKeys k = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, true);
		TransportKeys k1 = transportCrypto.updateTransportKeys(k, 125);
		assertSame(k.getNextIncomingKeys(), k1.getPreviousIncomingKeys());
	}

	@Test
	public void testHandshakeKeysAreUpdatedByThreePeriods() {
		TransportKeys k = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, true);
		TransportKeys k1 = transportCrypto.updateTransportKeys(k, 126);
		assertAllDifferent(k, k1);
	}

	@Test
	public void testCurrentHandshakeKeysMatchContact() {

		TransportKeys kA = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, true);
		TransportKeys kB = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, false);

		assertMatches(kA.getCurrentIncomingKeys(), kB.getCurrentOutgoingKeys());

		assertMatches(kB.getCurrentIncomingKeys(), kA.getCurrentOutgoingKeys());

		kA = transportCrypto.updateTransportKeys(kA, 456);
		kB = transportCrypto.updateTransportKeys(kB, 456);

		assertMatches(kA.getCurrentIncomingKeys(), kB.getCurrentOutgoingKeys());

		assertMatches(kB.getCurrentIncomingKeys(), kA.getCurrentOutgoingKeys());
	}

	@Test
	public void testPreviousHandshakeKeysMatchContact() {

		TransportKeys kA = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, true);
		TransportKeys kB = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, false);

		kA = transportCrypto.updateTransportKeys(kA, 456);
		kB = transportCrypto.updateTransportKeys(kB, 455);

		assertMatches(kA.getPreviousIncomingKeys(),
				kB.getCurrentOutgoingKeys());

		kB = transportCrypto.updateTransportKeys(kB, 457);

		assertMatches(kB.getPreviousIncomingKeys(),
				kA.getCurrentOutgoingKeys());
	}

	@Test
	public void testNextHandshakeKeysMatchContact() {

		TransportKeys kA = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, true);
		TransportKeys kB = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, false);

		kA = transportCrypto.updateTransportKeys(kA, 456);
		kB = transportCrypto.updateTransportKeys(kB, 455);

		assertMatches(kB.getNextIncomingKeys(), kA.getCurrentOutgoingKeys());

		kB = transportCrypto.updateTransportKeys(kB, 457);

		assertMatches(kA.getNextIncomingKeys(), kB.getCurrentOutgoingKeys());
	}

	@Test
	public void testRootKeyAffectsHandshakeKeyDerivation() {
		SecretKey rootKey1 = getSecretKey();
		assertFalse(Arrays.equals(rootKey.getBytes(), rootKey1.getBytes()));
		TransportKeys k = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, true);
		TransportKeys k1 = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey1, 123, true);
		assertAllDifferent(k, k1);
	}

	@Test
	public void testTransportIdAffectsHandshakeKeyDerivation() {
		TransportId transportId1 = getTransportId();
		assertNotEquals(transportId.getString(), transportId1.getString());
		TransportKeys k = transportCrypto.deriveHandshakeKeys(transportId,
				rootKey, 123, true);
		TransportKeys k1 = transportCrypto.deriveHandshakeKeys(transportId1,
				rootKey, 123, true);
		assertAllDifferent(k, k1);
	}

	private void assertAllDifferent(TransportKeys... transportKeys) {
		List<SecretKey> secretKeys = new ArrayList<>();
		for (TransportKeys k : transportKeys) {
			secretKeys.add(k.getPreviousIncomingKeys().getTagKey());
			secretKeys.add(k.getPreviousIncomingKeys().getHeaderKey());
			secretKeys.add(k.getCurrentIncomingKeys().getTagKey());
			secretKeys.add(k.getCurrentIncomingKeys().getHeaderKey());
			secretKeys.add(k.getNextIncomingKeys().getTagKey());
			secretKeys.add(k.getNextIncomingKeys().getHeaderKey());
			secretKeys.add(k.getCurrentOutgoingKeys().getTagKey());
			secretKeys.add(k.getCurrentOutgoingKeys().getHeaderKey());
		}
		assertAllDifferent(secretKeys);
	}

	private void assertAllDifferent(List<SecretKey> keys) {
		Set<Bytes> set = new HashSet<>();
		for (SecretKey k : keys) assertTrue(set.add(new Bytes(k.getBytes())));
	}

	private void assertMatches(IncomingKeys in, OutgoingKeys out) {
		assertArrayEquals(in.getTagKey().getBytes(),
				out.getTagKey().getBytes());
		assertArrayEquals(in.getHeaderKey().getBytes(),
				out.getHeaderKey().getBytes());
	}
}
