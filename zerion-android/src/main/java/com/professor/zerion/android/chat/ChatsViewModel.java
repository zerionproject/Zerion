package com.professor.zerion.android.chat;

import android.app.Application;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.PendingContactAddedEvent;
import org.zerionproject.core.api.contact.event.PendingContactRemovedEvent;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.plugin.event.ContactDisconnectedEvent;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.zerionproject.app.api.avatar.event.AvatarUpdatedEvent;
import org.zerionproject.app.api.client.MessageTracker.GroupCount;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.conversation.event.ConversationMessageTrackedEvent;
import org.zerionproject.app.api.identity.AuthorInfo;
import org.zerionproject.app.api.identity.AuthorManager;
import com.professor.zerion.android.contact.PinnedContactManager;
import com.professor.zerion.android.mesh.MeshPresenceTracker;
import com.professor.zerion.android.mesh.event.MeshPresenceChangedEvent;
import com.professor.zerion.android.viewmodel.DbViewModel;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * Backs the Chats inbox: loads every 1:1 contact, reduces each to a
 * {@link ChatItem} (name, last-activity time, unread count) and sorts them
 * most-recent first. Private groups and channels have their own tabs and are
 * not listed here. Reloads whenever an event that can change a conversation's
 * activity or unread count fires, so the inbox stays current while the app is
 * open.
 */
@NotNullByDefault
public class ChatsViewModel extends DbViewModel implements EventListener {

	private final ContactManager contactManager;
	private final ConversationManager conversationManager;
	private final AuthorManager authorManager;
	private final PinnedContactManager pinnedManager;
	private final ConnectionRegistry connectionRegistry;
	private final MeshPresenceTracker meshPresenceTracker;
	private final EventBus eventBus;

	private final MutableLiveData<List<ChatItem>> items =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> hasPendingContacts =
			new MutableLiveData<>();

	@Inject
	ChatsViewModel(Application app, @DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor, ContactManager contactManager,
			ConversationManager conversationManager,
			AuthorManager authorManager,
			PinnedContactManager pinnedManager,
			ConnectionRegistry connectionRegistry,
			MeshPresenceTracker meshPresenceTracker, EventBus eventBus) {
		super(app, dbExecutor, lifecycleManager, db, androidExecutor);
		this.contactManager = contactManager;
		this.conversationManager = conversationManager;
		this.authorManager = authorManager;
		this.pinnedManager = pinnedManager;
		this.connectionRegistry = connectionRegistry;
		this.meshPresenceTracker = meshPresenceTracker;
		this.eventBus = eventBus;
		eventBus.addListener(this);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
	}

	LiveData<List<ChatItem>> getItems() {
		return items;
	}

	LiveData<Boolean> getHasPendingContacts() {
		return hasPendingContacts;
	}

	void checkForPendingContacts() {
		runOnDbThread(() -> {
			try {
				hasPendingContacts.postValue(
						!contactManager.getPendingContacts().isEmpty());
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void load() {
		runOnDbThread(() -> {
			try {
				List<ChatItem> list = new ArrayList<>();
				for (Contact c : contactManager.getContacts()) {
					GroupCount count =
							conversationManager.getGroupCount(c.getId());
					String name = c.getAlias() != null ? c.getAlias()
							: c.getAuthor().getName();
					AuthorInfo info = authorManager.getAuthorInfo(c);
					list.add(new ChatItem(ChatItem.Type.CONTACT,
							c.getId().getInt(), null, name,
							count.getLatestMsgTime(), count.getUnreadCount(),
							pinnedManager.isPinned(c.getId()),
							connectionRegistry.isConnected(c.getId())
									|| meshPresenceTracker.isPresent(c.getId()),
							info.getAvatarHeader()));
				}
				Collections.sort(list, (a, b) -> {
					if (a.isPinned() != b.isPinned()) {
						return a.isPinned() ? -1 : 1;
					}
					return Long.compare(b.getTime(), a.getTime());
				});
				items.postValue(list);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	/**
	 * Pins or unpins a contact. Returns false only when a pin was rejected
	 * because the pinned limit is already reached, so the caller can notify
	 * the user; true in every other case.
	 */
	boolean togglePin(int contactId) {
		ContactId cid = new ContactId(contactId);
		boolean wasPinned = pinnedManager.isPinned(cid);
		boolean nowPinned = pinnedManager.togglePin(cid);
		load();
		return wasPinned || nowPinned;
	}

	void setAlias(int contactId, @Nullable String alias) {
		String trimmed = (alias == null || alias.trim().isEmpty())
				? null : alias.trim();
		runOnDbThread(() -> {
			try {
				contactManager.setContactAlias(new ContactId(contactId),
						trimmed);
				load();
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void deleteContact(int contactId) {
		runOnDbThread(() -> {
			try {
				ContactId cid = new ContactId(contactId);
				pinnedManager.unpin(cid);
				contactManager.removeContact(cid);
				load();
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ConversationMessageTrackedEvent
				|| e instanceof ConversationMessagesDeletedEvent
				|| e instanceof AvatarUpdatedEvent
				|| e instanceof ContactConnectedEvent
				|| e instanceof ContactDisconnectedEvent
				|| e instanceof MeshPresenceChangedEvent) {
			load();
		} else if (e instanceof PendingContactAddedEvent
				|| e instanceof PendingContactRemovedEvent) {
			checkForPendingContacts();
		}
	}
}
