package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactExchangeManager;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.HandshakeManager.HandshakeResult;
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
import java.util.Arrays;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import static org.zerionproject.core.api.contact.B3Constants.B3_PQ_PUB_LEN;
import static org.zerionproject.core.api.contact.B3Constants.B3_PROOF_ENABLED;
import static org.zerionproject.core.api.contact.B3Constants.B3_SETTINGS_NAMESPACE;
import static org.zerionproject.core.api.contact.B3Constants.B3_SIG_LEN;
import static org.zerionproject.core.api.contact.B3Constants.B3_SLOT_PRESENT_KEY_PREFIX;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.identity.AuthorConstants.MAX_SIGNATURE_LENGTH;
import static org.zerionproject.core.api.system.Clock.MIN_REASONABLE_TIME_MS;
import static org.zerionproject.core.contact.ContactExchangeConstants.EXCHANGE_TAG_LENGTH;
import static org.zerionproject.core.contact.ContactExchangeConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.contact.ContactExchangeRecordTypes.CONTACT_INFO;
import static org.zerionproject.core.contact.ContactExchangeRecordTypes.REPLACE_KEYS;
import static org.zerionproject.core.util.ValidationUtils.checkLength;

@Immutable
@NotNullByDefault
class ContactExchangeManagerImpl implements ContactExchangeManager {

	private static final RecordPredicate ACCEPT = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					r.getRecordType() == CONTACT_INFO;
	private static final RecordPredicate ACCEPT_REPLACE_KEYS = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					r.getRecordType() == REPLACE_KEYS;
	private static final RecordPredicate IGNORE = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					!isKnownRecordType(r.getRecordType());

