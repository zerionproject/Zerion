package com.professor.zerion.android.update;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@NotNullByDefault
final class ReleaseAnnouncements {

	static final int MAX_MANIFEST_BYTES = 64 * 1024;
	static final int MAX_SIGNATURE_BYTES = 4 * 1024;
	static final byte[] CONTEXT =
			"zerion-release-manifest-v1\n".getBytes(StandardCharsets.US_ASCII);

	private static final Pattern VERSION =
			Pattern.compile("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}");

	static final class Signer {
		final PublicKey key;
		final String certSha256;

		Signer(PublicKey key, String certSha256) {
			this.key = key;
			this.certSha256 = certSha256;
		}
	}

	private ReleaseAnnouncements() {
	}

	static List<Signer> signersFrom(List<X509Certificate> certs) {
		List<Signer> out = new ArrayList<>();
		for (X509Certificate c : certs) {
			try {
				out.add(new Signer(c.getPublicKey(), sha256(c.getEncoded())));
			} catch (GeneralSecurityException | RuntimeException ignored) {
			}
		}
		return out;
	}

	@Nullable
	static String verifiedSigner(byte[] manifest, byte[] signatureFile,
			List<Signer> signers) {
		if (manifest.length == 0 || manifest.length > MAX_MANIFEST_BYTES) {
			return null;
		}
		byte[] signature = decodeSignature(signatureFile);
		if (signature == null) return null;
		for (Signer signer : signers) {
			String algorithm = algorithmFor(signer.key);
			if (algorithm == null) continue;
			try {
				Signature s = Signature.getInstance(algorithm);
				s.initVerify(signer.key);
				s.update(CONTEXT);
				s.update(manifest);
				if (s.verify(signature)) return signer.certSha256;
			} catch (GeneralSecurityException | RuntimeException ignored) {
			}
		}
		return null;
	}

	@Nullable
	static ReleaseAnnouncement parse(byte[] manifest, String releasesUrl,
			String signerSha256) {
		if (manifest.length == 0 || manifest.length > MAX_MANIFEST_BYTES) {
			return null;
		}
		String text = strictUtf8(manifest);
		if (text == null) return null;
		try {
			JSONObject android = new JSONObject(text).getJSONObject("android");
			Object version = android.get("version");
			Object code = android.get("versionCode");
			Object tag = android.get("tag");
			Object cert = android.get("signingCertSha256");
			JSONObject apk = android.getJSONObject("apk");
			Object url = apk.get("url");
			Object sha = apk.get("sha256");
			if (!(version instanceof String) || !(tag instanceof String)
					|| !(cert instanceof String) || !(url instanceof String)
					|| !(sha instanceof String)) {
				return null;
			}
			if (!(code instanceof Integer) && !(code instanceof Long)) {
				return null;
			}
			String v = (String) version;
			long c = ((Number) code).longValue();
			if (!VERSION.matcher(v).matches()) return null;
			if (c <= 0 || c > Integer.MAX_VALUE) return null;
			if (!tag.equals("v" + v)) return null;
			if (!cert.equals(signerSha256)) return null;
			String expectedApk = releasesUrl + "/download/v" + v + "/zerion-"
					+ v + ".apk";
			if (!url.equals(expectedApk)) return null;
			if (!SHA256.matcher((String) sha).matches()) return null;
			return new ReleaseAnnouncement(v, c, expectedApk,
					releasesUrl + "/tag/v" + v, (String) sha);
		} catch (JSONException | RuntimeException e) {
			return null;
		}
	}

	@Nullable
	static byte[] decodeSignature(byte[] signatureFile) {
		if (signatureFile.length == 0
				|| signatureFile.length > MAX_SIGNATURE_BYTES) {
			return null;
		}
		String text = new String(signatureFile, StandardCharsets.US_ASCII)
				.trim();
		if (!BASE64.matcher(text).matches()) return null;
		try {
			byte[] decoded = Base64.getDecoder().decode(text);
			return decoded.length == 0 ? null : decoded;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static String sha256(byte[] data) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder sb = new StringBuilder(d.length * 2);
			for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
			return sb.toString();
		} catch (GeneralSecurityException e) {
			throw new AssertionError(e);
		}
	}

	@Nullable
	private static String algorithmFor(PublicKey key) {
		String a = key.getAlgorithm();
		if ("RSA".equals(a)) return "SHA256withRSA";
		if ("EC".equals(a)) return "SHA256withECDSA";
		return null;
	}

	@Nullable
	private static String strictUtf8(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			return null;
		}
	}
}
