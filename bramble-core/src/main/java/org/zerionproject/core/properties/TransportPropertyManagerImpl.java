package org.zerionproject.core.properties;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager.ContactHook;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.properties.event.RemoteTransportPropertiesUpdatedEvent;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Group.Visibility;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.api.versioning.ClientVersioningManager.ClientVersioningHook;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.core.api.plugin.B4Constants.B4_LOCAL_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.WIRE_KEY_ONION3;
import static org.zerionproject.core.api.plugin.B4Constants.WIRE_KEY_ONION3_ANNOUNCED_AT_MS;
import static org.zerionproject.core.api.plugin.B4Constants.WIRE_KEY_ONION3_NEXT;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.GROUP_KEY_DISCOVERED;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_LOCAL;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_TRANSPORT_ID;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_VERSION;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.REFLECTED_PROPERTY_PREFIX;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.zerionproject.core.util.StringUtils.isNullOrEmpty;

@Immutable
@NotNullByDefault
class TransportPropertyManagerImpl implements TransportPropertyManager,
		OpenDatabaseHook, ContactHook, ClientVersioningHook,
		IncomingMessageHook {

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final ClientVersioningManager clientVersioningManager;
	private final MetadataParser metadataParser;
	private final ContactGroupFactory contactGroupFactory;
	private final Clock clock;
	private final B4OnionRotation b4OnionRotation;
	private final Group localGroup;

	@Inject
	TransportPropertyManagerImpl(DatabaseComponent db,
			ClientHelper clientHelper,
			ClientVersioningManager clientVersioningManager,
			MetadataParser metadataParser,
			ContactGroupFactory contactGroupFactory, Clock clock,
			B4OnionRotation b4OnionRotation) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.clientVersioningManager = clientVersioningManager;
		this.metadataParser = metadataParser;
		this.contactGroupFactory = contactGroupFactory;
		this.clock = clock;
		this.b4OnionRotation = b4OnionRotation;
		localGroup = contactGroupFactory.createLocalGroup(CLIENT_ID,
				MAJOR_VERSION);
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		boolean firstRun = !db.containsGroup(txn, localGroup.getId());
		if (firstRun) {
			db.addGroup(txn, localGroup);
			for (Contact c : db.getContacts(txn)) addingContact(txn, c);
		} else {
			for (Contact c : db.getContacts(txn)) {
				Group g = getContactGroup(c);
				if (db.containsGroup(txn, g.getId())) {
					db.setGroupVisibility(txn, c.getId(), g.getId(), SHARED);
					try {
						clientHelper.getContactId(txn, g.getId());
					} catch (DbException missing) {
						clientHelper.setContactId(txn, g.getId(), c.getId());
					}
				}
			}
		}
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		Group g = getContactGroup(c);
		db.addGroup(txn, g);
		db.setGroupVisibility(txn, c.getId(), g.getId(), SHARED);
		clientHelper.setContactId(txn, g.getId(), c.getId());
		Map<TransportId, TransportProperties> local = getLocalProperties(txn);
		for (Entry<TransportId, TransportProperties> e : local.entrySet()) {
			storeMessage(txn, g.getId(), e.getKey(), e.getValue(), 1,
					true, true);
		}
	}

	@Override
	public void removingContact(Transaction txn, Contact c) throws DbException {
		db.removeGroup(txn, getContactGroup(c));
	}

	@Override
	public void onClientVisibilityChanging(Transaction txn, Contact c,
			Visibility v) throws DbException {
		Group g = getContactGroup(c);
		db.setGroupVisibility(txn, c.getId(), g.getId(), SHARED);
	}

	@Override
	public DeliveryAction incomingMessage(Transaction txn, Message m,
			Metadata meta) throws DbException, InvalidMessageException {
		try {
			BdfDictionary d = metadataParser.parse(meta);
			TransportId t = new TransportId(d.getString(MSG_KEY_TRANSPORT_ID));
			LatestUpdate latest = findLatest(txn, m.getGroupId(), t, false);
			if (latest != null) {
				if (d.getLong(MSG_KEY_VERSION) > latest.version) {
					db.deleteMessage(txn, latest.messageId);
					db.deleteMessageMetadata(txn, latest.messageId);
				} else {
					db.deleteMessage(txn, m.getId());
					db.deleteMessageMetadata(txn, m.getId());
					return ACCEPT_DO_NOT_SHARE;
				}
			}
			if (TorConstants.ID.equals(t)) {
				BdfList body = clientHelper.toList(m, false);
				TransportProperties props = parseProperties(body);
				String pendingOnion = props.get(WIRE_KEY_ONION3_NEXT);
				String announcedAt =
						props.get(WIRE_KEY_ONION3_ANNOUNCED_AT_MS);
				String currentOnion = props.get(WIRE_KEY_ONION3);
				ContactId cid;
				try {
					cid = clientHelper.getContactId(txn, m.getGroupId());
				} catch (DbException ignored) {
					cid = null;
				}
				if (cid != null && !isNullOrEmpty(pendingOnion)
						&& !isNullOrEmpty(announcedAt)) {
					try {
						long ts = Long.parseLong(announcedAt);
						b4OnionRotation.onAnnounceReceived(txn, cid,
								pendingOnion, ts);
					} catch (NumberFormatException ignored) {
					}
				}
				if (cid != null && !isNullOrEmpty(currentOnion)) {
					b4OnionRotation.onPeerRotationComplete(txn, cid,
							currentOnion);
				}
			}
			txn.attach(new RemoteTransportPropertiesUpdatedEvent(t));
		} catch (FormatException e) {
			throw new InvalidMessageException(e);
		}
		return ACCEPT_DO_NOT_SHARE;
	}

	@Override
	public void addRemoteProperties(Transaction txn, ContactId c,
			Map<TransportId, TransportProperties> props) throws DbException {
		Group g = getContactGroup(db.getContact(txn, c));
		for (Entry<TransportId, TransportProperties> e : props.entrySet()) {
			storeMessage(txn, g.getId(), e.getKey(), e.getValue(), 0,
					false, false);
		}
	}

	@Override
	public void addRemotePropertiesFromConnection(ContactId c, TransportId t,
			TransportProperties props) throws DbException {
		if (props.isEmpty()) return;
		try {
			db.transaction(false, txn -> {
				Contact contact = db.getContact(txn, c);
				Group g = getContactGroup(contact);
				BdfDictionary meta = clientHelper.getGroupMetadataAsDictionary(
						txn, g.getId());
				BdfDictionary discovered =
						meta.getOptionalDictionary(GROUP_KEY_DISCOVERED);
				BdfDictionary merged;
				boolean changed;
				if (discovered == null) {
					merged = new BdfDictionary(props);
					changed = true;
				} else {
					merged = new BdfDictionary(discovered);
					merged.putAll(props);
					changed = !merged.equals(discovered);
				}
				if (changed) {
					meta.put(GROUP_KEY_DISCOVERED, merged);
					clientHelper.mergeGroupMetadata(txn, g.getId(), meta);
					updateLocalProperties(txn, contact, t);
				}
			});
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public Map<TransportId, TransportProperties> getLocalProperties()
			throws DbException {
		return db.transactionWithResult(true, this::getLocalProperties);
	}

	@Override
	public Map<TransportId, TransportProperties> getLocalProperties(
			Transaction txn) throws DbException {
		Map<TransportId, TransportProperties> local = new HashMap<>();
		Map<TransportId, LatestUpdate> latest;
		try {
			latest = findLatestLocal(txn);
		} catch (FormatException e) {
			throw new DbException(e);
		}
		for (Entry<TransportId, LatestUpdate> e : latest.entrySet()) {
			try {
				BdfList message = clientHelper.getMessageAsList(txn,
						e.getValue().messageId, false);
				local.put(e.getKey(), parseProperties(message));
			} catch (FormatException fe) {
			}
		}
		return local;
	}

	@Override
	public TransportProperties getLocalProperties(TransportId t)
			throws DbException {
		try {
			return db.transactionWithResult(true, txn -> {
				TransportProperties p = null;
				LatestUpdate latest = findLatest(txn, localGroup.getId(), t,
						true);
				if (latest != null) {
					BdfList message = clientHelper.getMessageAsList(txn,
							latest.messageId, false);
					p = parseProperties(message);
				}
				return p == null ? new TransportProperties() : p;
			});
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public Map<ContactId, TransportProperties> getRemoteProperties(
			TransportId t) throws DbException {
		return db.transactionWithResult(true, txn -> {
			Map<ContactId, TransportProperties> remote = new HashMap<>();
			for (Contact c : db.getContacts(txn))
				remote.put(c.getId(), getRemoteProperties(txn, c, t));
			return remote;
		});
	}

	private void updateLocalProperties(Transaction txn, Contact c,
			TransportId t) throws DbException {
		try {
			TransportProperties local;
			LatestUpdate latest = findLatest(txn, localGroup.getId(), t, true);
			if (latest == null) {
				local = new TransportProperties();
			} else {
				BdfList message = clientHelper.getMessageAsList(txn,
						latest.messageId, false);
				local = parseProperties(message);
			}
			storeLocalProperties(txn, c, t, local);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private TransportProperties getRemoteProperties(Transaction txn, Contact c,
			TransportId t) throws DbException {
		Group g = getContactGroup(c);
		try {
			TransportProperties remote;
			LatestUpdate latest = findLatest(txn, g.getId(), t, false);
			if (latest == null) {
				remote = new TransportProperties();
			} else {
				BdfList message = clientHelper.getMessageAsList(txn,
						latest.messageId, false);
				remote = parseProperties(message);
			}
			BdfDictionary meta =
					clientHelper.getGroupMetadataAsDictionary(txn, g.getId());
			BdfDictionary d = meta.getOptionalDictionary(GROUP_KEY_DISCOVERED);
			TransportProperties merged;
			if (d == null) {
				merged = remote;
			} else {
				merged = clientHelper.parseAndValidateTransportProperties(d);
				merged.putAll(remote);
			}
			if (TorConstants.ID.equals(t)) {
				String pending = b4OnionRotation
						.getPendingOnionForContact(txn, c.getId());
				if (pending != null && !pending.isEmpty()) {
					String previousOnion = merged.get(
							org.zerionproject.core.api.plugin
									.TorConstants.PROP_ONION_V3);
					merged.put(
							org.zerionproject.core.api.plugin
									.TorConstants.PROP_ONION_V3,
							pending);
					if (previousOnion != null
							&& !previousOnion.isEmpty()
							&& !previousOnion.equals(pending)) {
						merged.put(
								org.zerionproject.core.api.plugin
										.B4Constants
										.B4_LOCAL_FALLBACK_ONION_KEY,
								previousOnion);
					}
					merged.put(
							org.zerionproject.core.api.plugin
									.B4Constants.B4_LOCAL_CONTACT_ID_KEY,
							String.valueOf(c.getId().getInt()));
				}
			}
			return merged;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public TransportProperties getRemoteProperties(ContactId c, TransportId t)
			throws DbException {
		return db.transactionWithResult(true, txn ->
				getRemoteProperties(txn, db.getContact(txn, c), t));
	}

	@Override
	public void mergeLocalProperties(TransportId t, TransportProperties p)
			throws DbException {
		Iterator<String> stripIt = p.keySet().iterator();
		while (stripIt.hasNext()) {
			if (stripIt.next().startsWith(B4_LOCAL_KEY_PREFIX)) {
				stripIt.remove();
			}
		}
		try {
			db.transaction(false, txn -> {
				TransportProperties merged;
				boolean changed;
				LatestUpdate latest = findLatest(txn, localGroup.getId(), t,
						true);
				if (latest == null) {
					merged = new TransportProperties(p);
					Iterator<String> it = merged.values().iterator();
					while (it.hasNext()) {
						if (isNullOrEmpty(it.next())) it.remove();
					}
					changed = true;
				} else {
					BdfList message = clientHelper.getMessageAsList(txn,
							latest.messageId, false);
					TransportProperties old = parseProperties(message);
					merged = new TransportProperties(old);
					for (Entry<String, String> e : p.entrySet()) {
						String key = e.getKey(), value = e.getValue();
						if (isNullOrEmpty(value)) merged.remove(key);
						else merged.put(key, value);
					}
					changed = !merged.equals(old);
				}
				if (changed) {
					long version = latest == null ? 1 : latest.version + 1;
					storeMessage(txn, localGroup.getId(), t, merged, version,
							true, false);
					if (latest != null) db.removeMessage(txn, latest.messageId);
					for (Contact c : db.getContacts(txn)) {
						storeLocalProperties(txn, c, t, merged);
					}
				}
			});
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void storeLocalProperties(Transaction txn, Contact c,
			TransportId t, TransportProperties p)
			throws DbException, FormatException {
		Group g = getContactGroup(c);
		LatestUpdate latest = findLatest(txn, g.getId(), t, true);
		long version = latest == null ? 1 : latest.version + 1;
		BdfDictionary meta = clientHelper.getGroupMetadataAsDictionary(txn,
				g.getId());
		BdfDictionary discovered =
				meta.getOptionalDictionary(GROUP_KEY_DISCOVERED);
		TransportProperties combined;
		if (discovered == null) {
			combined = p;
		} else {
			combined = new TransportProperties(p);
			TransportProperties d = clientHelper
					.parseAndValidateTransportProperties(discovered);
			for (Entry<String, String> e : d.entrySet()) {
				String key = REFLECTED_PROPERTY_PREFIX + e.getKey();
				combined.put(key, e.getValue());
			}
		}
		storeMessage(txn, g.getId(), t, combined, version, true, true);
		if (latest != null) db.removeMessage(txn, latest.messageId);
	}

	private Group getContactGroup(Contact c) {
		return contactGroupFactory.createContactGroup(CLIENT_ID,
				MAJOR_VERSION, c);
	}

	private void storeMessage(Transaction txn, GroupId g, TransportId t,
			TransportProperties p, long version, boolean local, boolean shared)
			throws DbException {
		try {
			BdfList body = encodeProperties(t, p, version);
			long now = clock.currentTimeMillis();
			Message m = clientHelper.createMessage(g, now, body);
			BdfDictionary meta = new BdfDictionary();
			meta.put(MSG_KEY_TRANSPORT_ID, t.getString());
			meta.put(MSG_KEY_VERSION, version);
			meta.put(MSG_KEY_LOCAL, local);
			clientHelper.addLocalMessage(txn, m, meta, shared, false);
		} catch (FormatException e) {
			throw new RuntimeException(e);
		}
	}

	private BdfList encodeProperties(TransportId t, TransportProperties p,
			long version) {
		return BdfList.of(t.getString(), version, p);
	}

	private Map<TransportId, LatestUpdate> findLatestLocal(Transaction txn)
			throws DbException, FormatException {
		Map<TransportId, LatestUpdate> latestUpdates = new HashMap<>();
		Map<MessageId, BdfDictionary> metadata = clientHelper
				.getMessageMetadataAsDictionary(txn, localGroup.getId());
		for (Entry<MessageId, BdfDictionary> e : metadata.entrySet()) {
			BdfDictionary meta = e.getValue();
			TransportId t =
					new TransportId(meta.getString(MSG_KEY_TRANSPORT_ID));
			long version = meta.getLong(MSG_KEY_VERSION);
			latestUpdates.put(t, new LatestUpdate(e.getKey(), version));
		}
		return latestUpdates;
	}

	@Nullable
	private LatestUpdate findLatest(Transaction txn, GroupId g, TransportId t,
			boolean local) throws DbException, FormatException {
		Map<MessageId, BdfDictionary> metadata =
				clientHelper.getMessageMetadataAsDictionary(txn, g);
		for (Entry<MessageId, BdfDictionary> e : metadata.entrySet()) {
			BdfDictionary meta = e.getValue();
			if (meta.getString(MSG_KEY_TRANSPORT_ID).equals(t.getString())
					&& meta.getBoolean(MSG_KEY_LOCAL) == local) {
				return new LatestUpdate(e.getKey(),
						meta.getLong(MSG_KEY_VERSION));
			}
		}
		return null;
	}

	private TransportProperties parseProperties(BdfList message)
			throws FormatException {
		BdfDictionary dictionary = message.getDictionary(2);
		return clientHelper.parseAndValidateTransportProperties(dictionary);
	}

	private static class LatestUpdate {

		private final MessageId messageId;
		private final long version;

		private LatestUpdate(MessageId messageId, long version) {
			this.messageId = messageId;
			this.version = version;
		}
	}
}
