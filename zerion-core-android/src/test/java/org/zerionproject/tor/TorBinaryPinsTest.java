package org.zerionproject.tor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SC-TOR-02: the pins the app is built with are the values of the
 * executables the build verified, a file is accepted only when its hash is
 * one of them, and every way the pin data could be wrong is a refusal.
 */
public class TorBinaryPinsTest {

	private static final String TOR_ARM64 =
			"29eb99c78c803cdada5948c193308544f5c30414032c3334c3a83a124fcb9426";
	private static final String TOR_ARM32 =
			"418976d0958c8b422d71126e9fe34986657b96672b4fe6059107e41a0f7b8eaa";
	private static final String LYREBIRD_ARM64 =
			"34e258346e12648b7206941cd4af790ad61ca4c663e47426f3631ee98870dc5c";
	private static final String LYREBIRD_ARM32 =
			"ec54b954399a75cddbc76862fbd887d91714531e21be03795708483809a2e7ed";

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final Random random = new Random(7);

	private static TorBinaryPins parse(String text) throws IOException {
		return TorBinaryPins.parse(
				new ByteArrayInputStream(text.getBytes(UTF_8)));
	}

	private static String sha256(byte[] b) throws Exception {
		byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
		StringBuilder sb = new StringBuilder();
		for (byte x : d) sb.append(String.format("%02x", x));
		return sb.toString();
	}

	private File fileWith(byte[] content) throws IOException {
		File f = tmp.newFile();
		Files.write(f.toPath(), content);
		return f;
	}

