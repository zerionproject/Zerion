package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactExchangeManager;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.contact.event.PendingContactAlreadyContactEvent;
import org.zerionproject.core.api.db.ContactExistsException;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordReader.RecordPredicate;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.record.RecordWriter;
import org.zerionproject.core.api.record.RecordWriterFactory;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.transport.StreamReaderFactory;
import org.zerionproject.core.api.transport.StreamWriter;
import org.zerionproject.core.api.transport.StreamWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import static org.zerionproject.core.api.contact.B3Constants.B3_PQ_PUB_LEN;
import static org.zerionproject.core.api.contact.B3Constants.B3_PROOF_ENABLED;
import static org.zerionproject.core.api.contact.B3Constants.B3_SETTINGS_NAMESPACE;
import static org.zerionproject.core.api.contact.B3Constants.B3_SIG_LEN;
import static org.zerionproject.core.api.contact.B3Constants.B3_SLOT_PRESENT_KEY_PREFIX;
import static org.zerionproject.core.api.identity.AuthorConstants.MAX_SIGNATURE_LENGTH;
import static org.zerionproject.core.api.system.Clock.MIN_REASONABLE_TIME_MS;
import static org.zerionproject.core.contact.ContactExchangeConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.contact.ContactExchangeRecordTypes.CONTACT_INFO;
import static org.zerionproject.core.util.ValidationUtils.checkLength;

@Immutable
@NotNullByDefault
class ContactExchangeManagerImpl implements ContactExchangeManager {

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
	private final SettingsManager settingsManager;

	@Inject
	ContactExchangeManagerImpl(DatabaseComponent db, ClientHelper clientHelper,
			RecordReaderFactory recordReaderFactory,
			RecordWriterFactory recordWriterFactory, Clock clock,
			ContactManager contactManager, IdentityManager identityManager,
			TransportPropertyManager transportPropertyManager,
			ContactExchangeCrypto contactExchangeCrypto,
			StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory,
			SettingsManager settingsManager) {
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
		this.settingsManager = settingsManager;
	}

	@Override
	public Contact exchangeContacts(DuplexTransportConnection conn,
			SecretKey masterKey, boolean alice,
			boolean verified) throws IOException, DbException {
		return exchange(null, conn, masterKey, alice, verified, false,
				null, null, null, null);
	}

	@Override
	public Contact exchangeContacts(PendingContactId p,
			DuplexTransportConnection conn, SecretKey masterKey, boolean alice,
			boolean verified, boolean classical) throws IOException, DbException {
		return exchange(p, conn, masterKey, alice, verified, classical,
				null, null, null, null);
	}

	@Override
	public Contact exchangeContacts(PendingContactId p,
			DuplexTransportConnection conn, SecretKey masterKey, boolean alice,
			boolean verified, boolean classical,
			@Nullable byte[] ourStaticHybridPub,
			@Nullable byte[] theirStaticHybridPub,
			@Nullable byte[] ourEphX25519,
			@Nullable byte[] theirEphX25519)
			throws IOException, DbException {
		return exchange(p, conn, masterKey, alice, verified, classical,
				ourStaticHybridPub, theirStaticHybridPub,
				ourEphX25519, theirEphX25519);
	}

	private Contact exchange(@Nullable PendingContactId p,
			DuplexTransportConnection conn, SecretKey masterKey, boolean alice,
			boolean verified, boolean classical,
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
		}

		byte[] localMlDsaSigPub = identityManager.getLocalMlDsaSigPublicKey();
		ContactInfo remoteInfo;
		if (alice) {
			sendContactInfo(recordWriter, localAuthor, localProperties,
					localSignature, localTimestamp, localB3ProofSig,
					localMlDsaSigPub);
			remoteInfo = receiveContactInfo(recordReader);
		} else {
			remoteInfo = receiveContactInfo(recordReader);
			sendContactInfo(recordWriter, localAuthor, localProperties,
					localSignature, localTimestamp, localB3ProofSig,
					localMlDsaSigPub);
		}
		streamWriter.sendEndOfStream();
		recordReader.readRecord(r -> false, IGNORE);
		PublicKey remotePublicKey = remoteInfo.author.getPublicKey();
		boolean sigOk = contactExchangeCrypto.verify(remotePublicKey,
				masterKey, !alice, remoteInfo.signature);
		if (!sigOk) {
			throw new FormatException();
		}

		boolean hybridExchange = B3_PROOF_ENABLED
				&& theirStaticHybridPub != null
				&& ourEphX25519 != null
				&& theirEphX25519 != null;
		if (hybridExchange && remoteInfo.b3ProofSig == null) {
			throw new FormatException();
		}
		if (remoteInfo.b3ProofSig != null) {
			if (theirStaticHybridPub == null
					|| ourEphX25519 == null
					|| theirEphX25519 == null) {
				throw new FormatException();
			}
			byte[] theirStaticPqPub = java.util.Arrays.copyOfRange(
					theirStaticHybridPub, 32, 32 + B3_PQ_PUB_LEN);
			byte[] remoteSigningPubBytes = remotePublicKey.getEncoded();
			boolean ok = B3PqProof.verify(remoteSigningPubBytes,
					theirEphX25519, ourEphX25519,
					theirStaticPqPub, remoteInfo.b3ProofSig);
			if (!ok) throw new FormatException();
		}
		long timestamp = Math.min(localTimestamp, remoteInfo.timestamp);
		if (timestamp < MIN_REASONABLE_TIME_MS) {
			throw new FormatException();
		}
		Contact contact = addContact(p, remoteInfo.author, localAuthor,
				masterKey, timestamp, alice, verified, remoteInfo.properties,
				remoteInfo.b3ProofSig != null,
				remoteInfo.mlDsaSigPubKey);

