package org.zerionproject.core.transport;

import org.zerionproject.core.api.crypto.StreamEncrypter;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import static org.zerionproject.core.api.transport.TransportConstants.MAX_PAYLOAD_LENGTH;
import static org.junit.Assert.assertEquals;

public class StreamWriterImplTest extends BrambleMockTestCase {

	private StreamEncrypter createMockEncrypter() {
		StreamEncrypter encrypter = context.mock(StreamEncrypter.class);
		context.checking(new Expectations() {{
			allowing(encrypter).getMaxPayloadLength();
			will(returnValue(MAX_PAYLOAD_LENGTH));
		}});
		return encrypter;
	}

	@Test
	public void testCloseWithoutWritingWritesFinalFrame() throws Exception {
		StreamEncrypter encrypter = createMockEncrypter();
		context.checking(new Expectations() {{
			oneOf(encrypter).writeFrame(with(any(byte[].class)), with(0),
					with(0), with(true));
			oneOf(encrypter).flush();
		}});
		StreamWriterImpl w = new StreamWriterImpl(encrypter);
		w.close();
	}

	@Test
	public void testFlushWithoutBufferedDataWritesFrameAndFlushes()
			throws Exception {
		StreamEncrypter encrypter = createMockEncrypter();
		StreamWriterImpl w = new StreamWriterImpl(encrypter);
		context.checking(new Expectations() {{
			oneOf(encrypter).writeFrame(with(any(byte[].class)), with(0),
					with(0), with(false));
			oneOf(encrypter).flush();
		}});
		w.flush();
		context.assertIsSatisfied();

		context.checking(new Expectations() {{
			oneOf(encrypter).writeFrame(with(any(byte[].class)), with(0),
					with(0), with(true));
			oneOf(encrypter).flush();
		}});
		w.close();
	}

	@Test
	public void testFlushWithBufferedDataWritesFrameAndFlushes()
			throws Exception {
		StreamEncrypter encrypter = createMockEncrypter();
		StreamWriterImpl w = new StreamWriterImpl(encrypter);
		context.checking(new Expectations() {{
			oneOf(encrypter).writeFrame(with(any(byte[].class)), with(1),
					with(0), with(false));
			oneOf(encrypter).flush();
		}});
		w.write(0);
		w.flush();
		context.assertIsSatisfied();

		context.checking(new Expectations() {{
			oneOf(encrypter).writeFrame(with(any(byte[].class)), with(0),
					with(0), with(true));
			oneOf(encrypter).flush();
		}});
		w.close();
	}

	@Test
	public void testSingleByteWritesWriteFullFrame() throws Exception {
		StreamEncrypter encrypter = createMockEncrypter();
		StreamWriterImpl w = new StreamWriterImpl(encrypter);
		context.checking(new Expectations() {{
			oneOf(encrypter).writeFrame(with(any(byte[].class)),
					with(MAX_PAYLOAD_LENGTH), with(0), with(false));
		}});
		for (int i = 0; i < MAX_PAYLOAD_LENGTH; i++) w.write(0);
		context.assertIsSatisfied();

		context.checking(new Expectations() {{
			oneOf(encrypter).writeFrame(with(any(byte[].class)), with(0),
					with(0), with(true));
			oneOf(encrypter).flush();
		}});
		w.close();
	}

	@Test
	public void testMultiByteWritesWriteFullFrames() throws Exception {
		StreamEncrypter encrypter = createMockEncrypter();
		StreamWriterImpl w = new StreamWriterImpl(encrypter);
		context.checking(new Expectations() {{
			exactly(2).of(encrypter).writeFrame(with(any(byte[].class)),
					with(MAX_PAYLOAD_LENGTH), with(0), with(false));
		}});
		assertEquals(0, MAX_PAYLOAD_LENGTH % 2);
		byte[] b = new byte[MAX_PAYLOAD_LENGTH / 2];
		w.write(b);
		w.write(b);
		w.write(b);
		w.write(b);
		context.assertIsSatisfied();

		context.checking(new Expectations() {{
			oneOf(encrypter).writeFrame(with(any(byte[].class)), with(0),
					with(0), with(true));
			oneOf(encrypter).flush();
		}});
		w.close();
	}

	@Test
	public void testLargeMultiByteWriteWritesFullFrames() throws Exception {
		StreamEncrypter encrypter = createMockEncrypter();
		StreamWriterImpl w = new StreamWriterImpl(encrypter);
		context.checking(new Expectations() {{
			exactly(2).of(encrypter).writeFrame(with(any(byte[].class)),
					with(MAX_PAYLOAD_LENGTH), with(0), with(false));
			oneOf(encrypter).writeFrame(with(any(byte[].class)), with(1),
					with(0), with(true));
			oneOf(encrypter).flush();
		}});
		byte[] b = new byte[MAX_PAYLOAD_LENGTH * 2 + 1];
		w.write(b);
		w.close();
	}
}
