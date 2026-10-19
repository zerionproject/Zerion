package org.zerionproject.tor;

import org.junit.Test;
import org.zerionproject.tor.BridgeListFixtures.Signer;
import org.zerionproject.tor.CircumventionProvider.BridgeType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.tor.BridgeListFixtures.bytes;
import static org.zerionproject.tor.BridgeListFixtures.groups;
import static org.zerionproject.tor.BridgeListFixtures.json;
import static org.zerionproject.tor.BridgeListFixtures.obfs4;
import static org.zerionproject.tor.BridgeListFixtures.vanilla;

public class BridgeListTest {

	private static final String ISSUED = "2026-10-18";
	private static final String EXPIRES = "2027-01-16";

	private final Signer ours = new Signer();
	private final Signer theirs = new Signer();

	private static Map<String, List<String>> normalGroups() {
		return groups(BridgeList.OBFS4_DEFAULT,
				Arrays.asList(obfs4(1), obfs4(2), obfs4(3)),
				BridgeList.OBFS4_NON_DEFAULT,
				Arrays.asList(obfs4(4), obfs4(5)),
				BridgeList.VANILLA, Arrays.asList(vanilla(1), vanilla(2)));
	}

	private static byte[] normalList() {
		return bytes(json(7, ISSUED, EXPIRES, normalGroups()));
	}

	private List<byte[]> ourKey() {
		return Collections.singletonList(ours.publicKey());
	}

	@Test
	public void aListSignedWithThePinnedKeyVerifiesAndParses() {
		byte[] file = normalList();
		assertTrue(BridgeList.signatureValid(file, ours.sign(file), ourKey()));
		BridgeList list = BridgeList.parse(file);
		assertNotNull(list);
		assertEquals(7, list.getSeq());
		assertEquals(LocalDate.parse(ISSUED).toEpochDay(), list.getIssuedDay());
		assertEquals(LocalDate.parse(EXPIRES).toEpochDay(),
				list.getExpiresDay());
		assertEquals(Arrays.asList(obfs4(1), obfs4(2), obfs4(3)),
				list.lines(BridgeType.DEFAULT_OBFS4));
		assertEquals(Arrays.asList(obfs4(4), obfs4(5)),
				list.lines(BridgeType.NON_DEFAULT_OBFS4));
		assertEquals(Arrays.asList(vanilla(1), vanilla(2)),
				list.lines(BridgeType.VANILLA));
		assertTrue(list.lines(BridgeType.SNOWFLAKE).isEmpty());
		assertTrue(list.lines(BridgeType.MEEK).isEmpty());
	}

	@Test
	public void aChangedByteOrAnotherKeyOrAnotherContextIsRejected() {
		byte[] file = normalList();
		byte[] sig = ours.sign(file);
		byte[] changed = file.clone();
		changed[changed.length / 2] ^= 1;
		assertFalse(BridgeList.signatureValid(changed, sig, ourKey()));
		assertFalse(BridgeList.signatureValid(file, theirs.sign(file),
				ourKey()));
		assertFalse(BridgeList.signatureValid(file, sig,
				Collections.singletonList(theirs.publicKey())));
		assertFalse(BridgeList.signatureValid(file, sig,
				Collections.<byte[]>emptyList()));
		byte[] bare = Signer.encode(ours.signRaw(file));
		assertFalse("the context line is part of the signed message",
				BridgeList.signatureValid(file, bare, ourKey()));
		byte[] release = Signer.encode(ours.signRaw(concat(
				bytes("zerion-release-manifest-v1\n"), file)));
		assertFalse(BridgeList.signatureValid(file, release, ourKey()));
		byte[] otherContext = bytes(json("zerion-bridge-list-v2", "7", ISSUED,
				EXPIRES, normalGroups(), ""));
		assertTrue(BridgeList.signatureValid(otherContext,
				ours.sign(otherContext), ourKey()));
		assertNull("a signed list with another context is still refused",
				BridgeList.parse(otherContext));
	}

