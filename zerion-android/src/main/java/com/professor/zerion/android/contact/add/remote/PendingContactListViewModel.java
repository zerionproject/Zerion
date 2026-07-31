package com.professor.zerion.android.contact.add.remote;

import android.app.Application;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.contact.event.PendingContactAlreadyContactEvent;
import org.zerionproject.core.api.contact.event.PendingContactRemovedEvent;
import org.zerionproject.core.api.contact.event.PendingContactStateChangedEvent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.rendezvous.RendezvousPoller;
import org.zerionproject.core.api.rendezvous.event.RendezvousPollEvent;
import org.zerionproject.core.api.system.AndroidExecutor;
import com.professor.zerion.android.viewmodel.DbViewModel;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.professor.zerion.android.viewmodel.LiveEvent;
import com.professor.zerion.android.viewmodel.MutableLiveEvent;

import static org.zerionproject.core.api.contact.PendingContactState.OFFLINE;

@NotNullByDefault
public class PendingContactListViewModel extends DbViewModel
		implements EventListener {

	private final ContactManager contactManager;
	private final RendezvousPoller rendezvousPoller;
	private final EventBus eventBus;

	private final MutableLiveData<Collection<PendingContactItem>>
			pendingContacts = new MutableLiveData<>();
	private final MutableLiveData<Boolean> hasInternetConnection =
			new MutableLiveData<>();
	private final MutableLiveEvent<Boolean> alreadyContact =
			new MutableLiveEvent<>();

	@Inject
	PendingContactListViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			ContactManager contactManager,
			RendezvousPoller rendezvousPoller,
			EventBus eventBus) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.contactManager = contactManager;
		this.rendezvousPoller = rendezvousPoller;
		this.eventBus = eventBus;
		this.eventBus.addListener(this);
	}

	void onCreate() {
		if (pendingContacts.getValue() == null) loadPendingContacts();
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof PendingContactAlreadyContactEvent) {
			alreadyContact.postEvent(true);
			loadPendingContacts();
		} else if (e instanceof PendingContactStateChangedEvent ||
				e instanceof PendingContactRemovedEvent ||
				e instanceof RendezvousPollEvent) {
			loadPendingContacts();
		}
	}

	private void loadPendingContacts() {
		runOnDbThread(() -> {
			try {
				Collection<Pair<PendingContact, PendingContactState>> pairs =
						contactManager.getPendingContacts();
				List<PendingContactItem> items = new ArrayList<>(pairs.size());
				boolean online = pairs.isEmpty();
				for (Pair<PendingContact, PendingContactState> pair : pairs) {
					PendingContact p = pair.getFirst();
					PendingContactState state = pair.getSecond();
					long lastPoll = rendezvousPoller.getLastPollTime(p.getId());
					items.add(new PendingContactItem(p, state, lastPoll));
					online = online || state != OFFLINE;
				}
				pendingContacts.postValue(items);
				hasInternetConnection.postValue(online);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveData<Collection<PendingContactItem>> getPendingContacts() {
		return pendingContacts;
	}

	void removePendingContact(PendingContactId id) {
		runOnDbThread(() -> {
			try {
				contactManager.removePendingContact(id);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveData<Boolean> getHasInternetConnection() {
		return hasInternetConnection;
	}

	LiveEvent<Boolean> getAlreadyContact() {
		return alreadyContact;
	}

}
