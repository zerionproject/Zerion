package com.professor.zerion.android.contact.identity;

import javax.annotation.Nullable;

public final class IdentityDisplay {

	public static final int VERSION_CLASSICAL = 1;
	public static final int VERSION_HYBRID = 2;

	private IdentityDisplay() {
	}

	public static boolean hasHybrid(@Nullable byte[] mlDsaPub) {
		return mlDsaPub != null && mlDsaPub.length > 0;
	}

	@Nullable
	public static String hybridFingerprint(byte[] signingPub,
			@Nullable byte[] mlDsaPub) {
		if (!hasHybrid(mlDsaPub)) return null;
		return IdentityFingerprint.forIdentity(signingPub, mlDsaPub);
	}

	public static String classicalFingerprint(byte[] signingPub) {
		return IdentityFingerprint.forSigningPub(signingPub);
	}

	public static String lines(byte[] signingPub, @Nullable byte[] mlDsaPub,
			String hybridFormat, String classicalFormat) {
		String classical = String.format(classicalFormat,
				classicalFingerprint(signingPub));
		String hybrid = hybridFingerprint(signingPub, mlDsaPub);
		if (hybrid == null) return classical;
		return String.format(hybridFormat, hybrid) + "\n" + classical;
	}
}