	@Test
	public void onlyACanonicalSixtyFourByteSignatureIsRead() {
		byte[] file = normalList();
		byte[] raw = ours.signRaw(BridgeList.signedMessage(file));
		String b64 = java.util.Base64.getEncoder().encodeToString(raw);
		assertTrue(BridgeList.signatureValid(file, bytes("  " + b64 + "\r\n"),
				ourKey()));
		assertFalse(BridgeList.signatureValid(file, bytes(""), ourKey()));
		assertFalse(BridgeList.signatureValid(file,
				bytes(b64.substring(0, 84)), ourKey()));
		assertFalse(BridgeList.signatureValid(file, bytes(b64 + b64),
				ourKey()));
		assertFalse(BridgeList.signatureValid(file,
				bytes(b64.substring(0, 85) + "B=="), ourKey()));
		assertFalse(BridgeList.signatureValid(file,
				bytes(b64.replace('+', '-').replace('/', '_')
						.replace("==", "")), ourKey()));
		byte[] big = new byte[BridgeList.MAX_SIGNATURE_FILE_BYTES + 1];
		Arrays.fill(big, (byte) ' ');
		byte[] tail = bytes(b64);
		System.arraycopy(tail, 0, big, big.length - tail.length, tail.length);
		assertFalse(BridgeList.signatureValid(file, big, ourKey()));
		byte[] flipped = raw.clone();
		flipped[0] ^= 1;
		assertFalse(BridgeList.signatureValid(file, Signer.encode(flipped),
				ourKey()));
	}

	@Test
	public void filesAboveTheSizeCapAreRefusedBeforeAnyCheck() {
		StringBuilder pad = new StringBuilder();
		while (pad.length() <= BridgeList.MAX_FILE_BYTES) pad.append(' ');
		byte[] file = bytes(json(7, ISSUED, EXPIRES, normalGroups())
				.replace("{\n", "{\n" + pad));
		assertTrue(file.length > BridgeList.MAX_FILE_BYTES);
		assertFalse(BridgeList.signatureValid(file, ours.sign(file),
				ourKey()));
		assertNull(BridgeList.parse(file));
		assertNull(BridgeList.parse(new byte[0]));
	}

	@Test
	public void groupAndLineCapsHold() {
		List<String> many = new ArrayList<>();
		for (int i = 0; i < BridgeList.MAX_GROUP_LINES + 1; i++) {
			many.add(obfs4(100 + i));
		}
		assertNull(BridgeList.parse(bytes(json(7, ISSUED, EXPIRES,
				groups(BridgeList.OBFS4_DEFAULT, many)))));
		assertNotNull(BridgeList.parse(bytes(json(7, ISSUED, EXPIRES,
				groups(BridgeList.OBFS4_DEFAULT,
						many.subList(0, BridgeList.MAX_GROUP_LINES))))));

		Object[] spread = new Object[2 * 5];
		for (int g = 0; g < 5; g++) {
			List<String> lines = new ArrayList<>();
			for (int i = 0; i < 52; i++) lines.add(obfs4(1000 + g * 100 + i));
			spread[2 * g] = g == 0 ? BridgeList.OBFS4_DEFAULT : "spare-" + g;
			spread[2 * g + 1] = lines;
		}
		assertNull("more than the total cap",
				BridgeList.parse(bytes(json(7, ISSUED, EXPIRES,
						groups(spread)))));

		Object[] tooManyGroups = new Object[2 * (BridgeList.MAX_GROUPS + 1)];
		for (int g = 0; g <= BridgeList.MAX_GROUPS; g++) {
			tooManyGroups[2 * g] = g == 0 ? BridgeList.VANILLA : "spare-" + g;
			tooManyGroups[2 * g + 1] = g == 0
					? Collections.singletonList(vanilla(1))
					: Collections.<String>emptyList();
		}
		assertNull(BridgeList.parse(bytes(json(7, ISSUED, EXPIRES,
				groups(tooManyGroups)))));

		StringBuilder longLine = new StringBuilder("future 45.33.9.9:443");
		while (longLine.length() <= BridgeLines.MAX_LINE_LENGTH) {
			longLine.append(" a=b");
		}
		assertNull(BridgeList.parse(bytes(json(7, ISSUED, EXPIRES,
				groups(BridgeList.VANILLA, Arrays.asList(vanilla(1)),
						"future", Arrays.asList(longLine.toString()))))));
	}

