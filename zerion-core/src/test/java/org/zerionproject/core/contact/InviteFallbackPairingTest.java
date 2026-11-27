package org.zerionproject.core.contact;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.contact.OwnLinkChangedException;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.contact.request.ContactLinkStore;
import org.zerionproject.core.contact.request.FakeIdentity;
import org.zerionproject.core.contact.request.InviteLinkCodec;
import org.zerionproject.core.contact.request.RecordingEventBus;
import org.zerionproject.core.contact.request.SettableClock;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.db.HyperSqlDatabaseForTests;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestMessageFactory;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.PER_ADD_BINDING_MS;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class InviteFallbackPairingTest extends BrambleTestCase {

	private static final long NOW = 1_800_000_000_000L;

	private final File testDir = getTestDirectory();
	private final CryptoComponent crypto = PasswordCryptoForTests.create();
	private final SettableClock clock = new SettableClock(NOW);
	private final FakeIdentity identity = new FakeIdentity(crypto);
	private final InviteLinkCodec codec = new InviteLinkCodec(crypto);
	private final PendingContactFactoryImpl factory =
			new PendingContactFactoryImpl(crypto, clock);
	private DatabaseComponent db;
	private ContactLinkStore links;
	private ContactManagerImpl contacts;

	@Before
	public void setUp() throws Exception {
		testDir.mkdirs();
		db = HyperSqlDatabaseForTests.open(testDir, getSecretKey(),
				new RecordingEventBus(), new TestMessageFactory());
		links = new ContactLinkStore(db, crypto, identity.manager, codec,
				clock);
		contacts = new ContactManagerImpl(db, null, identity.manager,
				factory, crypto, null, links, clock);
	}

	@After
	public void tearDown() throws Exception {
		db.close();
		deleteTestDirectory(testDir);
	}

	private String peerLink() {
		KeyPair peer = crypto.generateHybridAgreementKeyPair();
		return factory.createHandshakeLink(peer.getPublic());
	}

	private String perAddLink() {
		return factory.createHandshakeLink(identity.perAdd.getPublic());
	}

	private byte[][] snapshot(PendingContact p) throws Exception {
		return db.transactionWithResult(true,
				txn -> db.getPendingContactOurKeys(txn, p.getId()));
	}

	@Test
	public void withoutABindingTheZeroIdIsCreatedAndSnapshotted()
			throws Exception {
		assertNull(db.transactionWithResult(true, links::getZeroId));
		PendingContact p = contacts.addPendingContactFromInvite(peerLink(),
				"peer", null);
		ContactLink zeroId = db.transactionWithResult(true, links::getZeroId);
		assertNotNull(zeroId);
		assertArrayEquals(zeroId.getHybridPublicKey(), snapshot(p)[0]);
		assertEquals(0, zeroId.getLastSharedAt());
	}

	@Test
	public void theSharedZeroIdMustStillBeCurrent() throws Exception {
		ContactLink zeroId = db.transactionWithResult(false,
				links::getOrCreateZeroId);
		String shared = links.linkString(zeroId);
		PendingContact p = contacts.addPendingContactFromInvite(peerLink(),
				"one", shared);
		assertArrayEquals(zeroId.getHybridPublicKey(), snapshot(p)[0]);
		db.transaction(false, links::resetZeroId);
		try {
			contacts.addPendingContactFromInvite(peerLink(), "two", shared);
			fail();
		} catch (OwnLinkChangedException expected) {
		}
		assertEquals(1, db.transactionWithResult(true,
				db::getPendingContacts).size());
	}

	@Test
	public void aFreshPerAddBindingIsUsedAndForgotten() throws Exception {
		contacts.recordPerAddLinkShared(perAddLink());
		clock.now = NOW + PER_ADD_BINDING_MS;
		PendingContact p = contacts.addPendingContactFromInvite(peerLink(),
				"peer", null);
		assertArrayEquals(identity.perAdd.getPublic().getEncoded(),
				snapshot(p)[0]);
		assertNull(db.transactionWithResult(true, links::getZeroId));
		assertNull(db.transactionWithResult(true,
				txn -> links.freshPerAddBinding(txn, clock.now)));
	}

	@Test
	public void aBindingNewerThanTheLastZeroIdShareIsUsed() throws Exception {
		ContactLink zeroId = db.transactionWithResult(false,
				links::getOrCreateZeroId);
		db.transaction(false, txn -> links.markShared(txn,
				zeroId.getLinkId()));
		clock.now = NOW + 1;
		contacts.recordPerAddLinkShared(perAddLink());
		PendingContact p = contacts.addPendingContactFromInvite(peerLink(),
				"peer", null);
		assertArrayEquals(identity.perAdd.getPublic().getEncoded(),
				snapshot(p)[0]);
	}

	@Test
	public void qrShownThenZeroIdSharedThenTheFallbackSnapshotsTheZeroId()
			throws Exception {
		contacts.recordPerAddLinkShared(perAddLink());
		clock.now = NOW + 1;
		ContactLink zeroId = db.transactionWithResult(false,
				links::getOrCreateZeroId);
		db.transaction(false, txn -> links.markShared(txn,
				zeroId.getLinkId()));
		PendingContact p = contacts.addPendingContactFromInvite(peerLink(),
				"peer", null);
		assertArrayEquals(zeroId.getHybridPublicKey(), snapshot(p)[0]);
	}

	@Test
	public void aSharedZeroIdIsNeverReplacedByABinding() throws Exception {
		ContactLink zeroId = db.transactionWithResult(false,
				links::getOrCreateZeroId);
		String shared = links.linkString(zeroId);
		clock.now = NOW + 1;
		contacts.recordPerAddLinkShared(perAddLink());
		PendingContact p = contacts.addPendingContactFromInvite(peerLink(),
				"peer", shared);
		assertArrayEquals(zeroId.getHybridPublicKey(), snapshot(p)[0]);
	}

	@Test
	public void anOldBindingFallsBackToTheZeroId() throws Exception {
		contacts.recordPerAddLinkShared(perAddLink());
		clock.now = NOW + PER_ADD_BINDING_MS + 1;
		PendingContact p = contacts.addPendingContactFromInvite(peerLink(),
				"peer", null);
		ContactLink zeroId = db.transactionWithResult(true, links::getZeroId);
		assertArrayEquals(zeroId.getHybridPublicKey(), snapshot(p)[0]);
	}

	@Test
	public void aBindingToARotatedKeyFailsAndIsForgotten() throws Exception {
		contacts.recordPerAddLinkShared(perAddLink());
		identity.perAdd = crypto.generateHybridAgreementKeyPair();
		try {
			contacts.addPendingContactFromInvite(peerLink(), "peer", null);
			fail();
		} catch (OwnLinkChangedException expected) {
		}
		assertNull(db.transactionWithResult(true,
				txn -> links.freshPerAddBinding(txn, clock.now)));
		assertTrue(db.transactionWithResult(true,
				db::getPendingContacts).isEmpty());
		try {
			contacts.recordPerAddLinkShared(perAddLink());
			identity.perAdd = crypto.generateHybridAgreementKeyPair();
			contacts.addPendingContactBoundToSharedLink(peerLink(), "x",
					false, false);
			fail();
		} catch (OwnLinkChangedException expected) {
		}
		assertNull(db.transactionWithResult(true,
				txn -> links.freshPerAddBinding(txn, clock.now)));
	}

	@Test
	public void theOldFlowUsesTheCurrentPerAddKey() throws Exception {
		PendingContact p = contacts.addPendingContactBoundToSharedLink(
				peerLink(), "peer", false, false);
		assertArrayEquals(identity.perAdd.getPublic().getEncoded(),
				snapshot(p)[0]);
		contacts.recordPerAddLinkShared(perAddLink());
		PendingContact q = contacts.addPendingContactBoundToSharedLink(
				peerLink(), "peer2", false, true);
		assertArrayEquals(identity.perAdd.getPublic().getEncoded(),
				snapshot(q)[0]);
		assertNull(db.transactionWithResult(true,
				txn -> links.freshPerAddBinding(txn, clock.now)));
	}

	@Test
	public void aPerAddRotationAfterTheLinkWasShownFailsInsteadOfPairing()
			throws Exception {
		String shown = perAddLink();
		identity.perAdd = crypto.generateHybridAgreementKeyPair();
		try {
			contacts.addPendingContactBoundToSharedLink(peerLink(), "x", shown,
					false, false);
			fail();
		} catch (OwnLinkChangedException expected) {
		}
		assertTrue(db.transactionWithResult(true,
				db::getPendingContacts).isEmpty());
		PendingContact p = contacts.addPendingContactBoundToSharedLink(
				peerLink(), "y", perAddLink(), false, false);
		assertArrayEquals(identity.perAdd.getPublic().getEncoded(),
				snapshot(p)[0]);
	}

	@Test
	public void anOwnLinkIsRefusedByTheInviteFallback() throws Exception {
		ContactLink zeroId = db.transactionWithResult(false,
				links::getOrCreateZeroId);
		String ownZeroId = links.linkString(zeroId);
		String withoutInbox = ownZeroId.substring(0, ownZeroId.indexOf('?'));
		for (String own : new String[] {withoutInbox, perAddLink()}) {
			try {
				contacts.addPendingContactFromInvite(own, "me", null);
				fail(own);
			} catch (org.zerionproject.core.api.contact.request
					.OwnLinkException expected) {
			}
		}
		assertTrue(db.transactionWithResult(true,
				db::getPendingContacts).isEmpty());
	}
}
