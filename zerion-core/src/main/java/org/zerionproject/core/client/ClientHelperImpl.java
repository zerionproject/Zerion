package org.zerionproject.core.client;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.UniqueId;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorFactory;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.util.Base32;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static java.util.Collections.sort;
import static org.zerionproject.core.api.client.ContactGroupConstants.GROUP_KEY_CONTACT_ID;
import static org.zerionproject.core.api.identity.Author.FORMAT_VERSION;
import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.zerionproject.core.api.identity.AuthorConstants.MAX_PUBLIC_KEY_LENGTH;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MAX_PROPERTIES_PER_TRANSPORT;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MAX_PROPERTY_LENGTH;
import static org.zerionproject.core.util.ValidationUtils.checkLength;
import static org.zerionproject.core.util.ValidationUtils.checkSize;

@Immutable
@NotNullByDefault
class ClientHelperImpl implements ClientHelper {

	private static final int SALT_LENGTH = 32;

	private final DatabaseComponent db;
	private final MessageFactory messageFactory;
	private final BdfReaderFactory bdfReaderFactory;
	private final BdfWriterFactory bdfWriterFactory;
	private final MetadataParser metadataParser;
	private final MetadataEncoder metadataEncoder;
	private final CryptoComponent crypto;
	private final AuthorFactory authorFactory;

	@Inject
	ClientHelperImpl(DatabaseComponent db, MessageFactory messageFactory,
			BdfReaderFactory bdfReaderFactory,
			BdfWriterFactory bdfWriterFactory, MetadataParser metadataParser,
			MetadataEncoder metadataEncoder, CryptoComponent crypto,
			AuthorFactory authorFactory) {
		this.db = db;
		this.messageFactory = messageFactory;
		this.bdfReaderFactory = bdfReaderFactory;
		this.bdfWriterFactory = bdfWriterFactory;
		this.metadataParser = metadataParser;
		this.metadataEncoder = metadataEncoder;
		this.crypto = crypto;
		this.authorFactory = authorFactory;
	}

	@Override
	public void addLocalMessage(Message m, BdfDictionary metadata,
			boolean shared) throws DbException, FormatException {
		db.transaction(false, txn -> addLocalMessage(txn, m, metadata, shared,
				false));
	}

	@Override
	public void addLocalMessage(Transaction txn, Message m,
			BdfDictionary metadata, boolean shared, boolean temporary)
			throws DbException, FormatException {
		db.addLocalMessage(txn, m, metadataEncoder.encode(metadata), shared,
				temporary);
	}

	@Override
	public Message createMessage(GroupId g, long timestamp, byte[] body) {
		return messageFactory.createMessage(g, timestamp, body);
	}

	@Override
	public Message createMessage(GroupId g, long timestamp, BdfList body)
			throws FormatException {
		return messageFactory.createMessage(g, timestamp, toByteArray(body));
	}

	@Override
	public Message createMessageForStoringMetadata(GroupId g) {
		byte[] salt = new byte[SALT_LENGTH];
		crypto.getSecureRandom().nextBytes(salt);
		return messageFactory.createMessage(g, 0, salt);
	}

	@Override
	public Message getMessage(MessageId m) throws DbException {
		return db.transactionWithResult(true, txn -> getMessage(txn, m));
	}

	@Override
	public Message getMessage(Transaction txn, MessageId m) throws DbException {
		return db.getMessage(txn, m);
	}

	@Override
	public BdfList getMessageAsList(MessageId m) throws DbException,
			FormatException {
		return db.transactionWithResult(true, txn -> getMessageAsList(txn, m));
	}

	@Override
	public BdfList getMessageAsList(Transaction txn, MessageId m)
			throws DbException, FormatException {
		return getMessageAsList(txn, m, true);
	}

	@Override
	public BdfList getMessageAsList(Transaction txn, MessageId m,
			boolean canonical) throws DbException, FormatException {
		return toList(db.getMessage(txn, m), canonical);
	}

	@Override
	public BdfDictionary getGroupMetadataAsDictionary(GroupId g)
			throws DbException, FormatException {
		return db.transactionWithResult(true, txn ->
				getGroupMetadataAsDictionary(txn, g));
	}

	@Override
	public BdfDictionary getGroupMetadataAsDictionary(Transaction txn,
			GroupId g) throws DbException, FormatException {
		Metadata metadata = db.getGroupMetadata(txn, g);
		return metadataParser.parse(metadata);
	}

