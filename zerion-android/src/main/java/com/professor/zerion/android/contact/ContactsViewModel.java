package com.professor.zerion.android.contact;

import android.app.Application;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.contact.event.ContactAddedEvent;
import org.briarproject.bramble.api.contact.event.ContactAliasChangedEvent;
import org.briarproject.bramble.api.contact.event.ContactRemovedEvent;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.plugin.event.ContactConnectedEvent;
import org.briarproject.bramble.api.plugin.event.ContactDisconnectedEvent;
import org.briarproject.bramble.api.system.AndroidExecutor;
import com.professor.zerion.android.viewmodel.DbViewModel;
import com.professor.zerion.android.viewmodel.LiveResult;
import org.briarproject.briar.api.avatar.event.AvatarUpdatedEvent;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.conversation.event.ConversationMessageTrackedEvent;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.identity.AuthorManager;
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
	private final EventBus eventBus;
	protected final PinnedContactManager pinnedContactManager;

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
			PinnedContactManager pinnedContactManager) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.contactManager = contactManager;
		this.authorManager = authorManager;
		this.conversationManager = conversationManager;
		this.connectionRegistry = connectionRegistry;
		this.eventBus = eventBus;
		this.eventBus.addListener(this);
		this.pinnedContactManager = pinnedContactManager;
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
			boolean connected = connectionRegistry.isConnected(c.getId());
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
			loadContacts();
		} else if (e instanceof ContactConnectedEvent) {
			ContactId cid = ((ContactConnectedEvent) e).getContactId();
			cancelPendingOffline(cid);
			updateItem(cid, item -> new ContactListItem(item, true), false);
		} else if (e instanceof ContactDisconnectedEvent) {
			scheduleOffline(((ContactDisconnectedEvent) e).getContactId());
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
			updateItem(cid, item -> new ContactListItem(item, false), false);
		};
		pendingOfflineCallbacks.put(cid, r);
		debounceHandler.postDelayed(r, OFFLINE_DEBOUNCE_MS);
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
