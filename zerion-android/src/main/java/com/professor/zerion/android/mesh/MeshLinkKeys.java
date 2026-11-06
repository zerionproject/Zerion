package com.professor.zerion.android.mesh;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.MeshLinkKeysReceivedEvent;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.versioning.event.ClientVersionUpdatedEvent;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;

@Singleton
@ThreadSafe
@NotNullByDefault
public class MeshLinkKeys implements EventListener,
		ContactManager.ContactHook, BleMeshTransport.PrivatePeers {

	static final String NAMESPACE = "org.zerionproject.mesh/linkKeys";
	static final String KEY_ADVERT = "advert";
	static final String KEY_GENERATION = "generation";
	static final String THEIR_ADVERT = "theirAdvert.";
	static final String THEIR_PAIR = "theirPair.";
	static final String OUR_PAIR = "ourPair.";
	static final String SENT_GENERATION = "sentGeneration.";
	static final String SENT_AT = "sentAt.";
	static final long RESEND_INTERVAL_MS = 24L * 60L * 60L * 1000L;
	static final long REPLY_INTERVAL_MS = 10L * 60L * 1000L;

	private final SettingsManager settingsManager;
	private final ContactManager contactManager;
	private final MessagingManager messagingManager;
	private final Executor ioExecutor;
	private final SecureRandom random = new SecureRandom();
	private final Object lock = new Object();

	@Nullable
	private volatile List<MeshPeer> peers;
	@Nullable
	private volatile byte[] ownAdvertKey;

	@Inject
	MeshLinkKeys(EventBus eventBus, SettingsManager settingsManager,
			ContactManager contactManager, MessagingManager messagingManager,
			@IoExecutor Executor ioExecutor) {
		this.settingsManager = settingsManager;
		this.contactManager = contactManager;
		this.messagingManager = messagingManager;
		this.ioExecutor = ioExecutor;
		eventBus.addListener(this);
		contactManager.registerContactHook(this);
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		settingsManager.mergeSettings(txn, blank(c.getId()), NAMESPACE);
		peers = null;
	}

	@Override
	public void removingContact(Transaction txn, Contact c)
			throws DbException {
		Settings held = settingsManager.getSettings(txn, NAMESPACE);
		Settings update = blank(c.getId());
		update.put(KEY_ADVERT, toHexString(fresh()));
		update.putLong(KEY_GENERATION, held.getLong(KEY_GENERATION, 0L) + 1L);
		settingsManager.mergeSettings(txn, update, NAMESPACE);
		ownAdvertKey = null;
		peers = null;
	}

	private static Settings blank(ContactId c) {
		String id = String.valueOf(c.getInt());
		Settings update = new Settings();
		update.put(THEIR_ADVERT + id, "");
		update.put(THEIR_PAIR + id, "");
		update.put(OUR_PAIR + id, "");
		update.put(SENT_GENERATION + id, "");
		update.put(SENT_AT + id, "");
		return update;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof MeshLinkKeysReceivedEvent) {
			MeshLinkKeysReceivedEvent k = (MeshLinkKeysReceivedEvent) e;
			ioExecutor.execute(() -> received(k.getContactId(),
					k.getAdvertKey(), k.getPairSecret()));
		} else if (e instanceof ContactConnectedEvent) {
			ContactId c = ((ContactConnectedEvent) e).getContactId();
			ioExecutor.execute(() -> sendIfDue(c, false));
		} else if (e instanceof ClientVersionUpdatedEvent) {
			ClientVersionUpdatedEvent v = (ClientVersionUpdatedEvent) e;
			if (v.getClientVersion().getClientId()
					.equals(MessagingManager.CLIENT_ID)) {
				ioExecutor.execute(() -> sendIfDue(v.getContactId(), false));
			}
		} else if (e instanceof ContactRemovedEvent) {
			ContactId c = ((ContactRemovedEvent) e).getContactId();
			ioExecutor.execute(() -> removed(c));
		}
	}

	@Override
	@Nullable
	public byte[] ownAdvertKey() {
		byte[] key = ownAdvertKey;
		if (key != null) return key;
		synchronized (lock) {
			try {
				return ownKeyLocked(settingsManager.getSettings(NAMESPACE));
			} catch (DbException e) {
				return null;
			}
		}
	}

	@Override
	public List<MeshPeer> peers() {
		List<MeshPeer> held = peers;
		if (held != null) return held;
		synchronized (lock) {
			try {
				List<MeshPeer> loaded = loadPeers();
				peers = loaded;
				return loaded;
			} catch (DbException e) {
				return Collections.emptyList();
			}
		}
	}

	void sendIfDue(ContactId c, boolean always) {
		synchronized (lock) {
			try {
				contactManager.getContact(c);
				Settings s = settingsManager.getSettings(NAMESPACE);
				byte[] advert = ownKeyLocked(s);
				long generation = s.getLong(KEY_GENERATION, 0L);
				String id = String.valueOf(c.getInt());
				byte[] ourPair = key(s, OUR_PAIR + id);
				long now = System.currentTimeMillis();
				long sentAt = s.getLong(SENT_AT + id, 0L);
				if (!always && ourPair != null
						&& s.getLong(SENT_GENERATION + id, -1L)
						== generation && now >= sentAt
						&& now - sentAt < RESEND_INTERVAL_MS) {
					return;
				}
				Settings update = new Settings();
				if (ourPair == null) {
					ourPair = fresh();
					update.put(OUR_PAIR + id, toHexString(ourPair));
					settingsManager.mergeSettings(update, NAMESPACE);
					peers = null;
				}
				if (!messagingManager.sendMeshLinkKeys(c, advert, ourPair)) {
					return;
				}
				Settings sent = new Settings();
				sent.putLong(SENT_GENERATION + id, generation);
				sent.putLong(SENT_AT + id, now);
				settingsManager.mergeSettings(sent, NAMESPACE);
			} catch (DbException | RuntimeException e) {
			}
		}
	}

	private void received(ContactId c, byte[] advertKey, byte[] pairSecret) {
		if (advertKey.length != MeshPrivateLink.KEY_BYTES
				|| pairSecret.length != MeshPrivateLink.KEY_BYTES) {
			return;
		}
		boolean changed;
		synchronized (lock) {
			try {
				contactManager.getContact(c);
				Settings held = settingsManager.getSettings(NAMESPACE);
				if (Arrays.equals(advertKey, ownKeyLocked(held))) return;
				String id = String.valueOf(c.getInt());
				changed = !Arrays.equals(advertKey,
						key(held, THEIR_ADVERT + id))
						|| !Arrays.equals(pairSecret,
						key(held, THEIR_PAIR + id));
				if (changed) {
					Settings update = new Settings();
					update.put(THEIR_ADVERT + id, toHexString(advertKey));
					update.put(THEIR_PAIR + id, toHexString(pairSecret));
					settingsManager.mergeSettings(update, NAMESPACE);
					peers = null;
				}
			} catch (DbException | RuntimeException e) {
				return;
			}
		}
		sendIfDue(c, changed && !isRecent(c));
	}

	private boolean isRecent(ContactId c) {
		try {
			long sentAt = settingsManager.getSettings(NAMESPACE)
					.getLong(SENT_AT + c.getInt(), 0L);
			long now = System.currentTimeMillis();
			return now >= sentAt && now - sentAt < REPLY_INTERVAL_MS;
		} catch (DbException e) {
			return true;
		}
	}

	private void removed(ContactId c) {
		synchronized (lock) {
			ownAdvertKey = null;
			peers = null;
		}
		List<Contact> remaining;
		try {
			remaining = new ArrayList<>(contactManager.getContacts());
		} catch (DbException | RuntimeException e) {
			return;
		}
		for (Contact contact : remaining) sendIfDue(contact.getId(), false);
	}

	private byte[] ownKeyLocked(Settings s) throws DbException {
		byte[] key = key(s, KEY_ADVERT);
		if (key == null) {
			key = fresh();
			Settings update = new Settings();
			update.put(KEY_ADVERT, toHexString(key));
			update.putLong(KEY_GENERATION, s.getLong(KEY_GENERATION, 0L));
			settingsManager.mergeSettings(update, NAMESPACE);
		}
		ownAdvertKey = key;
		return key;
	}

	private List<MeshPeer> loadPeers() throws DbException {
		Settings s = settingsManager.getSettings(NAMESPACE);
		List<MeshPeer> out = new ArrayList<>();
		for (Contact c : contactManager.getContacts()) {
			String id = String.valueOf(c.getId().getInt());
			byte[] theirAdvert = key(s, THEIR_ADVERT + id);
			byte[] theirPair = key(s, THEIR_PAIR + id);
			byte[] ourPair = key(s, OUR_PAIR + id);
			if (theirAdvert == null || theirPair == null || ourPair == null) {
				continue;
			}
			if (Arrays.equals(theirPair, ourPair)) continue;
			out.add(new MeshPeer(c.getId().getInt(), theirAdvert,
					MeshPrivateLink.linkKey(ourPair, theirPair),
					MeshPrivateLink.compare(ourPair, theirPair) < 0));
		}
		return Collections.unmodifiableList(out);
	}

	@Nullable
	private static byte[] key(Settings s, String name) {
		String hex = s.get(name);
		if (hex == null || hex.isEmpty()) return null;
		try {
			byte[] key = fromHexString(hex);
			return key.length == MeshPrivateLink.KEY_BYTES ? key : null;
		} catch (FormatException | RuntimeException e) {
			return null;
		}
	}

	private byte[] fresh() {
		byte[] key = new byte[MeshPrivateLink.KEY_BYTES];
		random.nextBytes(key);
		return key;
	}
}
