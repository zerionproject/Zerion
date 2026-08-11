package org.zerionproject.core.contact;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.HandshakeManager;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.HybridEncapsulationResult;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordReader.RecordPredicate;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.record.RecordWriter;
import org.zerionproject.core.api.record.RecordWriterFactory;
import org.zerionproject.core.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES;
import static org.zerionproject.core.contact.HandshakeConstants.PROOF_BYTES;
import static org.zerionproject.core.contact.HandshakeConstants.PROTOCOL_MAJOR_VERSION;
import static org.zerionproject.core.contact.HandshakeConstants.PROTOCOL_MINOR_VERSION;
import static org.zerionproject.core.contact.HandshakeConstants.FS_MINOR_VERSION;
import static org.zerionproject.core.api.Bytes.compare;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_BYTES;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_EPHEMERAL_PUBLIC_KEY;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_HYBRID_STATIC_KEY;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_KEM_CIPHERTEXT;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_MINOR_VERSION;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_MODE3_CAPABILITY;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_PROOF_OF_OWNERSHIP;
import static org.zerionproject.core.util.ValidationUtils.checkLength;

@Immutable
@NotNullByDefault
class HandshakeManagerImpl implements HandshakeManager {
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
	private final HandshakeCrypto handshakeCrypto;
	private final CryptoComponent crypto;
	private final PendingContactFactory pendingContactFactory;
	private final RecordReaderFactory recordReaderFactory;
	private final RecordWriterFactory recordWriterFactory;

