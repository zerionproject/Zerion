package org.briarproject.bramble.contact;

import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.contact.HandshakeManager;
import org.briarproject.bramble.api.contact.PendingContact;
import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.crypto.AgreementPublicKey;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.HybridAgreementPublicKey;
import org.briarproject.bramble.api.crypto.HybridEncapsulationResult;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.TransportCrypto;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.record.Record;
import org.briarproject.bramble.api.record.RecordReader;
import org.briarproject.bramble.api.record.RecordReader.RecordPredicate;
import org.briarproject.bramble.api.record.RecordReaderFactory;
import org.briarproject.bramble.api.record.RecordWriter;
import org.briarproject.bramble.api.record.RecordWriterFactory;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.List;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.briarproject.bramble.api.crypto.CryptoConstants.MAX_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES;
import static org.briarproject.bramble.contact.HandshakeConstants.PROOF_BYTES;
import static org.briarproject.bramble.contact.HandshakeConstants.PROTOCOL_MAJOR_VERSION;
import static org.briarproject.bramble.contact.HandshakeConstants.PROTOCOL_MINOR_VERSION;
import static org.briarproject.bramble.api.Bytes.compare;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_ENABLED;
import static org.briarproject.bramble.contact.HandshakeRecordTypes.RECORD_TYPE_EPHEMERAL_PUBLIC_KEY;
import static org.briarproject.bramble.contact.HandshakeRecordTypes.RECORD_TYPE_HYBRID_STATIC_KEY;
import static org.briarproject.bramble.contact.HandshakeRecordTypes.RECORD_TYPE_KEM_CIPHERTEXT;
import static org.briarproject.bramble.contact.HandshakeRecordTypes.RECORD_TYPE_MINOR_VERSION;
import static org.briarproject.bramble.contact.HandshakeRecordTypes.RECORD_TYPE_MODE3_CAPABILITY;
import static org.briarproject.bramble.contact.HandshakeRecordTypes.RECORD_TYPE_PROOF_OF_OWNERSHIP;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;

/**
 * Handshake manager that supports both classical and post-quantum key exchange.
 * <p>
 * The handshake protocol selects keys based on the pending contact's format version:
 * <ul>
 *   <li>Version 0 (classical): Uses X25519 keys (Briar-compatible)</li>
 *   <li>Version 1 (hybrid): Uses X25519 + ML-KEM-768 keys (PQ-secure)</li>
 * </ul>
 */
@Immutable
@NotNullByDefault
class HandshakeManagerImpl implements HandshakeManager {

	private static final java.util.logging.Logger LOG =
			getLogger(HandshakeManagerImpl.class.getName());

	// Ignore records with current protocol version, unknown record type
	private static final RecordPredicate IGNORE = r ->
			r.getProtocolVersion() == PROTOCOL_MAJOR_VERSION &&
					!isKnownRecordType(r.getRecordType());

	private static boolean isKnownRecordType(byte type) {
		return type == RECORD_TYPE_EPHEMERAL_PUBLIC_KEY ||
				type == RECORD_TYPE_PROOF_OF_OWNERSHIP ||
				type == RECORD_TYPE_MINOR_VERSION ||
				type == RECORD_TYPE_HYBRID_STATIC_KEY ||
				type == RECORD_TYPE_KEM_CIPHERTEXT ||
				type == RECORD_TYPE_MODE3_CAPABILITY;
	}

	private final TransactionManager db;
	private final IdentityManager identityManager;
	private final ContactManager contactManager;
	private final TransportCrypto transportCrypto;
	private final HandshakeCrypto handshakeCrypto;
	private final CryptoComponent crypto;
	private final PendingContactFactory pendingContactFactory;
	private final RecordReaderFactory recordReaderFactory;
	private final RecordWriterFactory recordWriterFactory;

