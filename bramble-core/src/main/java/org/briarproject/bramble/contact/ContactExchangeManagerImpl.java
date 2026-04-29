package org.briarproject.bramble.contact;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactExchangeManager;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.properties.TransportPropertyManager;
import org.briarproject.bramble.api.record.Record;
import org.briarproject.bramble.api.record.RecordReader;
import org.briarproject.bramble.api.record.RecordReader.RecordPredicate;
import org.briarproject.bramble.api.record.RecordReaderFactory;
import org.briarproject.bramble.api.record.RecordWriter;
import org.briarproject.bramble.api.record.RecordWriterFactory;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.transport.StreamReaderFactory;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.bramble.api.transport.StreamWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import static org.briarproject.bramble.api.contact.B3Constants.B3_DEBUG_LOG;
import static org.briarproject.bramble.api.contact.B3Constants.B3_PQ_PUB_LEN;
import static org.briarproject.bramble.api.contact.B3Constants.B3_PROOF_ENABLED;
import static org.briarproject.bramble.api.contact.B3Constants.B3_SIG_LEN;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_SIGNATURE_LENGTH;
import static org.briarproject.bramble.api.system.Clock.MIN_REASONABLE_TIME_MS;
import static org.briarproject.bramble.contact.ContactExchangeConstants.PROTOCOL_VERSION;
import static org.briarproject.bramble.contact.ContactExchangeRecordTypes.CONTACT_INFO;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;

@Immutable
@NotNullByDefault
class ContactExchangeManagerImpl implements ContactExchangeManager {

	// B.3 debug-logging gate. Compile-time-final via B3_DEBUG_LOG; the
	// JIT folds every log call out of the bytecode when the flag is
	// off, so release builds emit nothing. Flip B3_DEBUG_LOG=true in
	// B3Constants to activate during emulator testing, then flip back.
	private static final Logger B3_LOG =
			Logger.getLogger(ContactExchangeManagerImpl.class.getName());

	private static final RecordPredicate ACCEPT = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					isKnownRecordType(r.getRecordType());
	private static final RecordPredicate IGNORE = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					!isKnownRecordType(r.getRecordType());

	private static boolean isKnownRecordType(byte type) {
		return type == CONTACT_INFO;
	}

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final RecordReaderFactory recordReaderFactory;
	private final RecordWriterFactory recordWriterFactory;
	private final Clock clock;
	private final ContactManager contactManager;
	private final IdentityManager identityManager;
	private final TransportPropertyManager transportPropertyManager;
	private final ContactExchangeCrypto contactExchangeCrypto;
	private final StreamReaderFactory streamReaderFactory;
	private final StreamWriterFactory streamWriterFactory;

	@Inject
	ContactExchangeManagerImpl(DatabaseComponent db, ClientHelper clientHelper,
			RecordReaderFactory recordReaderFactory,
			RecordWriterFactory recordWriterFactory, Clock clock,
			ContactManager contactManager, IdentityManager identityManager,
			TransportPropertyManager transportPropertyManager,
			ContactExchangeCrypto contactExchangeCrypto,
			StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.recordReaderFactory = recordReaderFactory;
		this.recordWriterFactory = recordWriterFactory;
		this.clock = clock;
		this.contactManager = contactManager;
		this.identityManager = identityManager;
		this.transportPropertyManager = transportPropertyManager;
		this.contactExchangeCrypto = contactExchangeCrypto;
		this.streamReaderFactory = streamReaderFactory;
		this.streamWriterFactory = streamWriterFactory;
	}

	@Override
	public Contact exchangeContacts(DuplexTransportConnection conn,
			SecretKey masterKey, boolean alice,
			boolean verified) throws IOException, DbException {
		return exchange(null, conn, masterKey, alice, verified, false, false,
				null, null, null, null);
	}

	@Override
	public Contact exchangeContacts(PendingContactId p,
			DuplexTransportConnection conn, SecretKey masterKey, boolean alice,
			boolean verified, boolean classical) throws IOException, DbException {
		return exchange(p, conn, masterKey, alice, verified, classical, false,
				null, null, null, null);
	}