	@Test
	public void everyMalformedBridgeLineRejectsTheWholeList() {
		String fp = BridgeListFixtures.fingerprint(77);
		String cert = obfs4(1).split(" ")[3];
		String[] badObfs4 = {
				"obfs4 45.33.1.1:443 " + fp + " " + cert,
				"obfs4 45.33.1.1:443 " + fp + " " + cert + " iat-mode=3",
				"obfs4 45.33.1.1:443 " + fp + " cert=short iat-mode=0",
				"obfs4 45.33.1.1:443 " + fp.toLowerCase() + " " + cert
						+ " iat-mode=0",
				"obfs4 45.33.1.1:443 " + fp + " " + cert
						+ " iat-mode=0 url=https://example.com/",
				"obfs4 10.0.0.1:443 " + fp + " " + cert + " iat-mode=0",
				"obfs4 127.0.0.1:443 " + fp + " " + cert + " iat-mode=0",
				"obfs4 192.168.1.1:443 " + fp + " " + cert + " iat-mode=0",
				"obfs4 192.0.2.1:443 " + fp + " " + cert + " iat-mode=0",
				"obfs4 224.0.0.1:443 " + fp + " " + cert + " iat-mode=0",
				"obfs4 45.33.1.256:443 " + fp + " " + cert + " iat-mode=0",
				"obfs4 45.33.1.01:443 " + fp + " " + cert + " iat-mode=0",
				"obfs4 45.33.1.1:0 " + fp + " " + cert + " iat-mode=0",
				"obfs4 45.33.1.1:65536 " + fp + " " + cert + " iat-mode=0",
				"obfs4 example.com:443 " + fp + " " + cert + " iat-mode=0",
				"obfs4 [2001:4860::1]:443 " + fp + " " + cert + " iat-mode=0",
				"obfs4  45.33.1.1:443 " + fp + " " + cert + " iat-mode=0",
				" obfs4 45.33.1.1:443 " + fp + " " + cert + " iat-mode=0",
				"obfs4 45.33.1.1:443 " + fp + " " + cert + " iat-mode=0 ",
				"obfs4\t45.33.1.1:443 " + fp + " " + cert + " iat-mode=0",
				"Bridge obfs4 45.33.1.1:443 " + fp + " " + cert + " iat-mode=0",
				"snowflake 45.33.1.1:443 " + fp + " " + cert + " iat-mode=0",
				vanilla(3),
				BridgeListFixtures.bundled("s").get(0),
				BridgeListFixtures.bundled("m").get(0),
		};
		for (String bad : badObfs4) {
			for (String group : new String[] {BridgeList.OBFS4_DEFAULT,
					BridgeList.OBFS4_NON_DEFAULT}) {
				String text = json(7, ISSUED, EXPIRES, groups(group,
						Arrays.asList(obfs4(1), bad)));
				assertNull(group + ": " + bad, BridgeList.parse(bytes(text)));
			}
		}
		String[] badVanilla = {
				"45.79.1.1:9001",
				"45.79.1.1:9001 " + fp + " extra",
				"10.1.1.1:9001 " + fp,
				"45.79.1.1 " + fp,
				obfs4(9),
		};
		for (String bad : badVanilla) {
			String text = json(7, ISSUED, EXPIRES, groups(BridgeList.VANILLA,
					Arrays.asList(vanilla(1), bad)));
			assertNull(bad, BridgeList.parse(bytes(text)));
		}
	}

