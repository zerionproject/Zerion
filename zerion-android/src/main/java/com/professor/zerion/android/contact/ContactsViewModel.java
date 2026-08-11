package com.professor.zerion.android.contact;

import android.app.Application;

import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactAddedEvent;
import org.zerionproject.core.api.contact.event.ContactAliasChangedEvent;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.plugin.event.ContactDisconnectedEvent;
import org.zerionproject.core.api.system.AndroidExecutor;
import com.professor.zerion.android.viewmodel.DbViewModel;
import com.professor.zerion.android.viewmodel.LiveResult;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.avatar.event.AvatarUpdatedEvent;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.conversation.event.ConversationMessageTrackedEvent;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.zerionproject.app.api.identity.AuthorManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import android.os.Handler;
import android.os.Looper;

import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.arch.core.util.Function;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

@NotNullByDefault
public class ContactsViewModel extends DbViewModel implements EventListener {

	protected final ContactManager contactManager;
	private final AuthorManager authorManager;
	protected final ConversationManager conversationManager;
	private final ConnectionRegistry connectionRegistry;
	private final com.professor.zerion.android.mesh.MeshPresenceTracker
			meshPresenceTracker;
	private final EventBus eventBus;
	protected final PinnedContactManager pinnedContactManager;
	private final AutoDeleteManager autoDeleteManager;

	private final MutableLiveData<LiveResult<List<ContactListItem>>>
			contactListItems = new MutableLiveData<>();

	private static final long OFFLINE_DEBOUNCE_MS = 10_000L;
	private final Handler debounceHandler =
			new Handler(Looper.getMainLooper());
	private final Map<ContactId, Runnable> pendingOfflineCallbacks =
			new HashMap<>();

