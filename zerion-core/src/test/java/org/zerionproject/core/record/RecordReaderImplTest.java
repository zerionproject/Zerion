package org.zerionproject.core.record;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordReader.RecordPredicate;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.util.ByteUtils;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;

import static org.zerionproject.core.api.record.Record.MAX_RECORD_PAYLOAD_BYTES;
import static org.zerionproject.core.api.record.Record.RECORD_HEADER_BYTES;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class RecordReaderImplTest extends BrambleTestCase {

	@Test
	public void testAcceptsEmptyPayload() throws Exception {

		byte[] header = new byte[] {1, 2, 0, 0, 0, 0};
		ByteArrayInputStream in = new ByteArrayInputStream(header);
		RecordReader reader = new RecordReaderImpl(in);
		Record record = reader.readRecord();
		assertEquals(1, record.getProtocolVersion());
		assertEquals(2, record.getRecordType());
		assertArrayEquals(new byte[0], record.getPayload());
	}

	@Test
	public void testAcceptsMaxLengthPayload() throws Exception {
		byte[] record =
				new byte[RECORD_HEADER_BYTES + MAX_RECORD_PAYLOAD_BYTES];

		record[0] = 1;
		record[1] = 2;
		ByteUtils.writeUint32(MAX_RECORD_PAYLOAD_BYTES, record, 2);
		ByteArrayInputStream in = new ByteArrayInputStream(record);
		RecordReader reader = new RecordReaderImpl(in);
		reader.readRecord();
	}

	@Test(expected = FormatException.class)
	public void testFormatExceptionIfPayloadLengthIsNegative()
			throws Exception {

		byte[] header = new byte[] {1, 2, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
		ByteArrayInputStream in = new ByteArrayInputStream(header);
		RecordReader reader = new RecordReaderImpl(in);
		reader.readRecord();
	}

	@Test(expected = FormatException.class)
	public void testFormatExceptionIfPayloadLengthIsTooLarge()
			throws Exception {

		byte[] header = new byte[RECORD_HEADER_BYTES];
		header[0] = 1;
		header[1] = 2;
		ByteUtils.writeUint32(MAX_RECORD_PAYLOAD_BYTES + 1, header, 2);
		ByteArrayInputStream in = new ByteArrayInputStream(header);
		RecordReader reader = new RecordReaderImpl(in);
		reader.readRecord();
	}

	@Test(expected = EOFException.class)
	public void testEofExceptionIfProtocolVersionIsMissing() throws Exception {
		ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
		RecordReader reader = new RecordReaderImpl(in);
		reader.readRecord();
	}

	@Test(expected = EOFException.class)
	public void testEofExceptionIfRecordTypeIsMissing() throws Exception {
		ByteArrayInputStream in = new ByteArrayInputStream(new byte[1]);
		RecordReader reader = new RecordReaderImpl(in);
		reader.readRecord();
	}

	@Test(expected = EOFException.class)
	public void testEofExceptionIfPayloadLengthIsMissing() throws Exception {
		ByteArrayInputStream in = new ByteArrayInputStream(new byte[2]);
		RecordReader reader = new RecordReaderImpl(in);
		reader.readRecord();
	}

	@Test(expected = EOFException.class)
	public void testEofExceptionIfPayloadLengthIsTruncated() throws Exception {

		ByteArrayInputStream in = new ByteArrayInputStream(new byte[5]);
		RecordReader reader = new RecordReaderImpl(in);
		reader.readRecord();
	}

	@Test(expected = EOFException.class)
	public void testEofExceptionIfPayloadIsTruncated() throws Exception {

		byte[] header = new byte[] {0, 0, 0, 0, 0, 1};
		ByteArrayInputStream in = new ByteArrayInputStream(header);
		RecordReader reader = new RecordReaderImpl(in);
		reader.readRecord();
	}

	@Test
	public void testAcceptsAndRejectsRecords() throws Exception {

		byte[] header1 = new byte[] {0, 0, 0, 0, 0, 123};

		byte[] header2 = new byte[] {0, 1, 0, 0, 0, 123};

		byte[] header3 = new byte[] {1, 0, 0, 0, 0, 123};

		byte[] payload = getRandomBytes(123);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(header1);
		out.write(payload);
		out.write(header2);
		out.write(payload);
		out.write(header3);
		out.write(payload);
		ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
		RecordReader reader = new RecordReaderImpl(in);

		RecordPredicate accept = r -> {
			byte version = r.getProtocolVersion(), type = r.getRecordType();
			return version == 0 && (type == 0 || type == 1);
		};

		RecordPredicate ignore = r -> {
			byte version = r.getProtocolVersion(), type = r.getRecordType();
			return version == 0 && !(type == 0 || type == 1);
		};

		Record r = reader.readRecord(accept, ignore);
		assertNotNull(r);
		assertEquals(0, r.getProtocolVersion());
		assertEquals(0, r.getRecordType());
		assertArrayEquals(payload, r.getPayload());

		r = reader.readRecord(accept, ignore);
		assertNotNull(r);
		assertEquals(0, r.getProtocolVersion());
		assertEquals(1, r.getRecordType());
		assertArrayEquals(payload, r.getPayload());

		try {
			reader.readRecord(accept, ignore);
			fail();
		} catch (FormatException expected) {

		}
	}

	@Test
	public void testAcceptsAndIgnoresRecords() throws Exception {

		byte[] header1 = new byte[] {0, 0, 0, 0, 0, 123};

		byte[] header2 = new byte[] {0, 2, 0, 0, 0, 123};

		byte[] header3 = new byte[] {0, 1, 0, 0, 0, 123};

		byte[] payload = getRandomBytes(123);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(header1);
		out.write(payload);
		out.write(header2);
		out.write(payload);
		out.write(header3);
		out.write(payload);
		ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
		RecordReader reader = new RecordReaderImpl(in);

		RecordPredicate accept = r -> {
			byte version = r.getProtocolVersion(), type = r.getRecordType();
			return version == 0 && (type == 0 || type == 1);
		};

		RecordPredicate ignore = r -> {
			byte version = r.getProtocolVersion(), type = r.getRecordType();
			return version == 0 && !(type == 0 || type == 1);
		};

		Record r = reader.readRecord(accept, ignore);
		assertNotNull(r);
		assertEquals(0, r.getProtocolVersion());
		assertEquals(0, r.getRecordType());
		assertArrayEquals(payload, r.getPayload());

		r = reader.readRecord(accept, ignore);
		assertNotNull(r);
		assertEquals(0, r.getProtocolVersion());
		assertEquals(1, r.getRecordType());
		assertArrayEquals(payload, r.getPayload());

		assertNull(reader.readRecord(accept, ignore));
	}
}