	@Inject
	HandshakeManagerImpl(TransactionManager db,
			IdentityManager identityManager,
			ContactManager contactManager,
			TransportCrypto transportCrypto,
			HandshakeCrypto handshakeCrypto,
			CryptoComponent crypto,
			PendingContactFactory pendingContactFactory,
			RecordReaderFactory recordReaderFactory,
			RecordWriterFactory recordWriterFactory) {
		this.db = db;
		this.identityManager = identityManager;
		this.contactManager = contactManager;
		this.transportCrypto = transportCrypto;
		this.handshakeCrypto = handshakeCrypto;
		this.crypto = crypto;
		this.pendingContactFactory = pendingContactFactory;
		this.recordReaderFactory = recordReaderFactory;
		this.recordWriterFactory = recordWriterFactory;
	}

	@Override
	public HandshakeResult handshake(PendingContactId p, InputStream in,
			StreamWriter out) throws DbException, IOException {
		HandshakeContext ctx = db.transactionWithResult(true, txn -> {
			PendingContact pendingContact =
					contactManager.getPendingContact(txn, p);
			KeyPair keyPair;
			KeyPair hybridKeyPair = null;

			if (pendingContact.isPostQuantum()) {
				hybridKeyPair = identityManager.getHybridHandshakeKeys(txn);
				if (hybridKeyPair == null) {
					keyPair = identityManager.getHandshakeKeys(txn);
				} else {
					keyPair = hybridKeyPair;
				}
			} else {
				keyPair = identityManager.getHandshakeKeys(txn);
			}

			return new HandshakeContext(pendingContact, keyPair, hybridKeyPair);
		});

		boolean isHybrid = ctx.pendingContact.isPostQuantum() &&
				ctx.hybridKeyPair != null;

		if (isHybrid) {
			return performHybridHandshake(ctx, in, out);
		} else {
			return performClassicalHandshake(ctx, in, out);
		}
	}

