package com.professor.zerion.android.contact;

import android.app.Application;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.contact.event.PendingContactAddedEvent;
import org.briarproject.bramble.api.contact.event.PendingContactRemovedEvent;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.conversation.ConversationCache;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.Collections.sort;

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
			AndroidNotificationManager notificationManager) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor,
				contactManager, authorManager, conversationManager,
				connectionRegistry, eventBus);
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

	/**
	 * Pre-load conversation messages into cache before opening ConversationActivity.
	 * This enables instant display when the activity opens (Signal/SimpleX level UX).
	 */
	void preLoadConversation(ContactId contactId) {
		// Skip if already cached
		if (ConversationCache.getInstance().hasValidCache(contactId)) {
			return;
		}

		// Pre-load in background - don't block UI
		runOnDbThread(() -> {
			try {
				Collection<ConversationMessageHeader> headers =
						conversationManager.getMessageHeaders(contactId);
				List<ConversationMessageHeader> sorted = new ArrayList<>(headers);
				sort(sorted, (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
				ConversationCache.getInstance().put(contactId, sorted);
			} catch (DbException e) {
				// Ignore - activity will load from DB if cache miss
			}
		});
	}

}