	@Override
	public Contact exchangeContacts(PendingContactId p,
			DuplexTransportConnection conn, SecretKey masterKey, boolean alice,
			boolean verified, boolean classical, boolean mode3Capable)
			throws IOException, DbException {
		return exchange(p, conn, masterKey, alice, verified, classical,
				mode3Capable, null, null, null, null);
	}

	@Override
	public Contact exchangeContacts(PendingContactId p,
			DuplexTransportConnection conn, SecretKey masterKey, boolean alice,
			boolean verified, boolean classical, boolean mode3Capable,
			@Nullable byte[] ourStaticHybridPub,
			@Nullable byte[] theirStaticHybridPub,
			@Nullable byte[] ourEphX25519,
			@Nullable byte[] theirEphX25519)
			throws IOException, DbException {
		return exchange(p, conn, masterKey, alice, verified, classical,
				mode3Capable,
				ourStaticHybridPub, theirStaticHybridPub,
				ourEphX25519, theirEphX25519);
	}

	private Contact exchange(@Nullable PendingContactId p,
			DuplexTransportConnection conn, SecretKey masterKey, boolean alice,
			boolean verified, boolean classical, boolean mode3Capable,
			@Nullable byte[] ourStaticHybridPub,
			@Nullable byte[] theirStaticHybridPub,
			@Nullable byte[] ourEphX25519,
			@Nullable byte[] theirEphX25519)
			throws IOException, DbException {
		InputStream in = conn.getReader().getInputStream();
		OutputStream out = conn.getWriter().getOutputStream();
		LocalAuthor localAuthor = identityManager.getLocalAuthor();
		Map<TransportId, TransportProperties> localProperties =
				transportPropertyManager.getLocalProperties();
		SecretKey localHeaderKey =
				contactExchangeCrypto.deriveHeaderKey(masterKey, alice);
		SecretKey remoteHeaderKey =
				contactExchangeCrypto.deriveHeaderKey(masterKey, !alice);
		InputStream streamReader = streamReaderFactory
				.createContactExchangeStreamReader(in, remoteHeaderKey);
		RecordReader recordReader =
				recordReaderFactory.createRecordReader(streamReader, classical);
		StreamWriter streamWriter = streamWriterFactory
				.createContactExchangeStreamWriter(out, localHeaderKey);
		RecordWriter recordWriter = recordWriterFactory
				.createRecordWriter(streamWriter.getOutputStream(), classical);
		byte[] localSignature = contactExchangeCrypto
				.sign(localAuthor.getPrivateKey(), masterKey, alice);
		long localTimestamp = clock.currentTimeMillis();

		// B.3: when the gate is on and the orchestrator handed us all the
		// hybrid handshake state, sign over our static ML-KEM-768 pubkey
		// for slot[4]. Skip silently if any input is null — that's the
		// legacy / non-hybrid path and slot[4] doesn't apply.
		byte[] localB3ProofSig = null;
		if (B3_PROOF_ENABLED
				&& ourStaticHybridPub != null
				&& ourEphX25519 != null
				&& theirEphX25519 != null) {
			byte[] ourStaticPqPub = java.util.Arrays.copyOfRange(
					ourStaticHybridPub, 32, 32 + B3_PQ_PUB_LEN);
			localB3ProofSig = B3PqProof.sign(
					localAuthor.getPrivateKey().getEncoded(),
					ourEphX25519, theirEphX25519, ourStaticPqPub);
			if (B3_DEBUG_LOG) {
				B3_LOG.info(String.format(
						"[B3] encode: side=%s gate=ON state=present "
								+ "sigLen=%d pqPubLen=%d",
						alice ? "alice" : "bob",
						localB3ProofSig.length, ourStaticPqPub.length));
			}
		} else if (B3_DEBUG_LOG) {
			B3_LOG.info(String.format(
					"[B3] encode: side=%s gate=%s state=%s "
							+ "-> writing 4-slot legacy",
					alice ? "alice" : "bob",
					B3_PROOF_ENABLED ? "ON" : "OFF",
					(ourStaticHybridPub != null && ourEphX25519 != null
							&& theirEphX25519 != null)
							? "present" : "missing"));
		}

		ContactInfo remoteInfo;
		if (alice) {
			sendContactInfo(recordWriter, localAuthor, localProperties,
					localSignature, localTimestamp, localB3ProofSig);
			remoteInfo = receiveContactInfo(recordReader);
		} else {
			remoteInfo = receiveContactInfo(recordReader);
			sendContactInfo(recordWriter, localAuthor, localProperties,
					localSignature, localTimestamp, localB3ProofSig);
		}
		streamWriter.sendEndOfStream();
		recordReader.readRecord(r -> false, IGNORE);
		PublicKey remotePublicKey = remoteInfo.author.getPublicKey();
		if (!contactExchangeCrypto.verify(remotePublicKey,
				masterKey, !alice, remoteInfo.signature)) {
			throw new FormatException();
		}

		// B.3: if the peer sent a slot[4] proof, verify it. We require
		// the buffered handshake state to be present — receiving a proof
		// without buffered state would mean the orchestrator failed to
		// thread through the hybrid handshake context, which is a bug,
		// not graceful degradation. Hard-reject in that case.
		//
		// v1.5.1 strict-reject hook: when the messaging.minorVersion
		// sync record from a peer advertising v5 arrives post-
		// handshake, we'll need to confirm slot[4] was present here.
		// Two ways to resolve in v1.5.1:
		//   (a) persist a per-contact flag here;
		//   (b) derive — any contact in our DB advertising v5 MUST
		//       have passed verify, since this method throws on
		//       verify-fail and the contact wouldn't have been
		//       promoted.
		// iOS persists for forward-compat. Either is fine; we'll pick
		// at v1.5.1 implementation time.
		if (remoteInfo.b3ProofSig != null) {
			if (theirStaticHybridPub == null
					|| ourEphX25519 == null
					|| theirEphX25519 == null) {
				if (B3_DEBUG_LOG) {
					B3_LOG.info(String.format(
							"[B3] verify: side=%s FAILED "
									+ "(buffered handshake state missing)",
							alice ? "alice" : "bob"));
				}
				throw new FormatException();
			}
			byte[] theirStaticPqPub = java.util.Arrays.copyOfRange(
					theirStaticHybridPub, 32, 32 + B3_PQ_PUB_LEN);
			byte[] remoteSigningPubBytes = remotePublicKey.getEncoded();
			boolean ok = B3PqProof.verify(remoteSigningPubBytes,
					theirEphX25519, ourEphX25519,
					theirStaticPqPub, remoteInfo.b3ProofSig);
			if (B3_DEBUG_LOG) {
				B3_LOG.info(String.format(
						"[B3] verify: side=%s result=%s",
						alice ? "alice" : "bob",
						ok ? "PASSED" : "FAILED"));
			}
			if (!ok) throw new FormatException();
		} else if (B3_DEBUG_LOG) {
			B3_LOG.info(String.format(
					"[B3] verify: side=%s skipped (no slot[4])",
					alice ? "alice" : "bob"));
		}
		long timestamp = Math.min(localTimestamp, remoteInfo.timestamp);
		if (timestamp < MIN_REASONABLE_TIME_MS) {
			throw new FormatException();
		}
		Contact contact = addContact(p, remoteInfo.author, localAuthor,
				masterKey, timestamp, alice, verified, remoteInfo.properties,
				mode3Capable);

		return contact;
	}

