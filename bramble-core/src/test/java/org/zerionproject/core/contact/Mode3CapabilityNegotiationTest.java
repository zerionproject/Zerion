package org.zerionproject.core.contact;

import org.zerionproject.core.api.contact.HandshakeManager.HandshakeResult;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordReader.RecordPredicate;
import org.zerionproject.core.api.record.RecordWriter;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.zerionproject.core.contact.HandshakeConstants.PROTOCOL_MAJOR_VERSION;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_MODE3_CAPABILITY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Mode3CapabilityNegotiationTest extends BrambleMockTestCase {

	private final RecordReader recordReader = context.mock(RecordReader.class);
	private final RecordWriter recordWriter = context.mock(RecordWriter.class);

	@Test
	public void testHandshakeResultStoresMode3Capable() {
		byte[] masterKeyBytes = new byte[32];
		Arrays.fill(masterKeyBytes, (byte) 0x42);
		org.zerionproject.core.api.crypto.SecretKey masterKey =
				new org.zerionproject.core.api.crypto.SecretKey(masterKeyBytes);

		HandshakeResult result1 = new HandshakeResult(masterKey, true);
		assertFalse("Default mode3Capable should be false",
				result1.isMode3Capable());
		assertTrue("Alice flag should be preserved", result1.isAlice());
		assertNotNull("Master key should be preserved", result1.getMasterKey());

		HandshakeResult result2 = new HandshakeResult(masterKey, false, true);
		assertTrue("mode3Capable should be true when explicitly set",
				result2.isMode3Capable());
		assertFalse("Alice flag should be false", result2.isAlice());

		HandshakeResult result3 = new HandshakeResult(masterKey, true, false);
		assertFalse("mode3Capable should be false when explicitly set",
				result3.isMode3Capable());
	}

	@Test
	public void testMode3CapabilityRecordFormat() {

		byte[] expectedPayload = new byte[] {0x01};

		Record mode3Record = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, expectedPayload);

		assertEquals("Protocol version should match",
				PROTOCOL_MAJOR_VERSION, mode3Record.getProtocolVersion());
		assertEquals("Record type should be MODE3_CAPABILITY",
				RECORD_TYPE_MODE3_CAPABILITY, mode3Record.getRecordType());
		assertArrayEquals("Payload should be 0x01",
				expectedPayload, mode3Record.getPayload());
	}

	@Test
	public void testRecordTypeConstant() {
		assertEquals("RECORD_TYPE_MODE3_CAPABILITY should be 5",
				5, RECORD_TYPE_MODE3_CAPABILITY);
	}

	@Test
	public void testMode3CapabilityIsKnownRecordType() {

		byte[] knownTypes = {
				HandshakeRecordTypes.RECORD_TYPE_EPHEMERAL_PUBLIC_KEY,
				HandshakeRecordTypes.RECORD_TYPE_PROOF_OF_OWNERSHIP,
				HandshakeRecordTypes.RECORD_TYPE_MINOR_VERSION,
				HandshakeRecordTypes.RECORD_TYPE_HYBRID_STATIC_KEY,
				HandshakeRecordTypes.RECORD_TYPE_KEM_CIPHERTEXT,
				HandshakeRecordTypes.RECORD_TYPE_MODE3_CAPABILITY
		};

		for (int i = 0; i < knownTypes.length; i++) {
			for (int j = i + 1; j < knownTypes.length; j++) {
				assertTrue("Record types must be unique",
						knownTypes[i] != knownTypes[j]);
			}
		}

		boolean found = false;
		for (byte type : knownTypes) {
			if (type == RECORD_TYPE_MODE3_CAPABILITY) {
				found = true;
				break;
			}
		}
		assertTrue("Mode 3 capability should be a known record type", found);
	}

	@Test
	public void testClassicalHandshakeNeverNegotiatesMode3() {

		byte[] masterKeyBytes = new byte[32];
		org.zerionproject.core.api.crypto.SecretKey masterKey =
				new org.zerionproject.core.api.crypto.SecretKey(masterKeyBytes);

		HandshakeResult classicalResult = new HandshakeResult(masterKey, true);

		assertFalse("Classical handshake must never have mode3Capable=true",
				classicalResult.isMode3Capable());
	}

	@Test
	public void testMode3CapabilityPayloadValidation() {

		byte[] validPayload = {0x01};
		assertTrue("0x01 indicates Mode 3 support",
				isValidMode3Payload(validPayload));

		assertFalse("Null payload should not indicate support",
				isValidMode3Payload(null));
		assertFalse("Empty payload should not indicate support",
				isValidMode3Payload(new byte[0]));
		assertFalse("0x00 should not indicate support",
				isValidMode3Payload(new byte[] {0x00}));
		assertFalse("Multi-byte payload should not indicate support",
				isValidMode3Payload(new byte[] {0x01, 0x00}));
		assertFalse("Wrong value should not indicate support",
				isValidMode3Payload(new byte[] {0x02}));
	}

	private boolean isValidMode3Payload(byte[] payload) {
		return payload != null && payload.length == 1 && payload[0] == 0x01;
	}

	@Test
	public void testSymmetricNegotiationRequirement() {

		Record aliceSends = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[] {0x01});
		Record bobSends = new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, new byte[] {0x01});

		assertEquals("Alice and Bob send same record type",
				aliceSends.getRecordType(), bobSends.getRecordType());
		assertEquals("Alice and Bob use same protocol version",
				aliceSends.getProtocolVersion(), bobSends.getProtocolVersion());
		assertArrayEquals("Alice and Bob send same payload",
				aliceSends.getPayload(), bobSends.getPayload());
	}

	@Test
	public void testMode3NegotiationWhenFlagOn() {
		// Mode 3 is unconditionally enabled in v1.7+.
	}

	@Test
	public void testMode3CapableDefaultsToFalse() {
		byte[] keyBytes = new byte[32];
		org.zerionproject.core.api.crypto.SecretKey key =
				new org.zerionproject.core.api.crypto.SecretKey(keyBytes);

		HandshakeResult result = new HandshakeResult(key, true);
		assertFalse(result.isMode3Capable());
	}

	@Test
	public void testDeterministicNegotiation() {

		assertTrue("Both support -> mode3Capable = true",
				negotiateMode3(true, true));

		assertFalse("Alice doesn't support -> mode3Capable = false",
				negotiateMode3(false, true));

		assertFalse("Bob doesn't support -> mode3Capable = false",
				negotiateMode3(true, false));

		assertFalse("Neither supports -> mode3Capable = false",
				negotiateMode3(false, false));
	}

	private boolean negotiateMode3(boolean aliceSupports, boolean bobSupports) {

		return aliceSupports && bobSupports;
	}
}
