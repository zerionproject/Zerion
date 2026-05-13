package com.professor.zerion.android.sharing;

import android.os.Handler;
import android.os.Looper;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.plugin.event.ContactConnectedEvent;
import org.briarproject.bramble.api.plugin.event.ContactDisconnectedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

@NotNullByDefault
public class SharingControllerImpl implements SharingController, EventListener {

	private static final long EVENT_DEBOUNCE_MS = 400L;

	private final EventBus eventBus;
	private final ConnectionRegistry connectionRegistry;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final Set<ContactId> contacts = new HashSet<>();
	private final MutableLiveData<SharingInfo> sharingInfo =
			new MutableLiveData<>();
	@Nullable
	private Runnable pendingEventUpdate;

	@Inject
	SharingControllerImpl(EventBus eventBus,
			ConnectionRegistry connectionRegistry) {
		this.eventBus = eventBus;
		this.connectionRegistry = connectionRegistry;
		eventBus.addListener(this);
	}

	@Override
	public void onCleared() {
		eventBus.removeListener(this);
		if (pendingEventUpdate != null) {
			mainHandler.removeCallbacks(pendingEventUpdate);
			pendingEventUpdate = null;
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactConnectedEvent) {
			scheduleEventDrivenUpdate(
					((ContactConnectedEvent) e).getContactId());
		} else if (e instanceof ContactDisconnectedEvent) {
			scheduleEventDrivenUpdate(
					((ContactDisconnectedEvent) e).getContactId());
		}
	}

	private void scheduleEventDrivenUpdate(ContactId c) {
		mainHandler.post(() -> {
			if (!contacts.contains(c)) return;
			if (pendingEventUpdate != null) {
				mainHandler.removeCallbacks(pendingEventUpdate);
			}
			pendingEventUpdate = () -> {
				pendingEventUpdate = null;
				updateLiveData();
			};
			mainHandler.postDelayed(pendingEventUpdate, EVENT_DEBOUNCE_MS);
		});
	}

	@UiThread
	private void updateLiveData() {
		int online = getOnlineCount();
		SharingInfo current = sharingInfo.getValue();
		SharingInfo next = new SharingInfo(contacts.size(), online);
		if (current != null
				&& current.total == next.total
				&& current.online == next.online) {
			return;
		}
		sharingInfo.setValue(next);
	}

	private int getOnlineCount() {
		int online = 0;
		for (ContactId c : contacts) {
			if (connectionRegistry.isConnected(c)) online++;
		}
		return online;
	}

	@UiThread
	@Override
	public void addAll(Collection<ContactId> c) {
		contacts.addAll(c);
		updateLiveData();
	}

	@UiThread
	@Override
	public void add(ContactId c) {
		contacts.add(c);
		updateLiveData();
	}

	@UiThread
	@Override
	public void remove(ContactId c) {
		contacts.remove(c);
		updateLiveData();
	}

	@Override
	public LiveData<SharingInfo> getSharingInfo() {
		return sharingInfo;
	}

}
