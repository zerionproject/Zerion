package com.professor.zerion.android.contact;

import android.app.Application;

import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.PendingContactAddedEvent;
import org.zerionproject.core.api.contact.event.PendingContactRemovedEvent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.system.AndroidExecutor;
import com.professor.zerion.android.api.AndroidNotificationManager;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.identity.AuthorManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

@NotNullByDefault
class ContactListViewModel extends ContactsViewModel {

	private final AndroidNotificationManager notificationManager;

	private final MutableLiveData<Boolean> hasPendingContacts =
			new MutableLiveData<>();

	@Inject
	ContactListViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor, ContactManager contactManager,
			AuthorManager authorManager,
			ConversationManager conversationManager,
			ConnectionRegistry connectionRegistry, EventBus eventBus,
			AndroidNotificationManager notificationManager,
			PinnedContactManager pinnedContactManager,
			org.zerionproject.app.api.autodelete.AutoDeleteManager
					autoDeleteManager,
			com.professor.zerion.android.mesh.MeshPresenceTracker
					meshPresenceTracker) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor,
				contactManager, authorManager, conversationManager,
				connectionRegistry, eventBus, pinnedContactManager,
				autoDeleteManager, meshPresenceTracker);
		this.notificationManager = notificationManager;
	}

	@Override
	public void eventOccurred(Event e) {
		super.eventOccurred(e);
		if (e instanceof PendingContactAddedEvent ||
				e instanceof PendingContactRemovedEvent) {
			checkForPendingContacts();
		}
	}

	LiveData<Boolean> getHasPendingContacts() {
		return hasPendingContacts;
	}

	void checkForPendingContacts() {
		runOnDbThread(() -> {
			try {
				boolean hasPending =
						!contactManager.getPendingContacts().isEmpty();
				hasPendingContacts.postValue(hasPending);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void clearAllContactNotifications() {
		notificationManager.clearAllContactNotifications();
	}

	void clearAllContactAddedNotifications() {
		notificationManager.clearAllContactAddedNotifications();
	}

}