	@Test
	public void repeatedLinesOrFingerprintsAreRejected() {
		assertNull(BridgeList.parse(bytes(json(7, ISSUED, EXPIRES,
				groups(BridgeList.OBFS4_DEFAULT,
						Arrays.asList(obfs4(1), obfs4(1)))))));
		assertNull(BridgeList.parse(bytes(json(7, ISSUED, EXPIRES,
				groups(BridgeList.OBFS4_DEFAULT, Arrays.asList(obfs4(1)),
						BridgeList.OBFS4_NON_DEFAULT,
						Arrays.asList(obfs4(1)))))));
		String sameFingerprint = obfs4(1).replace("45.33.0.2:443",
				"45.33.0.99:443");
		assertFalse(sameFingerprint.equals(obfs4(1)));
		assertNull(BridgeList.parse(bytes(json(7, ISSUED, EXPIRES,
				groups(BridgeList.OBFS4_DEFAULT,
						Arrays.asList(obfs4(1), sameFingerprint))))));
	}

	@Test
	public void theJsonSubsetIsStrict() {
		String good = json(7, ISSUED, EXPIRES, normalGroups());
		assertNotNull(BridgeList.parse(bytes(good)));
		String[] bad = {
				good.replace("\"seq\": 7", "\"seq\": 7.0"),
				good.replace("\"seq\": 7", "\"seq\": -7"),
				good.replace("\"seq\": 7", "\"seq\": 07"),
				good.replace("\"seq\": 7", "\"seq\": 0"),
				good.replace("\"seq\": 7", "\"seq\": 7e2"),
				good.replace("\"seq\": 7", "\"seq\": \"7\""),
				good.replace("\"seq\": 7", "\"seq\": true"),
				good.replace("\"seq\": 7", "\"seq\": null"),
				good.replace("\"seq\": 7", "\"seq\": 1234567890123456"),
				good.replace("\"seq\": 7,", "\"seq\": 7, \"seq\": 8,"),
				good.replace("\"seq\": 7,", ""),
				good.replace("\"seq\": 7,", "\"seq\": 7, \"note\": \"x\","),
				good.replace("\"issued\"", "\"Issued\""),
				good.replace("2026-10-18", "2026-10-18T00:00:00Z"),
				good.replace("2026-10-18", "2026-02-30"),
				good.replace("2026-10-18", "2026-13-01"),
				good.replace("2026-10-18", "26-10-18"),
				good.replace("2027-01-16", "2026-10-17"),
				good.replace("2027-01-16", "2027-10-20"),
				good.replace("zerion-bridge-list-v1", "zerion-bridge-list-v1 "),
				good.replace("zerion-bridge-list-v1", "zerion\\u002dbridge-list-v1"),
				good.replace("\"context\"", "\"cont\\\"ext\""),
				good + "{}",
				good + "x",
				good.replace("\n", "\r\n"),
				good.replace("\"vanilla\": [", "\"vanilla\": [[\"x\"], "),
				good.replace("\"vanilla\": [", "\"vanilla\": [1, "),
				good.replace("\"vanilla\": [", "\"vanilla\": {\"a\": "),
				good.replace("\"vanilla\"", "\"Vanilla\""),
				good.replace("\"vanilla\"", "\"van illa\""),
				good.replace("]\n  }", "],\n  }"),
				good.replace("\"bridges\": {", "\"bridges\": [{").replace(
						"]\n  }\n}", "]\n  }]\n}"),
				"[" + good + "]",
				"",
				"   ",
				"{}",
		};
		for (String b : bad) {
			assertNull(b, BridgeList.parse(bytes(b)));
		}
		byte[] utf8 = good.replace("vanilla", "vanill\u00e4")
				.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		assertNull(BridgeList.parse(utf8));
		byte[] plain = bytes(good);
		byte[] bom = new byte[plain.length + 3];
		bom[0] = (byte) 0xEF;
		bom[1] = (byte) 0xBB;
		bom[2] = (byte) 0xBF;
		System.arraycopy(plain, 0, bom, 3, plain.length);
		assertNull(BridgeList.parse(bom));
	}

