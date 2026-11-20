package com.professor.zerion.android.vault.keepass;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

final class KdbxFixtures {

	static final char[] PASSWORD = "correct horse".toCharArray();
	static final String UNTITLED = "Untitled";

	static final String V4_ARGON2D = "kdbx4_argon2d_aes_chacha20.kdbx";
	static final String V4_ARGON2ID_CHACHA20 = "kdbx4_argon2id_chacha20.kdbx";
	static final String V4_AESKDF = "kdbx4_aeskdf.kdbx";
	static final String V4_TWOFISH = "kdbx4_twofish.kdbx";
	static final String V4_NOCOMPRESS = "kdbx4_nocompress.kdbx";
	static final String V4_KEYFILE_RAW = "kdbx4_keyfile_raw.kdbx";
	static final String V4_KEYFILE_XML2 = "kdbx4_keyfile_xml2.kdbx";
	static final String V3_AESKDF_SALSA20 = "kdbx3_aeskdf_salsa20.kdbx";
	static final String KEYFILE_RAW = "keyfile_raw.key";
	static final String KEYFILE_XML2 = "keyfile_v2.keyx";

	private KdbxFixtures() {
	}

	static byte[] load(String name) throws IOException {
		try (InputStream in = KdbxFixtures.class
				.getResourceAsStream("/keepass/" + name)) {
			if (in == null) throw new IOException("missing fixture " + name);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
			return out.toByteArray();
		}
	}

	static char[] password() {
		return PASSWORD.clone();
	}

	static int indexOf(byte[] data, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= data.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (data[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}

	static void fixHeaderHash(byte[] file, int headerLength) throws Exception {
		byte[] hash = MessageDigest.getInstance("SHA-256")
				.digest(java.util.Arrays.copyOf(file, headerLength));
		System.arraycopy(hash, 0, file, headerLength, 32);
	}
}