	@Inject
	HandshakeManagerImpl(TransactionManager db,
			IdentityManager identityManager,
			ContactManager contactManager,
			HandshakeCrypto handshakeCrypto,
			CryptoComponent crypto,
			PendingContactFactory pendingContactFactory,
			RecordReaderFactory recordReaderFactory,
			RecordWriterFactory recordWriterFactory) {
		this.db = db;
		this.identityManager = identityManager;
		this.contactManager = contactManager;
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
		}
		if (ctx.pendingContact.isPostQuantum()) {
			throw new IOException(
					"Post-quantum handshake requested but hybrid keys unavailable");
		}
		throw new IOException(
				"Refusing classical-only handshake — peer must advertise "
						+ "post-quantum capability");
	}

	private HandshakeResult performHybridHandshake(HandshakeContext ctx,
			InputStream in, StreamWriter out) throws IOException {
		byte[] theirCommitment = Arrays.copyOfRange(
				ctx.pendingContact.getPublicKey().getEncoded(), 0,
				HYBRID_COMMITMENT_BYTES);
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
		int theirMinorVersion;
		if (alice) {
			sendMinorVersion(recordWriter);
			sendHybridStaticKey(recordWriter, ourHybridEphemeralKeyPair.getPublic());
			EphemeralExchange ex = receiveHybridEphemeral(recordReader);
			theirHybridEphemeralKey = ex.key;
			theirMinorVersion = ex.minorVersion;
		} else {
			EphemeralExchange ex = receiveHybridEphemeral(recordReader);
			theirHybridEphemeralKey = ex.key;
			theirMinorVersion = ex.minorVersion;
			sendMinorVersion(recordWriter);
			sendHybridStaticKey(recordWriter, ourHybridEphemeralKeyPair.getPublic());
		}

		boolean useFs = theirMinorVersion >= FS_MINOR_VERSION;
		if (!useFs) {
			throw new FormatException();
		}

		byte[] kemCiphertext;
		byte[] kemSecret;
		try {
			if (alice) {
				PublicKey kemTarget = theirHybridEphemeralKey;
				HybridEncapsulationResult encResult =
						handshakeCrypto.hybridEncapsulate(kemTarget);
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
			masterKey = handshakeCrypto.deriveHybridMasterKeyFs(
					theirHybridStaticKey, theirHybridEphemeralKey,
					ourHybridStaticKeyPair, ourHybridEphemeralKeyPair,
					kemCiphertext, kemSecret, alice,
					PROTOCOL_MINOR_VERSION, (byte) theirMinorVersion);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		} finally {
			Arrays.fill(kemSecret, (byte) 0);
			org.zerionproject.core.api.crypto.PrivateKey ephPriv =
					ourHybridEphemeralKeyPair.getPrivate();
			if (ephPriv instanceof
					org.zerionproject.core.api.crypto
							.HybridAgreementPrivateKey) {
				((org.zerionproject.core.api.crypto
						.HybridAgreementPrivateKey) ephPriv).clear();
			}
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
		sendMode3Capability(recordWriter, masterKey);
		out.sendEndOfStream();
		boolean mode3Capable = receiveMode3Capability(recordReader, masterKey);
		recordReader.readRecord(r -> false, IGNORE);
		boolean ownershipOk =
				handshakeCrypto.verifyOwnership(masterKey, !alice, theirProof);
		if (!ownershipOk) {
			throw new FormatException();
		}

		byte[] ourStaticHybridPub =
				ourHybridStaticKeyPair.getPublic().getEncoded();
		byte[] theirStaticHybridPub = theirHybridStaticKey.getEncoded();
		byte[] ourEphFull = ourHybridEphemeralKeyPair.getPublic().getEncoded();
		byte[] theirEphFull = theirHybridEphemeralKey.getEncoded();
		byte[] ourEphX25519 = Arrays.copyOfRange(ourEphFull, 0, 32);
		byte[] theirEphX25519 = Arrays.copyOfRange(theirEphFull, 0, 32);

		return new HandshakeResult(masterKey, alice, mode3Capable,
				ourStaticHybridPub, theirStaticHybridPub,
				ourEphX25519, theirEphX25519);
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

	private EphemeralExchange receiveHybridEphemeral(RecordReader r)
			throws IOException {
		Record first = readRecord(r, asList(RECORD_TYPE_MINOR_VERSION,
				RECORD_TYPE_HYBRID_STATIC_KEY));
		int minorVersion = -1;
		Record keyRecord;
		if (first.getRecordType() == RECORD_TYPE_MINOR_VERSION) {
			byte[] mv = first.getPayload();
			if (mv != null && mv.length == 1) {
				minorVersion = mv[0] & 0xFF;
			}
			keyRecord = readRecord(r,
					singletonList(RECORD_TYPE_HYBRID_STATIC_KEY));
		} else {
			keyRecord = first;
		}
		byte[] key = keyRecord.getPayload();
		checkLength(key, HYBRID_AGREEMENT_PUBLIC_KEY_BYTES,
				HYBRID_AGREEMENT_PUBLIC_KEY_BYTES);
		return new EphemeralExchange(new HybridAgreementPublicKey(key),
				minorVersion);
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

	private static final String MODE3_CAP_MAC_LABEL =
			"org.zerionproject/MODE3_CAPABILITY_MAC";

	private void sendMode3Capability(RecordWriter w, SecretKey masterKey)
			throws IOException {
		byte[] cap = new byte[] {0x01};
		byte[] mac = crypto.mac(MODE3_CAP_MAC_LABEL, masterKey, cap);
		byte[] payload = new byte[cap.length + mac.length];
		System.arraycopy(cap, 0, payload, 0, cap.length);
		System.arraycopy(mac, 0, payload, cap.length, mac.length);
		w.writeRecord(new Record(PROTOCOL_MAJOR_VERSION,
				RECORD_TYPE_MODE3_CAPABILITY, payload));
		w.flush();
	}

	private boolean receiveMode3Capability(RecordReader r, SecretKey masterKey)
			throws IOException {
		RecordPredicate accept = rec ->
				rec.getProtocolVersion() == PROTOCOL_MAJOR_VERSION &&
						rec.getRecordType() == RECORD_TYPE_MODE3_CAPABILITY;
		Record rec = r.readRecord(accept, IGNORE);
		if (rec == null) {
			throw new FormatException();
		}
		byte[] payload = rec.getPayload();
		if (payload == null || payload.length < 1) {
			throw new FormatException();
		}
		byte[] cap = new byte[] {payload[0]};
		byte[] mac = Arrays.copyOfRange(payload, 1, payload.length);
		if (!crypto.verifyMac(mac, MODE3_CAP_MAC_LABEL, masterKey, cap)) {
			throw new FormatException();
		}
		return payload[0] == 0x01;
	}

	private Record readRecord(RecordReader r, List<Byte> expectedTypes)
			throws IOException {
		RecordPredicate accept = rec ->
				rec.getProtocolVersion() == PROTOCOL_MAJOR_VERSION &&
						expectedTypes.contains(rec.getRecordType());
		Record rec = r.readRecord(accept, IGNORE);
		if (rec == null) throw new EOFException();
		return rec;
	}

	private static class EphemeralExchange {
		final PublicKey key;
		final int minorVersion;

		EphemeralExchange(PublicKey key, int minorVersion) {
			this.key = key;
			this.minorVersion = minorVersion;
		}
	}

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