		return contact;
	}

	private void sendContactInfo(RecordWriter recordWriter, Author author,
			Map<TransportId, TransportProperties> properties, byte[] signature,
			long timestamp, @Nullable byte[] b3ProofSig,
			@Nullable byte[] mlDsaSigPub) throws IOException {
		BdfList authorList = clientHelper.toList(author);
		BdfDictionary props = clientHelper.toDictionary(properties);
		BdfList payload;
		if (mlDsaSigPub != null) {
			byte[] safeB3 = b3ProofSig != null ? b3ProofSig : new byte[0];
			payload = BdfList.of(authorList, props, signature, timestamp,
					safeB3, mlDsaSigPub);
		} else if (b3ProofSig != null) {
			payload = BdfList.of(authorList, props, signature, timestamp,
					b3ProofSig);
		} else {
			payload = BdfList.of(authorList, props, signature, timestamp);
		}
		recordWriter.writeRecord(new Record(PROTOCOL_VERSION, CONTACT_INFO,
				clientHelper.toByteArray(payload)));
		recordWriter.flush();
	}

	private ContactInfo receiveContactInfo(RecordReader recordReader)
			throws IOException {
		Record record = recordReader.readRecord(ACCEPT, IGNORE);
		if (record == null) throw new EOFException();
		BdfList payload = clientHelper.toList(record.getPayload());
		int size = payload.size();
		if (size != 4 && size != 5 && size != 6) throw new FormatException();
		Author author = clientHelper.parseAndValidateAuthor(payload.getList(0));
		BdfDictionary props = payload.getDictionary(1);
		Map<TransportId, TransportProperties> properties =
				clientHelper.parseAndValidateTransportPropertiesMap(props);
		byte[] signature = payload.getRaw(2);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);
		long timestamp = payload.getLong(3);
		if (timestamp < 0) throw new FormatException();
		byte[] b3ProofSig = null;
		byte[] mlDsaSigPub = null;
		if (size >= 5) {
			byte[] slot4 = payload.getRaw(4);
			if (slot4.length == B3_SIG_LEN) {
				b3ProofSig = slot4;
			} else if (slot4.length != 0) {
				throw new FormatException();
			}
		}
		if (size == 6) {
			mlDsaSigPub = payload.getRaw(5);
			checkLength(mlDsaSigPub,
					org.zerionproject.core.api.crypto.PostQuantumConstants
							.ML_DSA_65_PUBLIC_KEY_BYTES,
					org.zerionproject.core.api.crypto.PostQuantumConstants
							.ML_DSA_65_PUBLIC_KEY_BYTES);
		}
		return new ContactInfo(author, properties, signature, timestamp,
				b3ProofSig, mlDsaSigPub);
	}

	private Contact addContact(@Nullable PendingContactId pendingContactId,
			Author remoteAuthor, LocalAuthor localAuthor, SecretKey masterKey,
			long timestamp, boolean alice, boolean verified,
			Map<TransportId, TransportProperties> remoteProperties,
			boolean b3SlotPresent,
			@Nullable byte[] peerMlDsaSigPubKey)
			throws DbException, FormatException {
		try {
			Transaction txn = db.startTransaction(false);
			try {
				ContactId contactId;
				if (pendingContactId == null) {
					contactId = contactManager.addContact(txn, remoteAuthor,
							localAuthor.getId(), masterKey, timestamp, alice,
							verified, true, peerMlDsaSigPubKey);
				} else {
					contactId = contactManager.addContact(txn, pendingContactId,
							remoteAuthor, localAuthor.getId(), masterKey,
							timestamp, alice, verified, true,
							peerMlDsaSigPubKey);
				}
				transportPropertyManager.addRemoteProperties(txn, contactId,
						remoteProperties);
				Settings b3 = new Settings();
				b3.put(B3_SLOT_PRESENT_KEY_PREFIX + contactId.getInt(),
						b3SlotPresent ? "1" : "0");
				settingsManager.mergeSettings(txn, b3, B3_SETTINGS_NAMESPACE);
				Contact contact = contactManager.getContact(txn, contactId);
				db.commitTransaction(txn);
				return contact;
			} catch (GeneralSecurityException e) {
				throw new FormatException();
			} finally {
				db.endTransaction(txn);
			}
		} catch (ContactExistsException e) {
			if (pendingContactId != null) {
				Transaction txn2 = db.startTransaction(false);
				try {
					db.removePendingContact(txn2, pendingContactId);
					txn2.attach(new PendingContactAlreadyContactEvent(
							pendingContactId));
					db.commitTransaction(txn2);
				} finally {
					db.endTransaction(txn2);
				}
			}
			throw e;
		}
	}

	private static class ContactInfo {

		private final Author author;
		private final Map<TransportId, TransportProperties> properties;
		private final byte[] signature;
		private final long timestamp;

		@Nullable
		private final byte[] b3ProofSig;
		@Nullable
		private final byte[] mlDsaSigPubKey;

		private ContactInfo(Author author,
				Map<TransportId, TransportProperties> properties,
				byte[] signature, long timestamp,
				@Nullable byte[] b3ProofSig,
				@Nullable byte[] mlDsaSigPubKey) {
			this.author = author;
			this.properties = properties;
			this.signature = signature;
			this.timestamp = timestamp;
			this.b3ProofSig = b3ProofSig;
			this.mlDsaSigPubKey = mlDsaSigPubKey;
		}
	}
}
