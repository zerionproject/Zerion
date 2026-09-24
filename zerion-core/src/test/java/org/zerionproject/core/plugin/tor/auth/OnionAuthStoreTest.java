package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.plugin.OnionClientAuthManager;
import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The persisted state round-trips exactly, an unknown contact reads as
 * LEGACY, a cleared record reads as LEGACY again and no longer appears in
 * the list of records, and the device's service record survives too.
 */
public class OnionAuthStoreTest {

	private final OnionAuthStore store =
			new OnionAuthStore(new InMemorySettingsManager());
	private final ContactId c = new ContactId(42);

	private static byte[] bytes(int fill) {
		byte[] b = new byte[32];
		Arrays.fill(b, (byte) fill);
		return b;
	}

	@Test
	public void testUnknownContactIsLegacy() throws Exception {
		OnionAuthRecord r = store.load(null, c);
		assertEquals(State.LEGACY, r.state);
		assertNull(r.dialPrivateKey);
		assertTrue(store.loadAll(null).isEmpty());
	}

	@Test
	public void testRecordRoundTripsAndClears() throws Exception {
		OnionAuthRecord r = new OnionAuthRecord(c);
		r.state = State.AUTH_REQUIRED;
		r.localGen = 2;
		r.peerGen = 5;
		r.dialPrivateKey = bytes(1);
		r.dialPublicKey = bytes(2);
		r.peerOnion = "i66iurfyjjh5tqpqhq7luu6defbb5rs7mxzvqgryaabqkzkgmjhql7qd";
		r.peerPublicKey = bytes(3);
		r.readyReceived = true;
		r.probeSucceeded = true;
		r.peerProbeSucceeded = true;
		r.commitSent = true;
		r.peerCommitReceived = true;
		r.negotiationStartedMs = 1234;
		store.save(null, r);
		OnionAuthRecord back = store.load(null, c);
		assertEquals(State.AUTH_REQUIRED, back.state);
		assertEquals(2, back.localGen);
		assertEquals(5, back.peerGen);
		assertArrayEquals(bytes(1), back.dialPrivateKey);
		assertArrayEquals(bytes(2), back.dialPublicKey);
		assertEquals(r.peerOnion, back.peerOnion);
		assertArrayEquals(bytes(3), back.peerPublicKey);
		assertTrue(back.readyReceived && back.probeSucceeded
				&& back.peerProbeSucceeded && back.commitSent
				&& back.peerCommitReceived);
		assertEquals(1234, back.negotiationStartedMs);
		List<OnionAuthRecord> all = store.loadAll(null);
		assertEquals(1, all.size());
		assertEquals(c, all.get(0).contactId);
		store.clear(null, c);
		assertEquals(State.LEGACY, store.load(null, c).state);
		assertTrue(store.loadAll(null).isEmpty());
	}

	@Test
	public void testServiceRecordRoundTrips() throws Exception {
		OnionAuthStore.ServiceRecord s = new OnionAuthStore.ServiceRecord();
		s.onion = "i66iurfyjjh5tqpqhq7luu6defbb5rs7mxzvqgryaabqkzkgmjhql7qd";
		s.privateKey = "ED25519-V3:abc=";
		s.gen = 3;
		s.oldOnion = "ru3bfvgi52cq7zgscrqw6nhcaga5thrvrnnb23tefpdv7hxwrrhfl6yd";
		s.oldPrivateKey = "ED25519-V3:old=";
		s.oldSinceMs = 99;
		s.lastRotationMs = 100;
		store.saveService(null, s);
		OnionAuthStore.ServiceRecord back = store.loadService(null);
		assertEquals(s.onion, back.onion);
		assertEquals(s.privateKey, back.privateKey);
		assertEquals(3, back.gen);
		assertEquals(s.oldOnion, back.oldOnion);
		assertEquals(s.oldPrivateKey, back.oldPrivateKey);
		assertEquals(99, back.oldSinceMs);
		assertEquals(100, back.lastRotationMs);
	}

	@Test
	public void testAPeerWithoutAnAddressLoadsAsNoAddress() throws Exception {
		OnionAuthRecord r = new OnionAuthRecord(new ContactId(9));
		r.state = OnionClientAuthManager.State.AUTH_NEGOTIATING;
		r.peerPublicKey = new byte[32];
		r.peerOnion = null;
		store.save(null, r);
		OnionAuthRecord back = store.load(null, new ContactId(9));
		assertNull(back.peerOnion);
		assertFalse(back.hasPeerService());
		assertNotNull(back.peerPublicKey);
	}
}
