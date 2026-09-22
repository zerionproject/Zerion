package com.professor.zerion.android.vault.utils;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * STO-04: a crafted .zenc header cannot make the importer allocate or run
 * beyond the parameters the app itself produces; it is refused before any
 * derivation starts.
 */
public class EncryptedFileExporterBoundsTest {

	private static final String OUT_OF_RANGE = "Argon2 parameters out of range";

	private static byte[] zencHeader(int memoryKb, int iterations,
			int parallelism, int filenameLength) {
		ByteBuffer b = ByteBuffer.allocate(160);
		b.put(EncryptedFileExporter.MAGIC_HEADER_V2);
		b.putInt(memoryKb);
		b.putInt(iterations);
		b.putInt(parallelism);
		b.putInt(filenameLength);
		return b.array();
	}

	private static void assertRefused(byte[] header) throws Exception {
		try {
			EncryptedFileExporter.importEncrypted(header, "pw".toCharArray());
			fail();
		} catch (IllegalArgumentException expected) {
			assertEquals(OUT_OF_RANGE, expected.getMessage());
		}
	}

	@Test
	public void oversizedParametersAreRefusedBeforeDerivation() throws Exception {
		assertRefused(zencHeader(Integer.MAX_VALUE, 3, 1, 8));
		assertRefused(zencHeader(256 * 1024 + 1, 3, 1, 8));
		assertRefused(zencHeader(64 * 1024, 11, 1, 8));
		assertRefused(zencHeader(64 * 1024, 3, 5, 8));
		assertRefused(zencHeader(64 * 1024, 0, 1, 8));
	}

	@Test
	public void saneParametersReachTheNextCheck() throws Exception {
		try {
			EncryptedFileExporter.importEncrypted(
					zencHeader(64 * 1024, 3, 1, 0), "pw".toCharArray());
			fail();
		} catch (IllegalArgumentException e) {
			if (OUT_OF_RANGE.equals(e.getMessage())) {
				fail("in-range parameters must pass the bound");
			}
		}
	}
}
