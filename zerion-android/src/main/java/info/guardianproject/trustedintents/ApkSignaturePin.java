package info.guardianproject.trustedintents;

import android.content.pm.Signature;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public abstract class ApkSignaturePin {

	protected String[] fingerprints;
	protected byte[][] certificates;
	private Signature[] signatures;

	public Signature[] getSignatures() {
		if (signatures == null) {
			signatures = new Signature[certificates.length];
			for (int i = 0; i < certificates.length; i++)
				signatures[i] = new Signature(certificates[i]);
		}
		return signatures;
	}

	
	public String getFingerprint(String algorithm) {
		try {
			MessageDigest md = MessageDigest.getInstance(algorithm);
			byte[] hashBytes = md.digest(certificates[0]);
			BigInteger bi = new BigInteger(1, hashBytes);
			md.reset();
			return String.format("%0" + (hashBytes.length << 1) + "x", bi);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		return null;
	}

	
	public String getMD5Fingerprint() {
		return getFingerprint("MD5");
	}

	
	public String getSHA1Fingerprint() {
		return getFingerprint("SHA1");
	}

	
	public String getSHA256Fingerprint() {
		return getFingerprint("SHA-256");
	}

	
	public boolean doFingerprintsMatchCertificates() {
		if (fingerprints == null || certificates == null)
			return false;
		String[] calcedFingerprints = new String[certificates.length];
		for (int i = 0; i < calcedFingerprints.length; i++)
			calcedFingerprints[i] = getSHA256Fingerprint();
		if (fingerprints.length == 0 || calcedFingerprints.length == 0)
			return false;
		return Arrays.equals(fingerprints, calcedFingerprints);
	}
}
