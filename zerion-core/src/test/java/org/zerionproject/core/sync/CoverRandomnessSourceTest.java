package org.zerionproject.core.sync;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CoverRandomnessSourceTest {

	private static final String THREAD_LOCAL_RANDOM =
			"java/util/concurrent/ThreadLocalRandom";
	private static final String SECURE_RANDOM = "java/security/SecureRandom";

	@Test
	public void coverPaddingIsDrawnFromSecureRandom() throws IOException {
		assertDrawsOnlyFromSecureRandom(SyncRecordWriterImpl.class);
	}

	@Test
	public void coverJitterAndSlotSizesAreDrawnFromSecureRandom()
			throws IOException {
		assertDrawsOnlyFromSecureRandom(DuplexOutgoingSession.class);
	}

	private static void assertDrawsOnlyFromSecureRandom(Class<?> c)
			throws IOException {
		String constants = classFile(c);
		assertFalse(c.getSimpleName() + " still uses ThreadLocalRandom",
				constants.contains(THREAD_LOCAL_RANDOM));
		assertTrue(c.getSimpleName() + " does not use SecureRandom",
				constants.contains(SECURE_RANDOM));
	}

	private static String classFile(Class<?> c) throws IOException {
		try (InputStream in = c.getResourceAsStream(
				c.getSimpleName() + ".class")) {
			assertNotNull(in);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
			return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
		}
	}
}