	private static boolean isKnownRecordType(byte type) {
		return type == CONTACT_INFO || type == REPLACE_KEYS;
	}

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	static final int MAX_CONTACT_INFO_BYTES = 64 * 1024;

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
		return exchange(null, conn, masterKey, masterKey, false, alice,
				verified, false, null, null, null, null);
	}

	@Override
	public Contact exchangeContacts(DuplexTransportConnection conn,
			SecretKey exchangeKey, SecretKey rootKey, boolean alice,
			boolean verified) throws IOException, DbException {
		try {
			return exchange(null, conn, exchangeKey, rootKey,
					exchangeKey != rootKey, alice, verified, false, null, null,
					null, null);
		} finally {
			if (exchangeKey != rootKey) exchangeKey.clear();
		}
	}

	@Override
	public Contact exchangeContacts(PendingContactId p,
			DuplexTransportConnection conn, SecretKey masterKey, boolean alice,
			boolean verified, boolean classical) throws IOException, DbException {
		return exchange(p, conn, masterKey, masterKey, false, alice,
				verified, classical, null, null, null, null);
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
		return exchange(p, conn, masterKey, masterKey, false, alice,
				verified, classical, ourStaticHybridPub, theirStaticHybridPub,
				ourEphX25519, theirEphX25519);
	}

	@Override
	public Contact exchangeContacts(PendingContactId p,
			DuplexTransportConnection conn, HandshakeResult handshake,
			boolean verified, boolean classical)
			throws IOException, DbException {
		try {
			return exchange(p, conn, handshake.getExchangeKey(),
					handshake.getRootKey(), handshake.isKeySeparated(),
					handshake.isAlice(), verified, classical,
					handshake.getOurStaticHybridPub(),
					handshake.getTheirStaticHybridPub(),
					handshake.getOurEphX25519(),
					handshake.getTheirEphX25519());
		} finally {
			if (handshake.isKeySeparated()) {
				handshake.getExchangeKey().clear();
			}
		}
	}

	private Contact exchange(@Nullable PendingContactId p,
			DuplexTransportConnection conn, SecretKey exchangeKey,
			SecretKey rootKey, boolean keySeparated, boolean alice,
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
				contactExchangeCrypto.deriveHeaderKey(exchangeKey, alice);
		SecretKey remoteHeaderKey =
				contactExchangeCrypto.deriveHeaderKey(exchangeKey, !alice);
		try {
			InputStream streamReader = streamReaderFactory
					.createContactExchangeStreamReader(in, remoteHeaderKey);
			RecordReader recordReader = classical
					? recordReaderFactory.createRecordReader(streamReader, true)
					: recordReaderFactory.createRecordReader(streamReader,
							MAX_CONTACT_INFO_BYTES);
			StreamWriter streamWriter = streamWriterFactory
					.createContactExchangeStreamWriter(out, localHeaderKey);
			RecordWriter recordWriter = recordWriterFactory
					.createRecordWriter(streamWriter.getOutputStream(), classical);
			long localTimestamp = clock.currentTimeMillis();
			byte[] localMlDsaSigPub = identityManager.getLocalMlDsaSigPublicKey();
			if (localMlDsaSigPub == null) throw new DbException();
			boolean localHybrid = B3_PROOF_ENABLED
					&& ourStaticHybridPub != null
					&& ourEphX25519 != null
					&& theirEphX25519 != null;
			BdfList localAuthorList = clientHelper.toList(localAuthor);
			BdfDictionary localProps =
					clientHelper.toDictionary(localProperties);
			boolean localReplacesKeys = intendsToReplaceKeys(p);
			boolean localTagForm = keySeparated && !verified
					&& !localReplacesKeys;
			byte[] localSignature;
			byte[] localB3ProofSig = null;
			byte[] localHybridSignature;
			if (localTagForm) {
				localSignature = contactExchangeCrypto.exchangeTag(exchangeKey,
						alice, transcript(localAuthorList, localMlDsaSigPub,
								localProps, localTimestamp,
								localHybrid ? ourStaticHybridPub : null,
								ourEphX25519, theirEphX25519));
				localHybridSignature = new byte[0];
			} else {
				localSignature = contactExchangeCrypto
						.sign(localAuthor.getPrivateKey(), exchangeKey, alice);
				if (localHybrid) {
					byte[] ourStaticPqPub = Arrays.copyOfRange(
							ourStaticHybridPub, 32, 32 + B3_PQ_PUB_LEN);
					localB3ProofSig = B3PqProof.sign(
							localAuthor.getPrivateKey().getEncoded(),
							ourEphX25519, theirEphX25519, ourStaticPqPub);
				}
				byte[] localMlDsaSigPriv =
						identityManager.getLocalMlDsaSigPrivateKey();
				if (localMlDsaSigPriv == null) throw new DbException();
				try {
					localHybridSignature = contactExchangeCrypto.hybridSign(
							localAuthor.getPrivateKey(), localMlDsaSigPriv,
							exchangeKey, alice);
				} finally {
					Arrays.fill(localMlDsaSigPriv, (byte) 0);
				}
			}
			ContactInfo remoteInfo;
			if (alice) {
				sendContactInfo(recordWriter, localAuthorList, localProps,
						localSignature, localTimestamp, localB3ProofSig,
						localMlDsaSigPub, localHybridSignature);
				sendReplaceKeys(recordWriter, localReplacesKeys);
				remoteInfo = receiveContactInfo(recordReader);
			} else {
				remoteInfo = receiveContactInfo(recordReader);
				sendContactInfo(recordWriter, localAuthorList, localProps,
						localSignature, localTimestamp, localB3ProofSig,
						localMlDsaSigPub, localHybridSignature);
				sendReplaceKeys(recordWriter, localReplacesKeys);
			}
			Boolean remoteReplacesKeys = receiveReplaceKeys(recordReader);
			streamWriter.sendEndOfStream();
			recordReader.readRecord(r -> false, IGNORE);
			boolean hybridExchange = B3_PROOF_ENABLED
					&& theirStaticHybridPub != null
					&& ourEphX25519 != null
					&& theirEphX25519 != null;
			boolean remoteTagForm =
					remoteInfo.signature.length == EXCHANGE_TAG_LENGTH
							&& remoteInfo.b3ProofSig == null
							&& remoteInfo.hybridSignature == null;
			boolean staticPqBound;
			if (remoteTagForm) {
				if (!keySeparated) throw new FormatException();
				boolean tagOk = contactExchangeCrypto.verifyExchangeTag(
						remoteInfo.signature, exchangeKey, !alice,
						transcript(remoteInfo.authorList,
								remoteInfo.mlDsaSigPubKey, remoteInfo.props,
								remoteInfo.timestamp,
								hybridExchange ? theirStaticHybridPub : null,
								theirEphX25519, ourEphX25519));
				if (!tagOk) throw new FormatException();
				staticPqBound = hybridExchange;
			} else {
				verifySignatures(remoteInfo, exchangeKey, alice,
						hybridExchange, theirStaticHybridPub, ourEphX25519,
						theirEphX25519);
				staticPqBound = remoteInfo.b3ProofSig != null;
			}
			long timestamp = Math.min(localTimestamp, remoteInfo.timestamp);
			if (timestamp < MIN_REASONABLE_TIME_MS) {
				throw new FormatException();
			}
			Contact contact = addContact(p, remoteInfo.author, localAuthor,
					rootKey, timestamp, alice, verified, remoteInfo.properties,
					staticPqBound, remoteTagForm && localReplacesKeys,
					remoteInfo.mlDsaSigPubKey, remoteReplacesKeys);

			return contact;
		} finally {
			localHeaderKey.clear();
			remoteHeaderKey.clear();
		}
	}

	private void verifySignatures(ContactInfo remoteInfo,
			SecretKey exchangeKey, boolean alice, boolean hybridExchange,
			@Nullable byte[] theirStaticHybridPub,
			@Nullable byte[] ourEphX25519, @Nullable byte[] theirEphX25519)
			throws FormatException {
		PublicKey remotePublicKey = remoteInfo.author.getPublicKey();
		boolean sigOk = contactExchangeCrypto.verify(remotePublicKey,
				exchangeKey, !alice, remoteInfo.signature);
		if (!sigOk) {
			throw new FormatException();
		}
		if (remoteInfo.hybridSignature == null
				|| !contactExchangeCrypto.verifyHybrid(remotePublicKey,
				remoteInfo.mlDsaSigPubKey, exchangeKey, !alice,
				remoteInfo.hybridSignature)) {
			throw new FormatException();
		}
		if (hybridExchange && remoteInfo.b3ProofSig == null) {
			throw new FormatException();
		}
		if (remoteInfo.b3ProofSig != null) {
			if (theirStaticHybridPub == null
					|| ourEphX25519 == null
					|| theirEphX25519 == null) {
				throw new FormatException();
			}
			byte[] theirStaticPqPub = Arrays.copyOfRange(
					theirStaticHybridPub, 32, 32 + B3_PQ_PUB_LEN);
			boolean ok = B3PqProof.verify(remotePublicKey.getEncoded(),
					theirEphX25519, ourEphX25519, theirStaticPqPub,
					remoteInfo.b3ProofSig);
			if (!ok) throw new FormatException();
		}
	}

	private ExchangeTranscript transcript(BdfList authorList,
			byte[] mlDsaSigPub, BdfDictionary props, long timestamp,
			@Nullable byte[] staticHybridPub, @Nullable byte[] senderEph,
			@Nullable byte[] receiverEph) throws FormatException {
		byte[] staticPqPub = null;
		byte[] sessionId = null;
		if (staticHybridPub != null && senderEph != null
				&& receiverEph != null) {
			staticPqPub = Arrays.copyOfRange(staticHybridPub, 32,
					32 + B3_PQ_PUB_LEN);
			sessionId = B3PqProof.computeSessionId(senderEph, receiverEph);
		}
		return new ExchangeTranscript(clientHelper.toByteArray(authorList),
				mlDsaSigPub, clientHelper.toByteArray(props), timestamp,
				staticPqPub, sessionId);
	}

	private void sendContactInfo(RecordWriter recordWriter,
			BdfList authorList, BdfDictionary props, byte[] signature,
			long timestamp, @Nullable byte[] b3ProofSig,
			byte[] mlDsaSigPub, byte[] hybridSignature)
			throws IOException {
		byte[] safeB3 = b3ProofSig != null ? b3ProofSig : new byte[0];
		BdfList payload = BdfList.of(authorList, props, signature, timestamp,
				safeB3, mlDsaSigPub, hybridSignature);
		recordWriter.writeRecord(new Record(PROTOCOL_VERSION, CONTACT_INFO,
				clientHelper.toByteArray(payload)));
		recordWriter.flush();
	}

	private boolean intendsToReplaceKeys(@Nullable PendingContactId p)
			throws DbException {
		if (p == null) return false;
		return db.transactionWithResult(true,
				txn -> contactManager.intendsToReplaceKeys(txn, p));
	}

	private void sendReplaceKeys(RecordWriter recordWriter, boolean replace)
			throws IOException {
		BdfList payload = BdfList.of(replace);
		recordWriter.writeRecord(new Record(PROTOCOL_VERSION, REPLACE_KEYS,
				clientHelper.toByteArray(payload)));
		recordWriter.flush();
	}

	/**
	 * Reads the peer's REPLACE_KEYS record, which follows its CONTACT_INFO.
	 * Returns null when the peer ended its stream without one, which an
	 * earlier release does.
	 */
	@Nullable
	private Boolean receiveReplaceKeys(RecordReader recordReader)
			throws IOException {
		Record record = recordReader.readRecord(ACCEPT_REPLACE_KEYS, IGNORE);
		if (record == null) return null;
		BdfList payload = clientHelper.toList(record.getPayload());
		if (payload.size() != 1) throw new FormatException();
		return payload.getBoolean(0);
	}

	private ContactInfo receiveContactInfo(RecordReader recordReader)
			throws IOException {
		Record record = recordReader.readRecord(ACCEPT, IGNORE);
		if (record == null) throw new EOFException();
		BdfList payload = clientHelper.toList(record.getPayload());
		if (payload.size() != 7) throw new FormatException();
		BdfList authorList = payload.getList(0);
		Author author = clientHelper.parseAndValidateAuthor(authorList);
		BdfDictionary props = payload.getDictionary(1);
		Map<TransportId, TransportProperties> properties =
				clientHelper.parseAndValidateTransportPropertiesMap(props);
		byte[] signature = payload.getRaw(2);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);
		long timestamp = payload.getLong(3);
		if (timestamp < 0) throw new FormatException();
		byte[] b3ProofSig = null;
		byte[] slot4 = payload.getRaw(4);
		if (slot4.length == B3_SIG_LEN) {
			b3ProofSig = slot4;
		} else if (slot4.length != 0) {
			throw new FormatException();
		}
		byte[] mlDsaSigPub = payload.getRaw(5);
		checkLength(mlDsaSigPub, ML_DSA_65_PUBLIC_KEY_BYTES,
				ML_DSA_65_PUBLIC_KEY_BYTES);
		byte[] hybridSignature = null;
		byte[] slot6 = payload.getRaw(6);
		if (slot6.length == HYBRID_SIGNATURE_BYTES) {
			hybridSignature = slot6;
		} else if (slot6.length != 0) {
			throw new FormatException();
		}
		return new ContactInfo(author, authorList, properties, props,
				signature, timestamp, b3ProofSig, mlDsaSigPub,
				hybridSignature);
	}

	private Contact addContact(@Nullable PendingContactId pendingContactId,
			Author remoteAuthor, LocalAuthor localAuthor, SecretKey rootKey,
			long timestamp, boolean alice, boolean verified,
			Map<TransportId, TransportProperties> remoteProperties,
			boolean b3SlotPresent, boolean mustBeNew,
			@Nullable byte[] peerMlDsaSigPubKey,
			@Nullable Boolean peerReplacesKeys)
			throws DbException, FormatException {
		try {
			Transaction txn = db.startTransaction(false);
			try {
				if (mustBeNew && db.containsContact(txn,
						remoteAuthor.getId(), localAuthor.getId())) {
					throw new FormatException();
				}
				ContactId contactId;
				if (pendingContactId == null) {
					contactId = contactManager.addContact(txn, remoteAuthor,
							localAuthor.getId(), rootKey, timestamp, alice,
							verified, true, peerMlDsaSigPubKey);
				} else {
					contactId = contactManager.addContact(txn, pendingContactId,
							remoteAuthor, localAuthor.getId(), rootKey,
							timestamp, alice, verified, true,
							peerMlDsaSigPubKey, peerReplacesKeys);
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
					String alias = db.getPendingContact(txn2, pendingContactId)
							.getAlias();
					contactManager.removePendingContact(txn2, pendingContactId);
					txn2.attach(new PendingContactAlreadyContactEvent(
							pendingContactId, alias));
					db.commitTransaction(txn2);
				} catch (org.zerionproject.core.api.db
						.NoSuchPendingContactException ignored) {
				} finally {
					db.endTransaction(txn2);
				}
			}
			throw e;
		}
	}

	private static class ContactInfo {

		private final Author author;
		private final BdfList authorList;
		private final Map<TransportId, TransportProperties> properties;
		private final BdfDictionary props;
		private final byte[] signature;
		private final long timestamp;

		@Nullable
		private final byte[] b3ProofSig;
		private final byte[] mlDsaSigPubKey;
		@Nullable
		private final byte[] hybridSignature;

		private ContactInfo(Author author, BdfList authorList,
				Map<TransportId, TransportProperties> properties,
				BdfDictionary props, byte[] signature, long timestamp,
				@Nullable byte[] b3ProofSig, byte[] mlDsaSigPubKey,
				@Nullable byte[] hybridSignature) {
			this.author = author;
			this.authorList = authorList;
			this.properties = properties;
			this.props = props;
			this.signature = signature;
			this.timestamp = timestamp;
			this.b3ProofSig = b3ProofSig;
			this.mlDsaSigPubKey = mlDsaSigPubKey;
			this.hybridSignature = hybridSignature;
		}
	}
}
