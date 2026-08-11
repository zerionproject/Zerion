package org.zerionproject.core.keyagreement;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyAgreementCrypto;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.keyagreement.Payload;
import org.zerionproject.core.api.keyagreement.PayloadEncoder;
import org.zerionproject.core.test.BrambleTestCase;
import org.jmock.Expectations;
import org.jmock.auto.Mock;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.jmock.integration.junit4.JUnitRuleMockery;
import org.jmock.lib.concurrent.Synchroniser;
import org.junit.Rule;
import org.junit.Test;

import static java.util.Collections.emptyList;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.COMMIT_LENGTH;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.MASTER_KEY_LABEL;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.SHARED_SECRET_LABEL;
import static org.zerionproject.core.test.TestUtils.getAgreementPrivateKey;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class KeyAgreementProtocolTest extends BrambleTestCase {

	@Rule
	public JUnitRuleMockery context = new JUnitRuleMockery() {{

		setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		setThreadingPolicy(new Synchroniser());
	}};

	private final PublicKey alicePubKey = getAgreementPublicKey();
	private final byte[] aliceCommit = getRandomBytes(COMMIT_LENGTH);
	private final byte[] alicePayload = getRandomBytes(COMMIT_LENGTH + 8);
	private final byte[] aliceConfirm = getRandomBytes(SecretKey.LENGTH);

	private final PublicKey bobPubKey = getAgreementPublicKey();
	private final byte[] bobCommit = getRandomBytes(COMMIT_LENGTH);
	private final byte[] bobPayload = getRandomBytes(COMMIT_LENGTH + 19);
	private final byte[] bobConfirm = getRandomBytes(SecretKey.LENGTH);

	private final PublicKey badPubKey = getAgreementPublicKey();
	private final byte[] badCommit = getRandomBytes(COMMIT_LENGTH);
	private final byte[] badConfirm = getRandomBytes(SecretKey.LENGTH);

	@Mock
	KeyAgreementProtocol.Callbacks callbacks;
	@Mock
	CryptoComponent crypto;
	@Mock
	KeyAgreementCrypto keyAgreementCrypto;
	@Mock
	KeyParser keyParser;
	@Mock
	PayloadEncoder payloadEncoder;
	@Mock
	KeyAgreementTransport transport;

	@Test
	public void testAliceProtocol() throws Exception {

		Payload theirPayload = new Payload(bobCommit, emptyList());
		Payload ourPayload = new Payload(aliceCommit, emptyList());
		KeyPair ourKeyPair = new KeyPair(alicePubKey, getAgreementPrivateKey());
		SecretKey sharedSecret = getSecretKey();
		SecretKey masterKey = getSecretKey();

		KeyAgreementProtocol protocol = new KeyAgreementProtocol(callbacks,
				crypto, keyAgreementCrypto, payloadEncoder, transport,
				theirPayload, ourPayload, ourKeyPair, true);

		context.checking(new Expectations() {{

			allowing(payloadEncoder).encode(ourPayload);
			will(returnValue(alicePayload));
			allowing(payloadEncoder).encode(theirPayload);
			will(returnValue(bobPayload));
			allowing(crypto).getAgreementKeyParser();
			will(returnValue(keyParser));

			oneOf(transport).sendKey(alicePubKey.getEncoded());

			oneOf(callbacks).connectionWaiting();
			oneOf(transport).receiveKey();
			will(returnValue(bobPubKey.getEncoded()));
			oneOf(callbacks).initialRecordReceived();
			oneOf(keyParser).parsePublicKey(bobPubKey.getEncoded());
			will(returnValue(bobPubKey));

			oneOf(keyAgreementCrypto).deriveKeyCommitment(bobPubKey);
			will(returnValue(bobCommit));

			oneOf(crypto).deriveSharedSecret(SHARED_SECRET_LABEL, bobPubKey,
					ourKeyPair, new byte[] {PROTOCOL_VERSION},
					alicePubKey.getEncoded(), bobPubKey.getEncoded());
			will(returnValue(sharedSecret));

			oneOf(keyAgreementCrypto).deriveConfirmationRecord(sharedSecret,
					bobPayload, alicePayload, bobPubKey, ourKeyPair,
					true, true);
			will(returnValue(aliceConfirm));
			oneOf(transport).sendConfirm(aliceConfirm);

			oneOf(transport).receiveConfirm();
			will(returnValue(bobConfirm));

			oneOf(keyAgreementCrypto).deriveConfirmationRecord(sharedSecret,
					bobPayload, alicePayload, bobPubKey, ourKeyPair,
					true, false);
			will(returnValue(bobConfirm));

			oneOf(crypto).deriveKey(MASTER_KEY_LABEL, sharedSecret);
			will(returnValue(masterKey));
		}});

		assertThat(masterKey, is(equalTo(protocol.perform())));
	}

	@Test
	public void testBobProtocol() throws Exception {

		Payload theirPayload = new Payload(aliceCommit, emptyList());
		Payload ourPayload = new Payload(bobCommit, emptyList());
		KeyPair ourKeyPair = new KeyPair(bobPubKey, getAgreementPrivateKey());
		SecretKey sharedSecret = getSecretKey();
		SecretKey masterKey = getSecretKey();

		KeyAgreementProtocol protocol = new KeyAgreementProtocol(callbacks,
				crypto, keyAgreementCrypto, payloadEncoder, transport,
				theirPayload, ourPayload, ourKeyPair, false);

		context.checking(new Expectations() {{

			allowing(payloadEncoder).encode(ourPayload);
			will(returnValue(bobPayload));
			allowing(payloadEncoder).encode(theirPayload);
			will(returnValue(alicePayload));
			allowing(crypto).getAgreementKeyParser();
			will(returnValue(keyParser));

			oneOf(transport).receiveKey();
			will(returnValue(alicePubKey.getEncoded()));
			oneOf(callbacks).initialRecordReceived();
			oneOf(keyParser).parsePublicKey(alicePubKey.getEncoded());
			will(returnValue(alicePubKey));

			oneOf(keyAgreementCrypto).deriveKeyCommitment(alicePubKey);
			will(returnValue(aliceCommit));

			oneOf(transport).sendKey(bobPubKey.getEncoded());

			oneOf(crypto).deriveSharedSecret(SHARED_SECRET_LABEL, alicePubKey,
					ourKeyPair, new byte[] {PROTOCOL_VERSION},
					alicePubKey.getEncoded(), bobPubKey.getEncoded());
			will(returnValue(sharedSecret));

			oneOf(transport).receiveConfirm();
			will(returnValue(aliceConfirm));

			oneOf(keyAgreementCrypto).deriveConfirmationRecord(sharedSecret,
					alicePayload, bobPayload, alicePubKey, ourKeyPair,
					false, true);
			will(returnValue(aliceConfirm));

			oneOf(keyAgreementCrypto).deriveConfirmationRecord(sharedSecret,
					alicePayload, bobPayload, alicePubKey, ourKeyPair,
					false, false);
			will(returnValue(bobConfirm));
			oneOf(transport).sendConfirm(bobConfirm);

			oneOf(crypto).deriveKey(MASTER_KEY_LABEL, sharedSecret);
			will(returnValue(masterKey));
		}});

		assertThat(masterKey, is(equalTo(protocol.perform())));
	}

	@Test(expected = AbortException.class)
	public void testAliceProtocolAbortOnBadKey() throws Exception {

		Payload theirPayload = new Payload(bobCommit, emptyList());
		Payload ourPayload = new Payload(aliceCommit, emptyList());
		KeyPair ourKeyPair = new KeyPair(alicePubKey, getAgreementPrivateKey());

		KeyAgreementProtocol protocol = new KeyAgreementProtocol(callbacks,
				crypto, keyAgreementCrypto, payloadEncoder, transport,
				theirPayload, ourPayload, ourKeyPair, true);

		context.checking(new Expectations() {{

			allowing(crypto).getAgreementKeyParser();
			will(returnValue(keyParser));

			oneOf(transport).sendKey(alicePubKey.getEncoded());

			oneOf(callbacks).connectionWaiting();
			oneOf(transport).receiveKey();
			will(returnValue(badPubKey.getEncoded()));
			oneOf(callbacks).initialRecordReceived();
			oneOf(keyParser).parsePublicKey(badPubKey.getEncoded());
			will(returnValue(badPubKey));

			oneOf(keyAgreementCrypto).deriveKeyCommitment(badPubKey);
			will(returnValue(badCommit));

			oneOf(transport).sendAbort(false);

			never(crypto).deriveSharedSecret(SHARED_SECRET_LABEL, badPubKey,
					ourKeyPair, new byte[] {PROTOCOL_VERSION},
					alicePubKey.getEncoded(), bobPubKey.getEncoded());
		}});

		protocol.perform();
	}

	@Test(expected = AbortException.class)
	public void testBobProtocolAbortOnBadKey() throws Exception {

		Payload theirPayload = new Payload(aliceCommit, emptyList());
		Payload ourPayload = new Payload(bobCommit, emptyList());
		KeyPair ourKeyPair = new KeyPair(bobPubKey, getAgreementPrivateKey());

		KeyAgreementProtocol protocol = new KeyAgreementProtocol(callbacks,
				crypto, keyAgreementCrypto, payloadEncoder, transport,
				theirPayload, ourPayload, ourKeyPair, false);

		context.checking(new Expectations() {{

			allowing(crypto).getAgreementKeyParser();
			will(returnValue(keyParser));

			oneOf(transport).receiveKey();
			will(returnValue(badPubKey.getEncoded()));
			oneOf(callbacks).initialRecordReceived();
			oneOf(keyParser).parsePublicKey(badPubKey.getEncoded());
			will(returnValue(badPubKey));

			oneOf(keyAgreementCrypto).deriveKeyCommitment(badPubKey);
			will(returnValue(badCommit));

			oneOf(transport).sendAbort(false);

			never(transport).sendKey(bobPubKey.getEncoded());
		}});

		protocol.perform();
	}

	@Test(expected = AbortException.class)
	public void testAliceProtocolAbortOnBadConfirm() throws Exception {

		Payload theirPayload = new Payload(bobCommit, emptyList());
		Payload ourPayload = new Payload(aliceCommit, emptyList());
		KeyPair ourKeyPair = new KeyPair(alicePubKey, getAgreementPrivateKey());
		SecretKey sharedSecret = getSecretKey();

		KeyAgreementProtocol protocol = new KeyAgreementProtocol(callbacks,
				crypto, keyAgreementCrypto, payloadEncoder, transport,
				theirPayload, ourPayload, ourKeyPair, true);

		context.checking(new Expectations() {{

			allowing(payloadEncoder).encode(ourPayload);
			will(returnValue(alicePayload));
			allowing(payloadEncoder).encode(theirPayload);
			will(returnValue(bobPayload));
			allowing(crypto).getAgreementKeyParser();
			will(returnValue(keyParser));

			oneOf(transport).sendKey(alicePubKey.getEncoded());

			oneOf(callbacks).connectionWaiting();
			oneOf(transport).receiveKey();
			will(returnValue(bobPubKey.getEncoded()));
			oneOf(callbacks).initialRecordReceived();
			oneOf(keyParser).parsePublicKey(bobPubKey.getEncoded());
			will(returnValue(bobPubKey));

			oneOf(keyAgreementCrypto).deriveKeyCommitment(bobPubKey);
			will(returnValue(bobCommit));

			oneOf(crypto).deriveSharedSecret(SHARED_SECRET_LABEL, bobPubKey,
					ourKeyPair, new byte[] {PROTOCOL_VERSION},
					alicePubKey.getEncoded(), bobPubKey.getEncoded());
			will(returnValue(sharedSecret));

			oneOf(keyAgreementCrypto).deriveConfirmationRecord(sharedSecret,
					bobPayload, alicePayload, bobPubKey, ourKeyPair,
					true, true);
			will(returnValue(aliceConfirm));
			oneOf(transport).sendConfirm(aliceConfirm);

			oneOf(transport).receiveConfirm();
			will(returnValue(badConfirm));

			oneOf(keyAgreementCrypto).deriveConfirmationRecord(sharedSecret,
					bobPayload, alicePayload, bobPubKey, ourKeyPair,
					true, false);
			will(returnValue(bobConfirm));

			oneOf(transport).sendAbort(false);

			never(crypto).deriveKey(MASTER_KEY_LABEL, sharedSecret);
		}});

		protocol.perform();
	}

	@Test(expected = AbortException.class)
	public void testBobProtocolAbortOnBadConfirm() throws Exception {

		Payload theirPayload = new Payload(aliceCommit, emptyList());
		Payload ourPayload = new Payload(bobCommit, emptyList());
		KeyPair ourKeyPair = new KeyPair(bobPubKey, getAgreementPrivateKey());
		SecretKey sharedSecret = getSecretKey();

		KeyAgreementProtocol protocol = new KeyAgreementProtocol(callbacks,
				crypto, keyAgreementCrypto, payloadEncoder, transport,
				theirPayload, ourPayload, ourKeyPair, false);

		context.checking(new Expectations() {{

			allowing(payloadEncoder).encode(ourPayload);
			will(returnValue(bobPayload));
			allowing(payloadEncoder).encode(theirPayload);
			will(returnValue(alicePayload));
			allowing(crypto).getAgreementKeyParser();
			will(returnValue(keyParser));

			oneOf(transport).receiveKey();
			will(returnValue(alicePubKey.getEncoded()));
			oneOf(callbacks).initialRecordReceived();
			oneOf(keyParser).parsePublicKey(alicePubKey.getEncoded());
			will(returnValue(alicePubKey));

			oneOf(keyAgreementCrypto).deriveKeyCommitment(alicePubKey);
			will(returnValue(aliceCommit));

			oneOf(transport).sendKey(bobPubKey.getEncoded());

			oneOf(crypto).deriveSharedSecret(SHARED_SECRET_LABEL, alicePubKey,
					ourKeyPair, new byte[] {PROTOCOL_VERSION},
					alicePubKey.getEncoded(), bobPubKey.getEncoded());
			will(returnValue(sharedSecret));

			oneOf(transport).receiveConfirm();
			will(returnValue(badConfirm));

			oneOf(keyAgreementCrypto).deriveConfirmationRecord(sharedSecret,
					alicePayload, bobPayload, alicePubKey, ourKeyPair,
					false, true);
			will(returnValue(aliceConfirm));

			oneOf(transport).sendAbort(false);

			never(keyAgreementCrypto).deriveConfirmationRecord(sharedSecret,
					alicePayload, bobPayload, alicePubKey, ourKeyPair,
					false, false);
		}});

		protocol.perform();
	}
}