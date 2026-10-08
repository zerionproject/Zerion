package com.professor.zerion.android.update;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ReleaseAnnouncementsTest {

	private static final String RELEASES =
			"https://github.com/zerionproject/Zerion/releases";
	private static final String CERT =
			"d7fdb11125890d133ae89d8ba4f4331d9045e21ef01d9899a7cdee6888f704c8";

	private static KeyPair ours;
	private static KeyPair theirs;
	private static KeyPair ourEc;
	private static byte[] repoManifest;

	@BeforeClass
	public static void keys() throws Exception {
		KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
		rsa.initialize(2048);
		ours = rsa.generateKeyPair();
		theirs = rsa.generateKeyPair();
		KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
		ec.initialize(256);
		ourEc = ec.generateKeyPair();
		repoManifest = Files.readAllBytes(
				new File("../docs/release-manifest.json").toPath());
	}

	private static List<ReleaseAnnouncements.Signer> ourSigner() {
		return Collections.singletonList(
				new ReleaseAnnouncements.Signer(ours.getPublic(), CERT));
	}

	private static byte[] sign(PrivateKey key, String alg, byte[] context,
			byte[] manifest) throws Exception {
		Signature s = Signature.getInstance(alg);
		s.initSign(key);
		s.update(context);
		s.update(manifest);
		return (Base64.getEncoder().encodeToString(s.sign()) + "\n")
				.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] sign(byte[] manifest) throws Exception {
		return sign(ours.getPrivate(), "SHA256withRSA",
				ReleaseAnnouncements.CONTEXT, manifest);
	}

	private static byte[] manifest(String version, String code, String tag,
			String url, String sha, String cert) {
		return ("{\"android\":{\"version\":" + version + ",\"versionCode\":"
				+ code + ",\"tag\":" + tag + ",\"signingCertSha256\":" + cert
				+ ",\"apk\":{\"url\":" + url + ",\"sha256\":" + sha + "}}}")
				.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] good(String v, long code) {
		return manifest("\"" + v + "\"", String.valueOf(code), "\"v" + v + "\"",
				"\"" + RELEASES + "/download/v" + v + "/zerion-" + v + ".apk\"",
				"\"" + repeat('a') + "\"", "\"" + CERT + "\"");
	}

	private static String repeat(char c) {
		char[] a = new char[64];
		Arrays.fill(a, c);
		return new String(a);
	}

	@Nullable
	private static ReleaseAnnouncement accept(byte[] manifest, byte[] sig) {
		String signer = ReleaseAnnouncements.verifiedSigner(manifest, sig,
				ourSigner());
		return signer == null ? null
				: ReleaseAnnouncements.parse(manifest, RELEASES, signer);
	}

	@Test
	public void theRepositoryManifestSignedByUsIsAccepted() throws Exception {
		ReleaseAnnouncement a = accept(repoManifest, sign(repoManifest));
		assertNotNull(a);
		assertEquals("3.0.16", a.versionName);
		assertEquals(31600, a.versionCode);
		assertEquals(RELEASES + "/tag/v3.0.16", a.releasePageUrl);
		assertEquals(RELEASES + "/download/v3.0.16/zerion-3.0.16.apk",
				a.apkUrl);
		assertEquals(64, a.apkSha256.length());
	}

	@Test
	public void anEcSignatureFromOurKeyIsAccepted() throws Exception {
		byte[] m = good("3.0.17", 31700);
		byte[] sig = sign(ourEc.getPrivate(), "SHA256withECDSA",
				ReleaseAnnouncements.CONTEXT, m);
		String signer = ReleaseAnnouncements.verifiedSigner(m, sig,
				Collections.singletonList(new ReleaseAnnouncements.Signer(
						ourEc.getPublic(), CERT)));
		assertEquals(CERT, signer);
	}

	@Test
	public void aChangedByteAfterSigningIsRejected() throws Exception {
		byte[] m = good("3.0.17", 31700);
		byte[] sig = sign(m);
		byte[] tampered = good("3.0.18", 31800);
		assertNull(accept(tampered, sig));
		byte[] flipped = m.clone();
		flipped[flipped.length / 2] ^= 1;
		assertNull(accept(flipped, sig));
	}

	@Test
	public void aSignatureFromAnyOtherKeyIsRejected() throws Exception {
		byte[] m = good("3.0.17", 31700);
		byte[] sig = sign(theirs.getPrivate(), "SHA256withRSA",
				ReleaseAnnouncements.CONTEXT, m);
		assertNull(accept(m, sig));
	}

	@Test
	public void aSignatureMadeForAnotherPurposeIsRejected() throws Exception {
		byte[] m = good("3.0.17", 31700);
		assertNull(accept(m, sign(ours.getPrivate(), "SHA256withRSA",
				new byte[0], m)));
		assertNull(accept(m, sign(ours.getPrivate(), "SHA256withRSA",
				"zerion-release-manifest-v2\n".getBytes(
						StandardCharsets.US_ASCII), m)));
	}

	@Test
	public void missingOrMalformedSignaturesAreRejected() throws Exception {
		byte[] m = good("3.0.17", 31700);
		assertNull(accept(m, new byte[0]));
		assertNull(accept(m, "not base64 !!".getBytes(StandardCharsets.US_ASCII)));
		byte[] big = new byte[ReleaseAnnouncements.MAX_SIGNATURE_BYTES + 1];
		Arrays.fill(big, (byte) 'A');
		assertNull(accept(m, big));
	}

	@Test
	public void aLinkOutsideOurReleasesIsRejectedEvenWhenSigned()
			throws Exception {
		byte[] m = manifest("\"3.0.17\"", "31700", "\"v3.0.17\"",
				"\"https://example.org/zerion-3.0.17.apk\"",
				"\"" + repeat('a') + "\"", "\"" + CERT + "\"");
		assertNull(accept(m, sign(m)));
		byte[] other = manifest("\"3.0.17\"", "31700", "\"v3.0.17\"",
				"\"" + RELEASES + "/download/v3.0.17/other.apk\"",
				"\"" + repeat('a') + "\"", "\"" + CERT + "\"");
		assertNull(accept(other, sign(other)));
	}

	@Test
	public void inconsistentFieldsAreRejectedEvenWhenSigned() throws Exception {
		byte[][] bad = {
				manifest("\"3.0.17\"", "\"31700\"", "\"v3.0.17\"",
						"\"" + RELEASES + "/download/v3.0.17/zerion-3.0.17.apk\"",
						"\"" + repeat('a') + "\"", "\"" + CERT + "\""),
				manifest("\"3.0.17\"", "31700", "\"v3.0.18\"",
						"\"" + RELEASES + "/download/v3.0.17/zerion-3.0.17.apk\"",
						"\"" + repeat('a') + "\"", "\"" + CERT + "\""),
				manifest("\"3.0.17 beta\"", "31700", "\"v3.0.17 beta\"",
						"\"" + RELEASES + "/download/v3.0.17 beta/zerion-3.0.17 beta.apk\"",
						"\"" + repeat('a') + "\"", "\"" + CERT + "\""),
				manifest("\"3.0.17\"", "31700", "\"v3.0.17\"",
						"\"" + RELEASES + "/download/v3.0.17/zerion-3.0.17.apk\"",
						"\"" + repeat('G') + "\"", "\"" + CERT + "\""),
				manifest("\"3.0.17\"", "31700", "\"v3.0.17\"",
						"\"" + RELEASES + "/download/v3.0.17/zerion-3.0.17.apk\"",
						"\"" + repeat('a') + "\"", "\"" + repeat('b') + "\""),
				manifest("\"3.0.17\"", "-5", "\"v3.0.17\"",
						"\"" + RELEASES + "/download/v3.0.17/zerion-3.0.17.apk\"",
						"\"" + repeat('a') + "\"", "\"" + CERT + "\""),
				"not json".getBytes(StandardCharsets.UTF_8),
				new byte[] {(byte) 0xff, (byte) 0xfe, '{', '}'},
		};
		for (byte[] m : bad) assertNull(new String(m, StandardCharsets.ISO_8859_1),
				accept(m, sign(m)));
	}

	@Test
	public void anOversizedManifestIsRejected() throws Exception {
		byte[] m = new byte[ReleaseAnnouncements.MAX_MANIFEST_BYTES + 1];
		Arrays.fill(m, (byte) ' ');
		assertNull(accept(m, sign(m)));
	}
}
