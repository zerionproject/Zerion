package org.zerionproject.core.data;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Random;

import static org.zerionproject.core.api.data.BdfReader.DEFAULT_MAX_BUFFER_SIZE;
import static org.zerionproject.core.api.data.BdfReader.DEFAULT_NESTED_LIMIT;
import static org.zerionproject.core.test.TestUtils.isOptionalTestEnabled;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class BdfReaderImplFuzzingTest extends BrambleTestCase {

	@Before
	public void setUp() {
		assumeTrue(isOptionalTestEnabled(BdfReaderImplFuzzingTest.class));
	}

	@Test
	public void testStringFuzzing() throws Exception {
		Random random = new Random();
		byte[] buf = new byte[22];
		ByteArrayInputStream in = new ByteArrayInputStream(buf);
		for (int i = 0; i < 100_000_000; i++) {
			random.nextBytes(buf);
			buf[0] = 0x41;
			buf[1] = 0x14;
			in.reset();
			BdfReaderImpl r = new BdfReaderImpl(in, DEFAULT_NESTED_LIMIT,
					DEFAULT_MAX_BUFFER_SIZE, true);
			try {
				int length = r.readString().length();
				assertTrue(length <= 20);
				assertTrue(r.eof());
			} catch (FormatException e) {

			}
		}
	}
}