	private void sendContactInfo(RecordWriter recordWriter, Author author,
			Map<TransportId, TransportProperties> properties, byte[] signature,
			long timestamp, @Nullable byte[] b3ProofSig) throws IOException {
		BdfList authorList = clientHelper.toList(author);
		BdfDictionary props = clientHelper.toDictionary(properties);
		// 4-slot legacy layout when no proof; 5-slot v1.5 layout with
		// proof at slot[4] when caller has computed one. Wire-byte-
		// identical with v1.4 in the legacy case (BDF list grows by
		// exactly one slot).
		BdfList payload = b3ProofSig == null
				? BdfList.of(authorList, props, signature, timestamp)
				: BdfList.of(authorList, props, signature, timestamp,
						b3ProofSig);
		recordWriter.writeRecord(new Record(PROTOCOL_VERSION, CONTACT_INFO,
				clientHelper.toByteArray(payload)));
		recordWriter.flush();
	}

	private ContactInfo receiveContactInfo(RecordReader recordReader)
			throws IOException {
		Record record = recordReader.readRecord(ACCEPT, IGNORE);
		if (record == null) throw new EOFException();
		BdfList payload = clientHelper.toList(record.getPayload());
		// Accept either 4 (legacy v1.4) or 5 (v1.5 with B.3 proof) slots.
		// Tolerate trailing slot via BDF's end-marker form on the reader
		// side; the new slot[4] is only consumed when the list has
		// length 5 and the caller has buffered handshake state to
		// verify against.
		int size = payload.size();
		if (size != 4 && size != 5) throw new FormatException();
		Author author = clientHelper.parseAndValidateAuthor(payload.getList(0));
		BdfDictionary props = payload.getDictionary(1);
		Map<TransportId, TransportProperties> properties =
				clientHelper.parseAndValidateTransportPropertiesMap(props);
		byte[] signature = payload.getRaw(2);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);
		long timestamp = payload.getLong(3);
		if (timestamp < 0) throw new FormatException();
		byte[] b3ProofSig = null;
		if (size == 5) {
			b3ProofSig = payload.getRaw(4);
			// Tight length check — Ed25519 sigs are exactly 64 bytes.
			// A wrong-length slot[4] is malformed and rejected, not
			// silently coerced.
			checkLength(b3ProofSig, B3_SIG_LEN, B3_SIG_LEN);
		}
		if (B3_DEBUG_LOG) {
			B3_LOG.info(String.format("[B3] decode: slots=%d sigLen=%s",
					size,
					b3ProofSig == null ? "absent"
							: String.valueOf(b3ProofSig.length)));
		}
		return new ContactInfo(author, properties, signature, timestamp,
				b3ProofSig);
	}

	private Contact addContact(@Nullable PendingContactId pendingContactId,
			Author remoteAuthor, LocalAuthor localAuthor, SecretKey masterKey,
			long timestamp, boolean alice, boolean verified,
			Map<TransportId, TransportProperties> remoteProperties,
			boolean mode3Capable)
			throws DbException, FormatException {
		Transaction txn = db.startTransaction(false);
		try {
			ContactId contactId;
			if (pendingContactId == null) {
				contactId = contactManager.addContact(txn, remoteAuthor,
						localAuthor.getId(), masterKey, timestamp, alice,
						verified, true, mode3Capable);
			} else {
				contactId = contactManager.addContact(txn, pendingContactId,
						remoteAuthor, localAuthor.getId(), masterKey,
						timestamp, alice, verified, true, mode3Capable);
			}
			transportPropertyManager.addRemoteProperties(txn, contactId,
					remoteProperties);
			Contact contact = contactManager.getContact(txn, contactId);
			db.commitTransaction(txn);
			return contact;
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		} finally {
			db.endTransaction(txn);
		}
	}

	private static class ContactInfo {

		private final Author author;
		private final Map<TransportId, TransportProperties> properties;
		private final byte[] signature;
		private final long timestamp;
		/** B.3 slot[4] proof sig, or null on legacy 4-slot records. */
		@Nullable
		private final byte[] b3ProofSig;

		private ContactInfo(Author author,
				Map<TransportId, TransportProperties> properties,
				byte[] signature, long timestamp,
				@Nullable byte[] b3ProofSig) {
			this.author = author;
			this.properties = properties;
			this.signature = signature;
			this.timestamp = timestamp;
			this.b3ProofSig = b3ProofSig;
		}
	}
}