	private HandshakeResult performClassicalHandshake(HandshakeContext ctx,
			InputStream in, StreamWriter out) throws IOException {
		PublicKey theirStaticPublicKey = ctx.pendingContact.getPublicKey();
		KeyPair ourStaticKeyPair = ctx.keyPair;
		boolean alice = transportCrypto.isAlice(theirStaticPublicKey,
				ourStaticKeyPair);
		RecordReader recordReader = recordReaderFactory.createRecordReader(in, true);
		RecordWriter recordWriter = recordWriterFactory
				.createRecordWriter(out.getOutputStream(), true);
		KeyPair ourEphemeralKeyPair =
				handshakeCrypto.generateEphemeralKeyPair();
		Pair<Byte, PublicKey> theirMinorVersionAndKey;
		if (alice) {
			sendMinorVersion(recordWriter);
			sendPublicKey(recordWriter, ourEphemeralKeyPair.getPublic());
			theirMinorVersionAndKey = receiveMinorVersionAndKey(recordReader);
		} else {
			theirMinorVersionAndKey = receiveMinorVersionAndKey(recordReader);
			sendMinorVersion(recordWriter);
			sendPublicKey(recordWriter, ourEphemeralKeyPair.getPublic());
		}
		byte theirMinorVersion = theirMinorVersionAndKey.getFirst();
		PublicKey theirEphemeralPublicKey = theirMinorVersionAndKey.getSecond();
		SecretKey masterKey;
		try {
			if (theirMinorVersion > 0) {
				masterKey = handshakeCrypto.deriveMasterKey_0_1(
						theirStaticPublicKey, theirEphemeralPublicKey,
						ourStaticKeyPair, ourEphemeralKeyPair, alice);
			} else {
				masterKey = handshakeCrypto.deriveMasterKey_0_0(
						theirStaticPublicKey, theirEphemeralPublicKey,
						ourStaticKeyPair, ourEphemeralKeyPair, alice);
			}
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
		byte[] ourProof = handshakeCrypto.proveOwnership(masterKey, alice);
		byte[] theirProof;
		if (alice) {
			sendProof(recordWriter, ourProof);
			theirProof = receiveProof(recordReader);
		} else {
			theirProof = receiveProof(recordReader);
			sendProof(recordWriter, ourProof);
		}
		out.sendEndOfStream();
		recordReader.readRecord(r -> false, IGNORE);
		if (!handshakeCrypto.verifyOwnership(masterKey, !alice, theirProof))
			throw new FormatException();
		return new HandshakeResult(masterKey, alice);
	}

	private HandshakeResult performHybridHandshake(HandshakeContext ctx,
			InputStream in, StreamWriter out) throws IOException {
		byte[] theirCommitment = ctx.pendingContact.getPublicKey().getEncoded();
		KeyPair ourHybridStaticKeyPair = ctx.hybridKeyPair;

		RecordReader recordReader = recordReaderFactory.createRecordReader(in, false);
		RecordWriter recordWriter = recordWriterFactory
				.createRecordWriter(out.getOutputStream(), false);

		byte[] ourCommitment = crypto.hash(HYBRID_COMMITMENT_LABEL,
				ourHybridStaticKeyPair.getPublic().getEncoded());
		boolean alice = compare(ourCommitment, theirCommitment) < 0;

		PublicKey theirHybridStaticKey;
		if (alice) {
			sendHybridStaticKey(recordWriter, ourHybridStaticKeyPair.getPublic());
			theirHybridStaticKey = receiveHybridStaticKey(recordReader);
		} else {
			theirHybridStaticKey = receiveHybridStaticKey(recordReader);
			sendHybridStaticKey(recordWriter, ourHybridStaticKeyPair.getPublic());
		}

		if (!pendingContactFactory.verifyHybridKeyCommitment(
				theirHybridStaticKey, theirCommitment)) {
			throw new FormatException();
		}

		KeyPair ourHybridEphemeralKeyPair =
				handshakeCrypto.generateHybridEphemeralKeyPair();

		PublicKey theirHybridEphemeralKey;
		if (alice) {
			sendMinorVersion(recordWriter);
			sendHybridStaticKey(recordWriter, ourHybridEphemeralKeyPair.getPublic());
			theirHybridEphemeralKey = receiveHybridEphemeralKey(recordReader);
		} else {
			theirHybridEphemeralKey = receiveHybridEphemeralKey(recordReader);
			sendMinorVersion(recordWriter);
			sendHybridStaticKey(recordWriter, ourHybridEphemeralKeyPair.getPublic());
		}

		byte[] kemCiphertext;
		byte[] kemSecret;
		try {
			if (alice) {
				HybridEncapsulationResult encResult =
						handshakeCrypto.hybridEncapsulate(theirHybridStaticKey);
				kemCiphertext = encResult.getCiphertext();
				kemSecret = encResult.getSharedSecret();
				sendKemCiphertext(recordWriter, kemCiphertext);
			} else {
				kemCiphertext = receiveKemCiphertext(recordReader);
				kemSecret = new byte[0];
			}
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}

		SecretKey masterKey;
		try {
			masterKey = handshakeCrypto.deriveHybridMasterKey(
					theirHybridStaticKey, theirHybridEphemeralKey,
					ourHybridStaticKeyPair, ourHybridEphemeralKeyPair,
					kemCiphertext, kemSecret, alice);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}

		byte[] ourProof = handshakeCrypto.proveOwnership(masterKey, alice);
		byte[] theirProof;
		if (alice) {
			sendProof(recordWriter, ourProof);
			theirProof = receiveProof(recordReader);
		} else {
			theirProof = receiveProof(recordReader);
			sendProof(recordWriter, ourProof);
		}

		// Send Mode3Capability before EOF (new peers will read it during drain)
		boolean mode3Capable = false;
		if (MODE3_ENABLED) {
			sendMode3Capability(recordWriter);
		}

		// Match old Zerion ordering: sendEOF → drain → verify
		// This ensures backward compatibility with older builds
		out.sendEndOfStream();

		// Drain remaining records from peer (reads Mode3Capability if present)
		if (MODE3_ENABLED) {
			mode3Capable = receiveMode3Capability(recordReader);
		}
		recordReader.readRecord(r -> false, IGNORE);

		// Verify proof AFTER drain (matches old Zerion ordering)
		if (!handshakeCrypto.verifyOwnership(masterKey, !alice, theirProof)) {
			throw new FormatException();
		}

		return new HandshakeResult(masterKey, alice, mode3Capable);
	}

	private void sendHybridStaticKey(RecordWriter w, PublicKey k)
			throws IOException {
		w.writeRecord(new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_HYBRID_STATIC_KEY, k.getEncoded()));
		w.flush();
	}

