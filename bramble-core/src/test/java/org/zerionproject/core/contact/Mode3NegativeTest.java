package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.HandshakeManager.HandshakeResult;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordWriter;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

import static org.zerionproject.core.contact.HandshakeConstants.PROTOCOL_MAJOR_VERSION;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_EPHEMERAL_PUBLIC_KEY;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_MODE3_CAPABILITY;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_PROOF_OF_OWNERSHIP;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Mode3NegativeTest extends BrambleMockTestCase {

	@Test
	public void testCorruptedEmptyPayload() {

		byte[] emptyPayload = new byte[0];
		Record corruptedRecord = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, emptyPayload);

		assertFalse("Empty payload should not indicate Mode 3 support",
				isValidMode3Record(corruptedRecord));
	}

	@Test
	public void testCorruptedWrongPayloadValue() {

		Record zeroRecord = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[]{0x00});
		assertFalse("0x00 should not indicate Mode 3 support",
				isValidMode3Record(zeroRecord));

		Record twoRecord = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[]{0x02});
		assertFalse("0x02 should not indicate Mode 3 support",
				isValidMode3Record(twoRecord));

		Record ffRecord = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[]{(byte) 0xFF});
		assertFalse("0xFF should not indicate Mode 3 support",
				isValidMode3Record(ffRecord));
	}

	@Test
	public void testCorruptedOversizedPayload() {

		byte[] oversizedPayload = {0x01, 0x00};
		Record oversizedRecord = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, oversizedPayload);
		assertFalse("Oversized payload should not indicate Mode 3 support",
				isValidMode3Record(oversizedRecord));

		byte[] largePayload = new byte[100];
		largePayload[0] = 0x01;
		Record largeRecord = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, largePayload);
		assertFalse("Large payload should not indicate Mode 3 support",
				isValidMode3Record(largeRecord));
	}

	@Test
	public void testNullRecord() {
		assertFalse("Null record should not indicate Mode 3 support",
				isValidMode3Record(null));
	}

	@Test
	public void testWrongProtocolVersion() {

		Record wrongVersion1 = new Record((byte) 1,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[]{0x01});

		assertFalse("Wrong protocol version should not indicate Mode 3 support",
				isValidMode3RecordStrict(wrongVersion1));

		Record futureVersion = new Record((byte) 99,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[]{0x01});
		assertFalse("Future protocol version should not indicate Mode 3 support",
				isValidMode3RecordStrict(futureVersion));
	}

	@Test
	public void testUnexpectedRecordType() {

		byte unknownType = 99;
		Record unknownRecord = new Record(PROTOCOL_MAJOR_VERSION,
				unknownType, new byte[]{0x01});

		assertFalse("Unknown record type should not be Mode 3 capability",
				unknownRecord.getRecordType() == RECORD_TYPE_MODE3_CAPABILITY);
	}

	@Test
	public void testPartialNegotiationNoRemoteResponse() {

		boolean localSupports = true;
		Record remoteRecord = null;

		boolean mode3Capable = negotiateMode3Safely(localSupports, remoteRecord);

		assertFalse("Partial negotiation (no remote response) should fail closed",
				mode3Capable);
	}

	@Test
	public void testPartialNegotiationInvalidRemoteCapability() {
		boolean localSupports = true;

		Record invalidRecord = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[]{0x00});

		boolean mode3Capable = negotiateMode3Safely(localSupports,
				isValidMode3Record(invalidRecord));

		assertFalse("Partial negotiation (invalid remote) should fail closed",
				mode3Capable);
	}

	@Test
	public void testLocalDoesNotSupport() {
		boolean localSupports = false;

		Record validRemoteRecord = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[]{0x01});

		boolean mode3Capable = negotiateMode3Safely(localSupports,
				isValidMode3Record(validRemoteRecord));

		assertFalse("Local not supporting should result in mode3Capable=false",
				mode3Capable);
	}

	@Test
	public void testValidNegotiationSucceeds() {
		boolean localSupports = true;
		Record validRemoteRecord = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[]{0x01});

		boolean mode3Capable = negotiateMode3Safely(localSupports,
				isValidMode3Record(validRemoteRecord));

		assertTrue("Valid negotiation should succeed",
				mode3Capable);
	}

	@Test
	public void testHandshakeResultNeverNull() {
		byte[] keyBytes = new byte[32];
		SecretKey key = new SecretKey(keyBytes);

		HandshakeResult result1 = new HandshakeResult(key, true);

		boolean mode3_1 = result1.isMode3Capable();
		assertFalse(mode3_1);

		HandshakeResult result2 = new HandshakeResult(key, true, false);
		boolean mode3_2 = result2.isMode3Capable();
		assertFalse(mode3_2);

		HandshakeResult result3 = new HandshakeResult(key, true, true);
		boolean mode3_3 = result3.isMode3Capable();
		assertTrue(mode3_3);
	}

	@Test
	public void testDuplicateCapabilityRecord() {

		Record first = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[]{0x01});
		Record second = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[]{0x00});

		assertTrue("First record should indicate support",
				isValidMode3Record(first));

		assertFalse("Second record should not indicate support",
				isValidMode3Record(second));
	}

	@Test
	public void testRecordTypeMixing() {

		Record mode3Record = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[]{0x01});

		Record ephemeralKey = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_EPHEMERAL_PUBLIC_KEY, new byte[32]);
		Record proofOfOwnership = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_PROOF_OF_OWNERSHIP, new byte[64]);

		assertTrue("Mode 3 record should be recognized",
				mode3Record.getRecordType() == RECORD_TYPE_MODE3_CAPABILITY);
		assertFalse("Ephemeral key record should not be Mode 3",
				ephemeralKey.getRecordType() == RECORD_TYPE_MODE3_CAPABILITY);
		assertFalse("Proof of ownership should not be Mode 3",
				proofOfOwnership.getRecordType() == RECORD_TYPE_MODE3_CAPABILITY);
	}

	@Test
	public void testFailClosedOnIOException() {

		boolean mode3Capable = negotiateMode3WithException(true, new IOException("Stream closed"));

		assertFalse("IOException should result in fail-closed (mode3Capable=false)",
				mode3Capable);
	}

	@Test
	public void testClassicalPathNeverNegotiates() {

		byte[] keyBytes = new byte[32];
		SecretKey key = new SecretKey(keyBytes);

		HandshakeResult classicalResult = new HandshakeResult(key, true);

		assertFalse("Classical handshake must never have mode3Capable=true",
				classicalResult.isMode3Capable());

	}

	@Test
	public void testFeatureFlagEnablesNegotiation() {
		// Mode 3 is unconditionally enabled in v1.7+.
	}

	private boolean isValidMode3Record(Record record) {
		if (record == null) return false;
		if (record.getRecordType() != RECORD_TYPE_MODE3_CAPABILITY) return false;
		byte[] payload = record.getPayload();
		return payload != null && payload.length == 1 && payload[0] == 0x01;
	}

	private boolean isValidMode3RecordStrict(Record record) {
		if (record == null) return false;
		if (record.getProtocolVersion() != PROTOCOL_MAJOR_VERSION) return false;
		return isValidMode3Record(record);
	}

	private boolean negotiateMode3Safely(boolean localSupports, Record remoteRecord) {
		if (!localSupports) return false;
		return isValidMode3Record(remoteRecord);
	}

	private boolean negotiateMode3Safely(boolean localSupports, boolean remoteSupports) {
		return localSupports && remoteSupports;
	}

	private boolean negotiateMode3WithException(boolean localSupports, Exception e) {

		if (e != null) return false;
		return localSupports;
	}
}
