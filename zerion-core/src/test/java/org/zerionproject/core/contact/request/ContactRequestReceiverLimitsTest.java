package org.zerionproject.core.contact.request;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactLinkInfo;
import org.zerionproject.core.api.contact.request.event.ContactRequestReceivedEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.CHURN_LIMIT_PER_HOUR;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_WITHDRAW;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_UNDECIDED_ZERO_ID;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.REPLAY_ROWS_PER_UNDECIDED;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ContactRequestReceiverLimitsTest extends BrambleTestCase {

	private static final long NOW = 1_800_000_000_000L;
	private static final long HOUR = 3_600_000L;

	private final File testDir = getTestDirectory();
	private final CryptoComponent crypto = PasswordCryptoForTests.create();
	private final SettableClock clock = new SettableClock(NOW);
	private final FakeTorNetwork network = new FakeTorNetwork();
	private final byte[] nonce = getRandomBytes(32);
	private final byte[] tag = getRandomBytes(32);
	private Party bob;
	private ContactLink zeroId;

	@Before
	public void setUp() throws Exception {
		testDir.mkdirs();
		bob = new Party(testDir, "bob", crypto, clock, network);
		zeroId = bob.zeroIdShared();
	}

	@After
	public void tearDown() throws Exception {
		bob.close();
		deleteTestDirectory(testDir);
	}

	private byte[] receive(ContactLink link, byte[] senderTag)
			throws Exception {
		KeyPair oneTime = crypto.generateHybridAgreementKeyPair();
		byte[] pub = oneTime.getPublic().getEncoded();
		byte[] otl = new byte[65];
		otl[0] = 1;
		System.arraycopy(crypto.hash(HYBRID_COMMITMENT_LABEL, pub), 0, otl, 1,
				32);
		System.arraycopy(pub, 0, otl, 33, 32);
		byte[] requestId = getRandomBytes(16);
		byte[] pop = bob.requestCrypto.proofOfPossession(
				Arrays.copyOf(link.getHybridPublicKey(), 32), oneTime, nonce,
				requestId);
		RequestPlaintext p = RequestPlaintext.request(requestId,
				link.getLinkId(), otl, pop, "A", null, 0, senderTag);
		return bob.receiver.handle(link.getLinkId(), p,
				new SecretKey(getRandomBytes(32)), nonce,
				clock.currentTimeMillis());
	}

	@Test
	public void aReplacementNotifiesAtMostOncePerHourPerTag()
			throws Exception {
		for (int i = 0; i < 3; i++) assertNotNull(receive(zeroId, tag));
		assertEquals(1, bob.incoming().size());
		assertEquals(1, bob.bus.of(ContactRequestReceivedEvent.class).size());
		receive(zeroId, getRandomBytes(32));
		assertEquals(2, bob.bus.of(ContactRequestReceivedEvent.class).size());
		clock.now = NOW + HOUR;
		receive(zeroId, tag);
		assertEquals(3, bob.bus.of(ContactRequestReceivedEvent.class).size());
	}

	@Test
	public void replacementsAndBlockedHitsAreRateLimitedPerLink()
			throws Exception {
		receive(zeroId, tag);
		for (int i = 0; i < CHURN_LIMIT_PER_HOUR - 1; i++) {
			receive(zeroId, tag);
		}
		assertFalse(bob.receiver.isFull(zeroId.getLinkId()));
		bob.manager.block(bob.incoming().get(0).getRequestId());
		receive(zeroId, tag);
		assertTrue(bob.receiver.isFull(zeroId.getLinkId()));
		ContactLinkInfo other = bob.manager.createPermanentLink("Other");
		assertFalse("limits are per link",
				bob.receiver.isFull(other.getLinkId()));
		clock.now = NOW + HOUR;
		assertFalse(bob.receiver.isFull(zeroId.getLinkId()));
	}

	@Test
	public void tooManyReplayRowsForALinkAnswerFull() throws Exception {
		bob.db.transaction(false, txn -> {
			for (int i = 0;
					i < REPLAY_ROWS_PER_UNDECIDED * MAX_UNDECIDED_ZERO_ID - 1;
					i++) {
				bob.db.addRequestReplay(txn, getRandomBytes(32), 1,
						getRandomBytes(32), zeroId.getLinkId(), NOW + HOUR);
			}
		});
		assertFalse(bob.receiver.isFull(zeroId.getLinkId()));
		receive(zeroId, tag);
		assertTrue(bob.receiver.isFull(zeroId.getLinkId()));
	}

	@Test
	public void aWithdrawForAnUnknownRequestStoresNothing() throws Exception {
		byte[] requestId = getRandomBytes(16);
		byte[] ack = bob.receiver.handle(zeroId.getLinkId(),
				RequestPlaintext.withdraw(requestId, zeroId.getLinkId()),
				new SecretKey(getRandomBytes(32)), nonce, NOW);
		assertNotNull(ack);
		assertFalse(bob.db.transactionWithResult(true, txn ->
				bob.db.containsRequestReplay(txn,
						bob.requestCrypto.requestIdHash(requestId),
						KIND_WITHDRAW)));
		assertEquals(0, (int) bob.db.transactionWithResult(true, txn ->
				bob.db.countRequestReplay(txn, zeroId.getLinkId())));
	}

	@Test
	public void aWithdrawnLinkStoresNoNewRequest() throws Exception {
		ContactLinkInfo info = bob.manager.createPermanentLink("Support");
		ContactLink link = bob.db.transactionWithResult(true,
				txn -> bob.links.getLink(txn, info.getLinkId()));
		receive(link, tag);
		bob.manager.withdrawPermanentLink(info.getLinkId());
		assertNull(receive(link, getRandomBytes(32)));
		assertEquals(1, bob.incoming().size());
	}
}