	@Override
	public Collection<MessageId> getMessageIds(Transaction txn, GroupId g,
			BdfDictionary query) throws DbException, FormatException {
		return db.getMessageIds(txn, g, metadataEncoder.encode(query));
	}

	@Override
	public BdfDictionary getMessageMetadataAsDictionary(MessageId m)
			throws DbException, FormatException {
		return db.transactionWithResult(true, txn ->
				getMessageMetadataAsDictionary(txn, m));
	}

	@Override
	public BdfDictionary getMessageMetadataAsDictionary(Transaction txn,
			MessageId m) throws DbException, FormatException {
		Metadata metadata = db.getMessageMetadata(txn, m);
		return metadataParser.parse(metadata);
	}

	@Override
	public Map<MessageId, BdfDictionary> getMessageMetadataAsDictionary(
			GroupId g) throws DbException, FormatException {
		return db.transactionWithResult(true, txn ->
				getMessageMetadataAsDictionary(txn, g));
	}

	@Override
	public Map<MessageId, BdfDictionary> getMessageMetadataAsDictionary(
			Transaction txn, GroupId g) throws DbException, FormatException {
		Map<MessageId, Metadata> raw = db.getMessageMetadata(txn, g);
		Map<MessageId, BdfDictionary> parsed = new HashMap<>(raw.size());
		for (Entry<MessageId, Metadata> e : raw.entrySet())
			parsed.put(e.getKey(), metadataParser.parse(e.getValue()));
		return parsed;
	}

	@Override
	public Map<MessageId, BdfDictionary> getMessageMetadataAsDictionary(
			GroupId g, BdfDictionary query) throws DbException,
			FormatException {
		return db.transactionWithResult(true, txn ->
				getMessageMetadataAsDictionary(txn, g, query));
	}

	@Override
	public Map<MessageId, BdfDictionary> getMessageMetadataAsDictionary(
			Transaction txn, GroupId g, BdfDictionary query) throws DbException,
			FormatException {
		Metadata metadata = metadataEncoder.encode(query);
		Map<MessageId, Metadata> raw = db.getMessageMetadata(txn, g, metadata);
		Map<MessageId, BdfDictionary> parsed = new HashMap<>(raw.size());
		for (Entry<MessageId, Metadata> e : raw.entrySet())
			parsed.put(e.getKey(), metadataParser.parse(e.getValue()));
		return parsed;
	}

	@Override
	public void mergeGroupMetadata(GroupId g, BdfDictionary metadata)
			throws DbException, FormatException {
		db.transaction(false, txn -> mergeGroupMetadata(txn, g, metadata));
	}

	@Override
	public void mergeGroupMetadata(Transaction txn, GroupId g,
			BdfDictionary metadata) throws DbException, FormatException {
		db.mergeGroupMetadata(txn, g, metadataEncoder.encode(metadata));
	}

	@Override
	public void mergeMessageMetadata(MessageId m, BdfDictionary metadata)
			throws DbException, FormatException {
		db.transaction(false, txn -> mergeMessageMetadata(txn, m, metadata));
	}

	@Override
	public void mergeMessageMetadata(Transaction txn, MessageId m,
			BdfDictionary metadata) throws DbException, FormatException {
		db.mergeMessageMetadata(txn, m, metadataEncoder.encode(metadata));
	}

	@Override
	public byte[] toByteArray(BdfDictionary dictionary) throws FormatException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter writer = bdfWriterFactory.createWriter(out);
		try {
			writer.writeDictionary(dictionary);
		} catch (FormatException e) {
			throw e;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return out.toByteArray();
	}

	@Override
	public byte[] toByteArray(BdfList list) throws FormatException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter writer = bdfWriterFactory.createWriter(out);
		try {
			writer.writeList(list);
		} catch (FormatException e) {
			throw e;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return out.toByteArray();
	}

