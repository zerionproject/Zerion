package org.zerionproject.core.transport;

import org.zerionproject.core.api.crypto.StreamDecrypter;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import static org.zerionproject.core.api.transport.TransportConstants.MAX_PAYLOAD_LENGTH;
import static org.junit.Assert.assertEquals;

public class StreamReaderImplTest extends BrambleMockTestCase {

	@Test
	public void testEmptyFramesAreSkipped() throws Exception {
		StreamDecrypter decrypter = context.mock(StreamDecrypter.class);
		context.checking(new Expectations() {{
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(0));
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(2));
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(0));
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(-1));
		}});
		StreamReaderImpl r = new StreamReaderImpl(decrypter);
		assertEquals(0, r.read());
		assertEquals(0, r.read());
		assertEquals(-1, r.read());
		assertEquals(-1, r.read());
		r.close();
	}

	@Test
	public void testEmptyFramesAreSkippedWithBuffer() throws Exception {
		StreamDecrypter decrypter = context.mock(StreamDecrypter.class);
		context.checking(new Expectations() {{
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(0));
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(2));
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(0));
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(-1));
		}});
		StreamReaderImpl r = new StreamReaderImpl(decrypter);
		byte[] buf = new byte[MAX_PAYLOAD_LENGTH];

		assertEquals(2, r.read(buf));

		assertEquals(-1, r.read(buf));

		assertEquals(-1, r.read(buf));
		r.close();
	}

	@Test
	public void testMultipleReadsPerFrame() throws Exception {
		StreamDecrypter decrypter = context.mock(StreamDecrypter.class);
		context.checking(new Expectations() {{
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(MAX_PAYLOAD_LENGTH));
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(-1));
		}});
		StreamReaderImpl r = new StreamReaderImpl(decrypter);
		byte[] buf = new byte[MAX_PAYLOAD_LENGTH / 2];

		assertEquals(MAX_PAYLOAD_LENGTH / 2, r.read(buf));

		assertEquals(MAX_PAYLOAD_LENGTH / 2, r.read(buf));

		assertEquals(-1, r.read(buf, 0, buf.length));
		r.close();
	}

	@Test
	public void testMultipleReadsPerFrameWithOffsets() throws Exception {
		StreamDecrypter decrypter = context.mock(StreamDecrypter.class);
		context.checking(new Expectations() {{
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(MAX_PAYLOAD_LENGTH));
			oneOf(decrypter).readFrame(with(any(byte[].class)));
			will(returnValue(-1));
		}});
		StreamReaderImpl r = new StreamReaderImpl(decrypter);
		byte[] buf = new byte[MAX_PAYLOAD_LENGTH];

		assertEquals(MAX_PAYLOAD_LENGTH / 2, r.read(buf, MAX_PAYLOAD_LENGTH / 2,
				MAX_PAYLOAD_LENGTH / 2));

		assertEquals(MAX_PAYLOAD_LENGTH / 2, r.read(buf, 123,
				MAX_PAYLOAD_LENGTH / 2));

		assertEquals(-1, r.read(buf, 0, buf.length));
		r.close();
	}
}