	private byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}

	@Test
	public void theShippedPinsCoverBothExecutablesOnBothAbis()
			throws Exception {
		TorBinaryPins pins = TorBinaryPins.shipped();
		assertEquals(new HashSet<>(Arrays.asList("libtor.so", "liblyrebird.so")),
				pins.libraries());
		assertEquals(new HashSet<>(Arrays.asList(TOR_ARM64, TOR_ARM32)),
				pins.pinsFor("libtor.so"));
		assertEquals(new HashSet<>(Arrays.asList(LYREBIRD_ARM64, LYREBIRD_ARM32)),
				pins.pinsFor("liblyrebird.so"));
	}

	@Test
	public void theShippedPinFileNamesOnlyThePackagedAbis()
			throws Exception {
		String text = new String(Files.readAllBytes(new File(
				"src/main/resources/org/zerionproject/tor/binaries.sha256")
				.toPath()), UTF_8);
		Set<String> abis = new HashSet<>();
		for (String line : text.split("\n")) {
			String t = line.trim();
			if (t.isEmpty() || t.startsWith("#")) continue;
			abis.add(t.substring(0, t.indexOf('/')));
		}
		assertEquals(new HashSet<>(Arrays.asList("arm64-v8a", "armeabi-v7a")),
				abis);
	}

	@Test
	public void aFileWithAPinnedHashIsAccepted() throws Exception {
		byte[] tor = randomBytes(100_000);
		byte[] lyrebird = randomBytes(50_000);
		TorBinaryPins pins = parse(
				"x/libtor.so " + sha256(tor) + "\n"
						+ "x/liblyrebird.so " + sha256(lyrebird) + "\n");
		pins.verify(fileWith(tor), fileWith(lyrebird));
	}

	@Test
	public void oneChangedByteIsRefused() throws Exception {
		byte[] tor = randomBytes(100_000);
		byte[] lyrebird = randomBytes(50_000);
		TorBinaryPins pins = parse(
				"x/libtor.so " + sha256(tor) + "\n"
						+ "x/liblyrebird.so " + sha256(lyrebird) + "\n");
		byte[] tampered = tor.clone();
		tampered[54_321] ^= 0x01;
		try {
			pins.verify(fileWith(tampered), fileWith(lyrebird));
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("libtor.so"));
		}
	}

	@Test
	public void aTamperedLyrebirdIsRefusedEvenWhenTorIsIntact()
			throws Exception {
		byte[] tor = randomBytes(10_000);
		byte[] lyrebird = randomBytes(10_000);
		TorBinaryPins pins = parse(
				"x/libtor.so " + sha256(tor) + "\n"
						+ "x/liblyrebird.so " + sha256(lyrebird) + "\n");
		byte[] tampered = Arrays.copyOf(lyrebird, lyrebird.length + 1);
		try {
			pins.verify(fileWith(tor), fileWith(tampered));
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("liblyrebird.so"));
		}
	}

	@Test
	public void aTruncatedFileIsRefused() throws Exception {
		byte[] tor = randomBytes(10_000);
		TorBinaryPins pins = parse("x/libtor.so " + sha256(tor) + "\n");
		try {
			pins.verify(fileWith(Arrays.copyOf(tor, 9_999)), "libtor.so");
			fail();
		} catch (IOException expected) {
		}
	}

	@Test
	public void anEmptyFileIsRefused() throws Exception {
		TorBinaryPins pins = parse("x/libtor.so " + sha256(randomBytes(5)) + "\n");
		try {
			pins.verify(fileWith(new byte[0]), "libtor.so");
			fail();
		} catch (IOException expected) {
		}
	}

	@Test
	public void aMissingFileIsRefused() throws Exception {
		TorBinaryPins pins = parse("x/libtor.so " + sha256(randomBytes(5)) + "\n");
		try {
			pins.verify(new File(tmp.getRoot(), "absent"), "libtor.so");
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("Missing"));
		}
	}

	@Test
	public void aLibraryWithoutAPinIsRefused() throws Exception {
		byte[] b = randomBytes(5);
		TorBinaryPins pins = parse("x/libtor.so " + sha256(b) + "\n");
		try {
			pins.verify(fileWith(b), "liblyrebird.so");
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("No pin"));
		}
	}

	@Test
	public void aHashPinnedForTheOtherLibraryDoesNotCount()
			throws Exception {
		byte[] b = randomBytes(5);
		TorBinaryPins pins = parse("x/liblyrebird.so " + sha256(b) + "\n"
				+ "x/libtor.so " + sha256(randomBytes(6)) + "\n");
		try {
			pins.verify(fileWith(b), "libtor.so");
			fail();
		} catch (IOException expected) {
		}
	}

	@Test
	public void hashCaseDoesNotMatterButContentDoes() throws Exception {
		byte[] b = randomBytes(64);
		TorBinaryPins pins = parse("x/libtor.so "
				+ sha256(b).toUpperCase() + "\n");
		pins.verify(fileWith(b), "libtor.so");
	}

	@Test
	public void emptyPinDataIsRefused() {
		for (String text : new String[] {"", "\n\n", "# only a comment\n"}) {
			try {
				parse(text);
				fail(text);
			} catch (IOException expected) {
			}
		}
	}

	@Test
	public void malformedPinDataIsRefused() {
		String good = "x/libtor.so " + TOR_ARM64 + "\n";
		String[] bad = {
				"x/libtor.so\n",
				"x/libtor.so " + TOR_ARM64 + " extra\n",
				"libtor.so " + TOR_ARM64 + "\n",
				"x/ " + TOR_ARM64 + "\n",
				"x/libtor.so " + TOR_ARM64.substring(1) + "\n",
				"x/libtor.so " + TOR_ARM64 + "0\n",
				"x/libtor.so " + TOR_ARM64.substring(0, 63) + "g\n",
		};
		for (String text : bad) {
			try {
				parse(good + text);
				fail(text);
			} catch (IOException expected) {
			}
		}
	}

	@Test
	public void commentsAndBlankLinesAreIgnored() throws Exception {
		TorBinaryPins pins = parse("# a\n\n  x/libtor.so " + TOR_ARM64
				+ "  \n\n# b\n");
		assertEquals(new HashSet<>(Arrays.asList(TOR_ARM64)),
				pins.pinsFor("libtor.so"));
	}

	@Test
	public void theSameContentVerifiesWhateverTheFileIsCalled()
			throws Exception {
		byte[] b = randomBytes(1000);
		TorBinaryPins pins = parse("x/libtor.so " + sha256(b) + "\n");
		File f = new File(tmp.getRoot(), "tor");
		Files.write(f.toPath(), b);
		pins.verify(f, "libtor.so");
	}
}