	@Override
	public BdfDictionary toDictionary(byte[] b, int off, int len)
			throws FormatException {
		ByteArrayInputStream in = new ByteArrayInputStream(b, off, len);
		BdfReader reader = bdfReaderFactory.createReader(in);
		try {
			BdfDictionary dictionary = reader.readDictionary();
			if (!reader.eof()) throw new FormatException();
			return dictionary;
		} catch (FormatException e) {
			throw e;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public BdfDictionary toDictionary(TransportProperties transportProperties) {
		return new BdfDictionary(transportProperties);
	}

	@Override
	public BdfDictionary toDictionary(
			Map<TransportId, TransportProperties> map) {
		BdfDictionary d = new BdfDictionary();
		for (Entry<TransportId, TransportProperties> e : map.entrySet())
			d.put(e.getKey().getString(), new BdfDictionary(e.getValue()));
		return d;
	}

	@Override
	public BdfList toList(byte[] b, int off, int len) throws FormatException {
		return toList(b, off, len, true);
	}

	private BdfList toList(byte[] b, int off, int len, boolean canonical)
			throws FormatException {
		ByteArrayInputStream in = new ByteArrayInputStream(b, off, len);
		BdfReader reader = bdfReaderFactory.createReader(in,
				BdfReader.DEFAULT_NESTED_LIMIT, len, canonical);
		try {
			BdfList list = reader.readList();
			if (!reader.eof()) throw new FormatException();
			return list;
		} catch (FormatException e) {
			throw e;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public BdfList toList(byte[] b) throws FormatException {
		return toList(b, 0, b.length, true);
	}

	@Override
	public BdfList toList(Message m) throws FormatException {
		return toList(m.getBody());
	}

	@Override
	public BdfList toList(Message m, boolean canonical) throws FormatException {
		byte[] b = m.getBody();
		return toList(b, 0, b.length, canonical);
	}

	@Override
	public BdfList toList(Author a) {
		return BdfList.of(a.getFormatVersion(), a.getName(), a.getPublicKey());
	}

	@Override
	public byte[] sign(String label, BdfList toSign, PrivateKey privateKey)
			throws FormatException, GeneralSecurityException {
		return crypto.sign(label, toByteArray(toSign), privateKey);
	}

	@Override
	public void verifySignature(byte[] signature, String label, BdfList signed,
			PublicKey publicKey)
			throws FormatException, GeneralSecurityException {
		if (!crypto.verifySignature(signature, label, toByteArray(signed),
				publicKey)) {
			throw new GeneralSecurityException("Invalid signature");
		}
	}

	@Override
	public Author parseAndValidateAuthor(BdfList author)
			throws FormatException {
		checkSize(author, 3);
		int formatVersion = author.getInt(0);
		if (formatVersion != FORMAT_VERSION) throw new FormatException();
		String name = author.getString(1);
		checkLength(name, 1, MAX_AUTHOR_NAME_LENGTH);
		byte[] publicKeyBytes = author.getRaw(2);
		checkLength(publicKeyBytes, 1, MAX_PUBLIC_KEY_LENGTH);
		KeyParser parser = crypto.getSignatureKeyParser();
		PublicKey publicKey;
		try {
			publicKey = parser.parsePublicKey(publicKeyBytes);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
		return authorFactory.createAuthor(formatVersion, name, publicKey);
	}

	@Override
	public PublicKey parseAndValidateAgreementPublicKey(byte[] publicKeyBytes)
			throws FormatException {
		KeyParser parser = crypto.getAgreementKeyParser();
		try {
			return parser.parsePublicKey(publicKeyBytes);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
	}

	@Override
	public TransportProperties parseAndValidateTransportProperties(
			BdfDictionary properties) throws FormatException {
		checkSize(properties, 0, MAX_PROPERTIES_PER_TRANSPORT);
		TransportProperties p = new TransportProperties();
		for (String key : properties.keySet()) {
			checkLength(key, 1, MAX_PROPERTY_LENGTH);
			String value = properties.getString(key);
			checkLength(value, 1, MAX_PROPERTY_LENGTH);
			p.put(key, value);
		}
		return p;
	}

	@Override
	public Map<TransportId, TransportProperties> parseAndValidateTransportPropertiesMap(
			BdfDictionary properties) throws FormatException {
		Map<TransportId, TransportProperties> tpMap = new HashMap<>();
		for (String key : properties.keySet()) {
			TransportId transportId = new TransportId(key);
			TransportProperties transportProperties =
					parseAndValidateTransportProperties(
							properties.getDictionary(key));
			tpMap.put(transportId, transportProperties);
		}
		return tpMap;
	}

	@Override
	public ContactId getContactId(Transaction txn, GroupId contactGroupId)
			throws DbException {
		try {
			BdfDictionary meta =
					getGroupMetadataAsDictionary(txn, contactGroupId);
			return new ContactId(meta.getInt(GROUP_KEY_CONTACT_ID));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void setContactId(Transaction txn, GroupId contactGroupId,
			ContactId c) throws DbException {
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(GROUP_KEY_CONTACT_ID, c.getInt()));
		try {
			mergeGroupMetadata(txn, contactGroupId, meta);
		} catch (FormatException e) {
			throw new AssertionError(e);
		}
	}
}
