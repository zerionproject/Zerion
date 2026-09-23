package org.zerionproject.core.keyagreement;

import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.keyagreement.Payload;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.COMMIT_LENGTH;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.PROTOCOL_VERSION;
import static org.junit.Assert.assertEquals;

/**
 * The nearby pairing payload parser, which reads a QR code, only ever throws
 * an {@link IOException} on hostile input: random strings, strings that start
 * with the current version byte, and strings that start with a valid version
 * byte and a valid BDF list header.
 */
public class PayloadParserImplFuzzTest {

	private static final int RANDOM_INPUTS = 4000;

	private final Random random = new Random(31);
	private PayloadParserImpl parser;

	@Before
	public void setUp() throws Exception {
		Constructor<?> c = Class.forName(
				"org.zerionproject.core.data.BdfReaderFactoryImpl")
				.getDeclaredConstructor();
		c.setAccessible(true);
		parser = new PayloadParserImpl((BdfReaderFactory) c.newInstance());
	}

	@Test
	public void randomInputYieldsOnlyIoExceptions() {
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			byte[] raw = new byte[random.nextInt(200)];
			random.nextBytes(raw);
			if (raw.length > 0 && i % 2 == 0) raw[0] = (byte) PROTOCOL_VERSION;
			if (raw.length > 1 && i % 4 == 0) raw[1] = 0x60;
			parse(raw, "random input " + i);
		}
	}

	@Test
	public void emptyAndSingleByteInputsAreRefused() {
		for (int b = 0; b < 256; b++) {
			parse(new byte[] {(byte) b}, "single byte " + b);
		}
		parse(new byte[0], "empty");
	}

	private void parse(byte[] raw, String what) {
		try {
			Payload p = parser.parse(new String(raw, StandardCharsets.ISO_8859_1));
			assertEquals(what, COMMIT_LENGTH, p.getCommitment().length);
		} catch (IOException expected) {
		} catch (RuntimeException e) {
			throw new AssertionError(what + ": " + e, e);
		}
	}
}
