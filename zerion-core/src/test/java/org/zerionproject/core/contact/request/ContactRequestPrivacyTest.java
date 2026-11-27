package org.zerionproject.core.contact.request;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactLinkInfo;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.data.BdfForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ContactRequestPrivacyTest extends BrambleTestCase {

	private static final long NOW = 1_800_000_000_000L;

	private final File testDir = getTestDirectory();
	private final CryptoComponent crypto = PasswordCryptoForTests.create();
	private final SettableClock clock = new SettableClock(NOW);
	private final FakeTorNetwork network = new FakeTorNetwork();
	private Party alice, bob;

	@Before
	public void setUp() throws Exception {
		testDir.mkdirs();
		alice = new Party(testDir, "alice", crypto, clock, network);
		bob = new Party(testDir, "bob", crypto, clock, network);
	}

	@After
	public void tearDown() throws Exception {
		alice.close();
		bob.close();
		deleteTestDirectory(testDir);
	}

	private byte[] deliver(String link, String name) throws Exception {
		byte[] id = alice.sender.submit(alice.codec.parse(link), link, "Bob",
				name, null);
		alice.sender.attempt(id);
		return id;
	}

	@Test
	public void theReceiverLearnsNoSenderKeyOrAddressBeforeAccepting()
			throws Exception {
		ContactLink z = bob.zeroIdShared();
		bob.publish(network);
		deliver(bob.links.linkString(z), "Alice");
		IncomingRequest r = bob.incoming().get(0);
		byte[][] aliceKeys = {
				alice.identity.perAdd.getPublic().getEncoded(),
				alice.identity.handshake.getPublic().getEncoded(),
				alice.identity.author.getPublicKey().getEncoded(),
				alice.identity.author.getId().getBytes()
		};
		byte[][] stored = {r.getOneTimeLink(), r.getSenderTag(),
				r.getRequestId()};
		for (byte[] key : aliceKeys) {
			byte[] head = Arrays.copyOf(key, 16);
			for (byte[] field : stored) {
				assertFalse(contains(field, head));
			}
		}
	}

	@Test
	public void theTagLinksOneAccountThroughOneLinkOnly() throws Exception {
		ContactLink z = bob.zeroIdShared();
		ContactLinkInfo support = bob.manager.createPermanentLink("Support");
		bob.publish(network);
		byte[] first = deliver(bob.links.linkString(z), "A1");
		alice.sender.cancel(first);
		alice.sender.attempt(first);
		alice.sender.dismiss(first);
		deliver(bob.links.linkString(z), "A2");
		deliver(support.getLink(), "A3");
		List<IncomingRequest> in = bob.incoming();
		assertEquals(2, in.size());
		IncomingRequest viaZeroId = null, viaSupport = null;
		for (IncomingRequest r : in) {
			if (Arrays.equals(r.getLinkId(), z.getLinkId())) viaZeroId = r;
			else viaSupport = r;
		}
		byte[] expected = bob.requestCrypto.senderTag(
				alice.db.transactionWithResult(false,
						alice.links::getAccountTagSecret), z.getLinkId());
		assertArrayEquals(expected, viaZeroId.getSenderTag());
		assertFalse(Arrays.equals(viaZeroId.getSenderTag(),
				viaSupport.getSenderTag()));
		assertFalse(Arrays.equals(viaZeroId.getOneTimeLink(),
				viaSupport.getOneTimeLink()));
	}

	@Test
	public void namesAreCleanedAndReservedNamesHidden() {
		assertEquals("Alice", ContactRequestReceiver.sanitizeName(
				"\u202eAl\u0000ice\u2066"));
		assertNull(ContactRequestReceiver.sanitizeName("\u200e\u0007"));
		assertEquals("ab", ContactRequestReceiver.sanitize("a\nb"));
	}

	@Test
	public void everyRequestHasTheSameSize() {
		byte[] otl = getRandomBytes(65);
		otl[0] = 1;
		StringBuilder longNote = new StringBuilder();
		for (int i = 0; i < 200; i++) longNote.append('n');
		int small = RequestPlaintext.request(getRandomBytes(16),
				getRandomBytes(32), otl, getRandomBytes(32), "a", null, 0,
				getRandomBytes(32)).encode(BdfForTests.writers()).length;
		int large = RequestPlaintext.request(getRandomBytes(16),
				getRandomBytes(32), otl, getRandomBytes(32),
				"nnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnnn",
				longNote.toString(), Long.MAX_VALUE / 2, getRandomBytes(32))
				.encode(BdfForTests.writers()).length;
		int withdraw = RequestPlaintext.withdraw(getRandomBytes(16),
				getRandomBytes(32)).encode(BdfForTests.writers()).length;
		assertEquals(small, large);
		assertEquals(small, withdraw);
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}
}