	private PublicKey receiveHybridStaticKey(RecordReader r) throws IOException {
		Record rec = readRecord(r,
				singletonList(RECORD_TYPE_HYBRID_STATIC_KEY));
		byte[] key = rec.getPayload();
		checkLength(key, HYBRID_AGREEMENT_PUBLIC_KEY_BYTES,
				HYBRID_AGREEMENT_PUBLIC_KEY_BYTES);
		return new HybridAgreementPublicKey(key);
	}

	private PublicKey receiveHybridEphemeralKey(RecordReader r)
			throws IOException {
		// First should be minor version, then ephemeral key (using HYBRID_STATIC_KEY type)
		Record first = readRecord(r, asList(RECORD_TYPE_MINOR_VERSION,
				RECORD_TYPE_HYBRID_STATIC_KEY));
		if (first.getRecordType() == RECORD_TYPE_MINOR_VERSION) {
			Record second = readRecord(r,
					singletonList(RECORD_TYPE_HYBRID_STATIC_KEY));
			byte[] key = second.getPayload();
			checkLength(key, HYBRID_AGREEMENT_PUBLIC_KEY_BYTES,
					HYBRID_AGREEMENT_PUBLIC_KEY_BYTES);
			return new HybridAgreementPublicKey(key);
		} else {
			// They didn't send minor version (older protocol)
			byte[] key = first.getPayload();
			checkLength(key, HYBRID_AGREEMENT_PUBLIC_KEY_BYTES,
					HYBRID_AGREEMENT_PUBLIC_KEY_BYTES);
			return new HybridAgreementPublicKey(key);
		}
	}