	@Inject
	public ContactsViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor, ContactManager contactManager,
			AuthorManager authorManager,
			ConversationManager conversationManager,
			ConnectionRegistry connectionRegistry, EventBus eventBus,
			PinnedContactManager pinnedContactManager,
			AutoDeleteManager autoDeleteManager,
			com.professor.zerion.android.mesh.MeshPresenceTracker
					meshPresenceTracker) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.contactManager = contactManager;
		this.authorManager = authorManager;
		this.conversationManager = conversationManager;
		this.connectionRegistry = connectionRegistry;
		this.meshPresenceTracker = meshPresenceTracker;
		this.eventBus = eventBus;
		this.eventBus.addListener(this);
		this.pinnedContactManager = pinnedContactManager;
		this.autoDeleteManager = autoDeleteManager;
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
		debounceHandler.removeCallbacksAndMessages(null);
		pendingOfflineCallbacks.clear();
	}

	protected void loadContacts() {
		loadFromDb(this::loadContacts, contactListItems::setValue);
	}

	private List<ContactListItem> loadContacts(Transaction txn)
			throws DbException {
		List<ContactListItem> contacts = new ArrayList<>();
		java.util.Set<ContactId> validIds = new java.util.HashSet<>();
		for (Contact c : contactManager.getContacts(txn)) {
			ContactId id = c.getId();
			validIds.add(id);
			if (!displayContact(id)) {
				continue;
			}
			AuthorInfo authorInfo = authorManager.getAuthorInfo(txn, c);
			MessageTracker.GroupCount count =
					conversationManager.getGroupCount(txn, id);
			boolean connected = connectionRegistry.isConnected(c.getId())
					|| meshPresenceTracker.isPresent(c.getId());
			boolean pinned = pinnedContactManager.isPinned(id);
			contacts.add(new ContactListItem(c, authorInfo, connected, count,
					pinned));
		}
		pinnedContactManager.pruneStaleEntries(validIds);
		Collections.sort(contacts);
		return contacts;
	}

	protected boolean displayContact(ContactId contactId) {
		return true;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactAddedEvent) {
			applyDefaultTimer(((ContactAddedEvent) e).getContactId());
			loadContacts();
		} else if (e instanceof ContactConnectedEvent) {
			ContactId cid = ((ContactConnectedEvent) e).getContactId();
			cancelPendingOffline(cid);
			updateItem(cid, item -> new ContactListItem(item, true), false);
		} else if (e instanceof ContactDisconnectedEvent) {
			scheduleOffline(((ContactDisconnectedEvent) e).getContactId());
		} else if (e instanceof com.professor.zerion.android.mesh.event
				.MeshPresenceChangedEvent) {
			com.professor.zerion.android.mesh.event.MeshPresenceChangedEvent m =
					(com.professor.zerion.android.mesh.event
							.MeshPresenceChangedEvent) e;
			if (m.isPresent()) {
				cancelPendingOffline(m.getContactId());
				updateItem(m.getContactId(),
						item -> new ContactListItem(item, true), false);
			} else {
				scheduleOffline(m.getContactId());
			}
		} else if (e instanceof ContactRemovedEvent) {
			removeItem(((ContactRemovedEvent) e).getContactId());
		} else if (e instanceof ConversationMessageTrackedEvent) {
			ConversationMessageTrackedEvent p =
					(ConversationMessageTrackedEvent) e;
			long timestamp = p.getTimestamp();
			boolean read = p.getRead();
			updateItem(p.getContactId(),
					item -> new ContactListItem(item, timestamp, read), true);
		} else if (e instanceof AvatarUpdatedEvent) {
			AvatarUpdatedEvent a = (AvatarUpdatedEvent) e;
			updateItem(a.getContactId(), item -> new ContactListItem(item,
					a.getAttachmentHeader()), false);
		} else if (e instanceof ContactAliasChangedEvent) {
			ContactAliasChangedEvent c = (ContactAliasChangedEvent) e;
			updateItem(c.getContactId(),
					item -> new ContactListItem(item, c.getAlias()), false);
		} else if (e instanceof ConversationMessagesDeletedEvent) {
			ConversationMessagesDeletedEvent d =
					(ConversationMessagesDeletedEvent) e;
			reloadGroupCount(d.getContactId());
		}
	}

	private void applyDefaultTimer(ContactId contactId) {
		long timer = com.professor.zerion.android.AppModule.getUiPrefs()
				.getLong("default_disappearing_timer", -1L);
		if (timer <= 0) return;
		runOnDbThread(false, txn ->
						autoDeleteManager.setAutoDeleteTimer(txn, contactId,
								timer),
				this::handleException);
	}

	private void reloadGroupCount(ContactId contactId) {
		runOnDbThread(() -> {
			try {
				MessageTracker.GroupCount count =
						conversationManager.getGroupCount(contactId);
				androidExecutor.runOnUiThread(() ->
						updateItem(contactId,
								item -> new ContactListItem(item, count), true));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	public LiveData<LiveResult<List<ContactListItem>>> getContactListItems() {
		return contactListItems;
	}

	private void cancelPendingOffline(ContactId cid) {
		Runnable pending = pendingOfflineCallbacks.remove(cid);
		if (pending != null) debounceHandler.removeCallbacks(pending);
	}

	private void scheduleOffline(ContactId cid) {
		cancelPendingOffline(cid);
		Runnable r = () -> {
			pendingOfflineCallbacks.remove(cid);
			if (connectionRegistry.isConnected(cid)
					|| meshPresenceTracker.isPresent(cid)) {
				return;
			}
			updateItem(cid, item -> new ContactListItem(item, false), false);
		};
		pendingOfflineCallbacks.put(cid, r);
		debounceHandler.postDelayed(r, OFFLINE_DEBOUNCE_MS);
	}

	@UiThread
	public void reconcileConnectionState() {
		List<ContactListItem> list = getList(contactListItems);
		if (list == null) {
			return;
		}
		for (ContactListItem item : list) {
			ContactId id = item.getContact().getId();
			boolean actual = connectionRegistry.isConnected(id)
					|| meshPresenceTracker.isPresent(id);
			if (actual && !item.isConnected()) {
				cancelPendingOffline(id);
				updateItem(id, it -> new ContactListItem(it, true), false);
			} else if (!actual && item.isConnected()
					&& !pendingOfflineCallbacks.containsKey(id)) {
				scheduleOffline(id);
			}
		}
	}

	@UiThread
	private void updateItem(ContactId c,
			Function<ContactListItem, ContactListItem> replacer, boolean sort) {
		List<ContactListItem> list = updateListItems(getList(contactListItems),
				itemToTest -> itemToTest.getContact().getId().equals(c),
				replacer);
		if (list == null) return;
		if (sort) Collections.sort(list);
		contactListItems.setValue(new LiveResult<>(list));
	}

	@UiThread
	private void removeItem(ContactId c) {
		pinnedContactManager.unpin(c);
		removeAndUpdateListItems(contactListItems,
				itemToTest -> itemToTest.getContact().getId().equals(c));
	}

	@UiThread
	boolean togglePinned(ContactId contactId) {
		boolean result = pinnedContactManager.togglePin(contactId);
		boolean isPinned = pinnedContactManager.isPinned(contactId);
		updateItem(contactId,
				item -> new ContactListItem(item, isPinned, 0), true);
		return result;
	}

	boolean isContactPinned(ContactId contactId) {
		return pinnedContactManager.isPinned(contactId);
	}

	int getPinnedCount() {
		return pinnedContactManager.getPinnedCount();
	}

}