	@Test
	public void aListWithoutAnyUsableLineIsRejected() {
		assertNull(BridgeList.parse(bytes(json(7, ISSUED, EXPIRES,
				groups()))));
		assertNull(BridgeList.parse(bytes(json(7, ISSUED, EXPIRES,
				groups(BridgeList.OBFS4_DEFAULT,
						Collections.<String>emptyList(), "snowflake",
						BridgeListFixtures.bundled("s"))))));
	}

	@Test
	public void snowflakeMeekAndUnknownGroupsAreReadButNeverUsed() {
		String text = json(7, ISSUED, EXPIRES, groups(
				BridgeList.OBFS4_DEFAULT, Arrays.asList(obfs4(1)),
				"snowflake", Arrays.asList(
						"snowflake 192.0.2.3:80 " + BridgeListFixtures
								.fingerprint(1) + " url=https://broker."
								+ "example/ fronts=front.example"),
				"meek", Arrays.asList("meek_lite 192.0.2.20:80 "
						+ "url=https://meek.example front=front.example"),
				"webtunnel", Arrays.asList("webtunnel 45.33.7.7:443 "
						+ BridgeListFixtures.fingerprint(2)
						+ " url=https://tunnel.example/path"),
				"some-future-transport", Arrays.asList("x 45.33.7.8:1")));
		BridgeList list = BridgeList.parse(bytes(text));
		assertNotNull(list);
		assertEquals(Arrays.asList(obfs4(1)),
				list.lines(BridgeType.DEFAULT_OBFS4));
		for (BridgeType t : BridgeType.values()) {
			for (String line : list.lines(t)) {
				assertFalse(line, line.contains("example"));
			}
		}
		assertTrue(list.lines(BridgeType.SNOWFLAKE).isEmpty());
		assertTrue(list.lines(BridgeType.MEEK).isEmpty());
		assertTrue(list.lines(BridgeType.VANILLA).isEmpty());
	}

	@Test
	public void theDayNumbersAreTheCalendarDays() {
		for (LocalDate d = LocalDate.of(2020, 1, 1);
				d.isBefore(LocalDate.of(2101, 1, 1)); d = d.plusDays(1)) {
			assertEquals(d.toString(), d.toEpochDay(),
					BridgeList.epochDay(d.toString()));
		}
		assertEquals(-1, BridgeList.epochDay("2019-12-31"));
		assertEquals(-1, BridgeList.epochDay("2027-02-29"));
		assertEquals(LocalDate.of(2028, 2, 29).toEpochDay(),
				BridgeList.epochDay("2028-02-29"));
		assertEquals(-1, BridgeList.epochDay("2100-02-29"));
		assertEquals(-1, BridgeList.epochDay("2026-1-01"));
	}

	@Test
	public void aSignatureFromTheJdkSignerTheOperatorUsesVerifies()
			throws Exception {
		java.security.KeyPair pair = java.security.KeyPairGenerator
				.getInstance("Ed25519").generateKeyPair();
		byte[] spki = pair.getPublic().getEncoded();
		byte[] raw = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
		byte[] file = normalList();
		java.security.Signature jdk =
				java.security.Signature.getInstance("Ed25519");
		jdk.initSign(pair.getPrivate());
		jdk.update(BridgeList.signedMessage(file));
		byte[] sig = Signer.encode(jdk.sign());
		assertTrue(BridgeList.signatureValid(file, sig,
				Collections.singletonList(raw)));
		assertFalse(BridgeList.signatureValid(file, sig, ourKey()));
	}

	@Test
	public void theSignedMessageIsTheContextLineAndTheFile() {
		byte[] file = bytes("{}");
		assertArrayEquals(bytes("zerion-bridge-list-v1\n{}"),
				BridgeList.signedMessage(file));
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = new byte[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