	private void sendKemCiphertext(RecordWriter w, byte[] ciphertext)
			throws IOException {
		w.writeRecord(new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_KEM_CIPHERTEXT, ciphertext));
		w.flush();
	}

	private byte[] receiveKemCiphertext(RecordReader r) throws IOException {
		Record rec = readRecord(r, singletonList(RECORD_TYPE_KEM_CIPHERTEXT));
		byte[] ciphertext = rec.getPayload();
		checkLength(ciphertext, ML_KEM_768_CIPHERTEXT_BYTES,
				ML_KEM_768_CIPHERTEXT_BYTES);
		return ciphertext;
	}

	private void sendPublicKey(RecordWriter w, PublicKey k) throws IOException {
		w.writeRecord(new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_EPHEMERAL_PUBLIC_KEY, k.getEncoded()));
		w.flush();
	}

	/**
	 * Receives the remote peer's protocol minor version and ephemeral public
	 * key.
	 * <p>
	 * In version 0.1 of the protocol, each peer sends a minor version record
	 * followed by an ephemeral public key record.
	 * <p>
	 * In version 0.0 of the protocol, each peer sends an ephemeral public key
	 * record without a preceding minor version record.
	 * <p>
	 * Therefore the remote peer's minor version must be non-zero if a minor
	 * version record is received, and is assumed to be zero if no minor
	 * version record is received.
	 */
	private Pair<Byte, PublicKey> receiveMinorVersionAndKey(RecordReader r)
			throws IOException {
		byte theirMinorVersion;
		PublicKey theirEphemeralPublicKey;
		// The first record can be either a minor version record or an
		// ephemeral public key record
		Record first = readRecord(r, asList(RECORD_TYPE_MINOR_VERSION,
				RECORD_TYPE_EPHEMERAL_PUBLIC_KEY));
		if (first.getRecordType() == RECORD_TYPE_MINOR_VERSION) {
			// The payload must be a single byte giving the remote peer's
			// protocol minor version, which must be non-zero
			byte[] payload = first.getPayload();
			checkLength(payload, 1);
			theirMinorVersion = payload[0];
			if (theirMinorVersion == 0) throw new FormatException();
			// The second record must be an ephemeral public key record
			Record second = readRecord(r,
					singletonList(RECORD_TYPE_EPHEMERAL_PUBLIC_KEY));
			theirEphemeralPublicKey = parsePublicKey(second);
		} else {
			// The remote peer did not send a minor version record, so the
			// remote peer's protocol minor version is assumed to be zero
			// TODO: Remove this branch after a reasonable migration period
			//  (added 2023-03-10).
			theirMinorVersion = 0;
			theirEphemeralPublicKey = parsePublicKey(first);
		}
		return new Pair<>(theirMinorVersion, theirEphemeralPublicKey);
	}

	private PublicKey parsePublicKey(Record rec) throws IOException {
		if (rec.getRecordType() != RECORD_TYPE_EPHEMERAL_PUBLIC_KEY) {
			throw new AssertionError();
		}
		byte[] key = rec.getPayload();
		checkLength(key, 1, MAX_AGREEMENT_PUBLIC_KEY_BYTES);
		return new AgreementPublicKey(key);
	}

	private void sendProof(RecordWriter w, byte[] proof) throws IOException {
		w.writeRecord(new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_PROOF_OF_OWNERSHIP, proof));
		w.flush();
	}

	private byte[] receiveProof(RecordReader r) throws IOException {
		Record rec = readRecord(r,
				singletonList(RECORD_TYPE_PROOF_OF_OWNERSHIP));
		byte[] proof = rec.getPayload();
		checkLength(proof, PROOF_BYTES, PROOF_BYTES);
		return proof;
	}

	private void sendMinorVersion(RecordWriter w) throws IOException {
		w.writeRecord(new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MINOR_VERSION,
				new byte[] {PROTOCOL_MINOR_VERSION}));
		w.flush();
	}

	private void sendMode3Capability(RecordWriter w) throws IOException {
		w.writeRecord(new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY,
				new byte[] {0x01}));
		w.flush();
	}

	private boolean receiveMode3Capability(RecordReader r) throws IOException {
		RecordPredicate accept = rec ->
				rec.getProtocolVersion() == PROTOCOL_MAJOR_VERSION &&
						rec.getRecordType() == RECORD_TYPE_MODE3_CAPABILITY;
		Record rec = r.readRecord(accept, IGNORE);
		if (rec == null) {
			return false;
		}
		byte[] payload = rec.getPayload();
		return payload != null && payload.length == 1 && payload[0] == 0x01;
	}

	private Record readRecord(RecordReader r, List<Byte> expectedTypes)
			throws IOException {
		// Accept records with current protocol version, expected types only
		RecordPredicate accept = rec ->
				rec.getProtocolVersion() == PROTOCOL_MAJOR_VERSION &&
						expectedTypes.contains(rec.getRecordType());
		Record rec = r.readRecord(accept, IGNORE);
		if (rec == null) throw new EOFException();
		return rec;
	}

	/**
	 * Helper class to hold handshake context data.
	 */
	private static class HandshakeContext {
		final PendingContact pendingContact;
		final KeyPair keyPair;
		final KeyPair hybridKeyPair;

		HandshakeContext(PendingContact pendingContact, KeyPair keyPair,
				KeyPair hybridKeyPair) {
			this.pendingContact = pendingContact;
			this.keyPair = keyPair;
			this.hybridKeyPair = hybridKeyPair;
		}
	}
}
