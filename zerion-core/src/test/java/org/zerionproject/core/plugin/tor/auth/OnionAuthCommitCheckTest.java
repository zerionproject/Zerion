package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.plugin.tor.auth.OnionAuthCommitCheck.Verdict;

/**
 * A commit moves a pair to AUTH_REQUIRED only when it is bound to the
 * current generation on both sides, names this device's authorized
 * service and the peer's registered key, both directions were proven and
 * the contact is not revoked. Every other commit changes nothing.
 */
public class OnionAuthCommitCheckTest {

	private static final String MY_ONION =
			"i66iurfyjjh5tqpqhq7luu6defbb5rs7mxzvqgryaabqkzkgmjhql7qd";
	private static final String OTHER_ONION =
			"ru3bfvgi52cq7zgscrqw6nhcaga5thrvrnnb23tefpdv7hxwrrhfl6yd";

	private static byte[] bytes(int fill) {
		byte[] b = new byte[32];
		Arrays.fill(b, (byte) fill);
		return b;
	}

	private OnionAuthRecord ready() {
		OnionAuthRecord r = new OnionAuthRecord(new ContactId(1));
		r.state = State.AUTH_CONFIRMED;
		r.localGen = 1;
		r.peerGen = 4;
		r.dialPrivateKey = bytes(1);
		r.dialPublicKey = bytes(2);
		r.peerPublicKey = bytes(3);
		r.peerOnion = OTHER_ONION;
		r.readyReceived = true;
		r.probeSucceeded = true;
		r.peerProbeSucceeded = true;
		return r;
	}

	private OnionAuthRecords.Record commit(long keyVersion, String onion,
			byte[] fingerprint) throws Exception {
		return OnionAuthRecords.parse(OnionAuthRecords.commit(keyVersion,
				onion, fingerprint));
	}

	private byte[] peerFp() {
		return OnionAuthCommitCheck.fingerprint(bytes(3));
	}

	@Test
	public void testAValidCommitIsAccepted() throws Exception {
		assertEquals(Verdict.ACCEPT, OnionAuthCommitCheck.check(ready(),
				commit(4, MY_ONION, peerFp()), 1, MY_ONION));
	}

	@Test
	public void testStaleCommitAfterPeerRotation() throws Exception {
		OnionAuthRecord r = ready();
		r.peerGen = 5;
		assertEquals(Verdict.STALE_GENERATION, OnionAuthCommitCheck.check(r,
				commit(4, MY_ONION, peerFp()), 1, MY_ONION));
	}

	@Test
	public void testFutureCommitIsIgnored() throws Exception {
		assertEquals(Verdict.FUTURE_GENERATION, OnionAuthCommitCheck.check(
				ready(), commit(5, MY_ONION, peerFp()), 1, MY_ONION));
	}

	@Test
	public void testCommitAfterLocalRotationIsStale() throws Exception {
		assertEquals(Verdict.STALE_GENERATION, OnionAuthCommitCheck.check(
				ready(), commit(4, MY_ONION, peerFp()), 2, MY_ONION));
	}

	@Test
	public void testMismatchedOnion() throws Exception {
		assertEquals(Verdict.ONION_MISMATCH, OnionAuthCommitCheck.check(
				ready(), commit(4, OTHER_ONION, peerFp()), 1, MY_ONION));
	}

	@Test
	public void testMismatchedClientKeyFingerprint() throws Exception {
		assertEquals(Verdict.KEY_MISMATCH, OnionAuthCommitCheck.check(ready(),
				commit(4, MY_ONION, OnionAuthCommitCheck.fingerprint(bytes(8))),
				1, MY_ONION));
	}

	@Test
	public void testCommitBeforeBothDirectionsProven() throws Exception {
		OnionAuthRecord r = ready();
		r.peerProbeSucceeded = false;
		assertEquals(Verdict.NOT_READY, OnionAuthCommitCheck.check(r,
				commit(4, MY_ONION, peerFp()), 1, MY_ONION));
		r = ready();
		r.probeSucceeded = false;
		assertEquals(Verdict.NOT_READY, OnionAuthCommitCheck.check(r,
				commit(4, MY_ONION, peerFp()), 1, MY_ONION));
	}

	@Test
	public void testCommitAfterRevoked() throws Exception {
		OnionAuthRecord r = ready();
		r.state = State.REVOKED;
		assertEquals(Verdict.REVOKED, OnionAuthCommitCheck.check(r,
				commit(4, MY_ONION, peerFp()), 1, MY_ONION));
	}

	@Test
	public void testCommitWithoutLocalKeysOrService() throws Exception {
		OnionAuthRecord r = ready();
		r.dialPrivateKey = null;
		assertEquals(Verdict.LOCAL_KEY_MISSING, OnionAuthCommitCheck.check(r,
				commit(4, MY_ONION, peerFp()), 1, MY_ONION));
		assertEquals(Verdict.ONION_MISMATCH, OnionAuthCommitCheck.check(
				ready(), commit(4, MY_ONION, peerFp()), 1, null));
	}
}
