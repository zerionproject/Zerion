package org.zerionproject.transport;

import org.junit.Test;
import org.zerionproject.tor.BridgeList;
import org.zerionproject.tor.BridgeListFixtures;
import org.zerionproject.tor.BridgeListFixtures.Signer;
import org.zerionproject.tor.BridgeListKeys;
import org.zerionproject.tor.CircumventionProvider.BridgeType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.tor.BridgeListFixtures.DAY_MS;

public class PublishedBridgeListTest {

	private static final File LIST = new File("../docs/bridges/bridges.json");
	private static final File SIGNATURE =
			new File("../docs/bridges/bridges.json.sig");

	private static byte[] read(File f) throws IOException {
		return Files.readAllBytes(f.toPath());
	}

	@Test
	public void thePublishedListParsesAndFollowsTheReleaseRule()
			throws IOException {
		assertFollowsTheReleaseRule(read(LIST));
	}

	@Test
	public void thePublishedSignatureVerifiesWithThePinnedKey()
			throws IOException {
		assertSignedWith(read(LIST),
				SIGNATURE.isFile() ? read(SIGNATURE) : null,
				BridgeListKeys.pinnedOnly().forNetwork());
	}

	@Test
	public void theSignatureCheckPassesOnlyForAGoodSignature()
			throws IOException {
		Signer temp = new Signer();
		List<byte[]> key = Collections.singletonList(temp.publicKey());
		byte[] file = read(LIST);
		assertSignedWith(file, temp.sign(file), key);
		assertRefused(() -> assertSignedWith(file, null, key));
		assertRefused(() -> assertSignedWith(file, new Signer().sign(file),
				key));
		byte[] changed = file.clone();
		changed[changed.length / 2] ^= 1;
		assertRefused(() -> assertSignedWith(changed, temp.sign(file), key));
		assertRefused(() -> assertSignedWith(file, temp.sign(file),
				BridgeListKeys.pinnedOnly().forNetwork()));
	}

	@Test
	public void aLaterListMayCarryOtherLinesButTheBundledOneMayNot() {
		long later = BridgeListStore.BUNDLED_SEQ + 1;
		String issued = "2026-10-18";
		String expires = "2027-01-16";
		byte[] other = BridgeListFixtures.bytes(BridgeListFixtures.json(later,
				issued, expires, BridgeListFixtures.groups(
						BridgeList.OBFS4_DEFAULT, Arrays.asList(
								BridgeListFixtures.obfs4(1),
								BridgeListFixtures.obfs4(2),
								BridgeListFixtures.obfs4(3)))));
		assertFollowsTheReleaseRule(other);
		Signer temp = new Signer();
		assertSignedWith(other, temp.sign(other),
				Collections.singletonList(temp.publicKey()));

		byte[] sameSeq = BridgeListFixtures.bytes(BridgeListFixtures.json(
				BridgeListStore.BUNDLED_SEQ, issued, expires,
				BridgeListFixtures.groups(BridgeList.OBFS4_DEFAULT,
						Arrays.asList(BridgeListFixtures.obfs4(1)))));
		assertRefused(() -> assertFollowsTheReleaseRule(sameSeq));
		assertRefused(() -> assertFollowsTheReleaseRule(
				BridgeListFixtures.bytes("{}")));
	}

	@Test
	public void thePinnedKeyIsTheDedicatedBridgeListKey() {
		assertEquals("bJJq5mVcoBaEVU3hOWnr+FyR/gPqQKoBk5UbNEYIfN8=",
				BridgeListKeys.PINNED_KEY);
		assertEquals(32, BridgeListKeys.decodeKey(BridgeListKeys.PINNED_KEY)
				.length);
		BridgeListKeys keys = BridgeListKeys.pinnedOnly();
		assertEquals(1, keys.forNetwork().size());
		assertEquals(1, keys.forLocalFile().size());
		assertFalse(keys.hasLocalKeys());
		BridgeListKeys withLocal = BridgeListKeys.withLocalKeys(
				Collections.singletonList(
						"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
		assertEquals("a local key never verifies a downloaded list", 1,
				withLocal.forNetwork().size());
		assertEquals(2, withLocal.forLocalFile().size());
	}

	static void assertFollowsTheReleaseRule(byte[] file) {
		String text = new String(file, StandardCharsets.US_ASCII);
		assertFalse("LF only, as GitHub serves it", text.contains("\r"));
		assertTrue(text.endsWith("}\n"));
		BridgeList list = BridgeList.parse(file);
		assertNotNull("docs/bridges/bridges.json does not parse with the"
				+ " app's BridgeList", list);
		assertTrue("a published list is never older than the bundled one",
				list.getSeq() >= BridgeListStore.BUNDLED_SEQ);
		assertTrue(list.getExpiresDay() > list.getIssuedDay());
		if (list.getSeq() > BridgeListStore.BUNDLED_SEQ) return;
		assertEquals("the list of the bundled sequence carries the bundled"
				+ " lines", BridgeListFixtures.bundled("d"),
				list.lines(BridgeType.DEFAULT_OBFS4));
		assertEquals(BridgeListFixtures.bundled("n"),
				list.lines(BridgeType.NON_DEFAULT_OBFS4));
		assertEquals(BridgeListFixtures.bundled("v"),
				list.lines(BridgeType.VANILLA));
		int groups = text.split("\": \\[", -1).length - 1;
		assertEquals("only the three groups the app uses", 3, groups);
	}

	static void assertSignedWith(byte[] file, @Nullable byte[] signature,
			List<byte[]> keys) {
		assertNotNull("docs/bridges/bridges.json.sig is missing; the"
				+ " operator signs docs/bridges/bridges.json with"
				+ " SignBridgeList.java and the offline bridge-list key",
				signature);
		assertTrue("docs/bridges/bridges.json.sig does not verify with "
				+ "the pinned key " + BridgeListKeys.PINNED_KEY,
				BridgeList.signatureValid(file, signature, keys));
		BridgeList list = BridgeList.parse(file);
		assertNotNull(list);
		BridgeListStore store = new BridgeListStore(new NamespacedSettings(),
				new NamespacedSettings.TestClock(
						list.getIssuedDay() * DAY_MS),
				new BridgeListKeys(keys, Collections.<byte[]>emptyList()), () -> "account");
		assertEquals("the app takes it on its issue day",
				list.getSeq() > BridgeListStore.BUNDLED_SEQ
						? BridgeListStore.Verdict.ACCEPTED
						: BridgeListStore.Verdict.NOT_NEWER,
				store.offer(file, signature, false));
	}

	private static void assertRefused(Runnable check) {
		boolean refused = false;
		try {
			check.run();
		} catch (AssertionError expected) {
			refused = true;
		}
		assertTrue("the check let a bad case through", refused);
	}
}
