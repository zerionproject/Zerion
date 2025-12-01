package com.professor.zerion.android.sharing;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;

@NotNullByDefault
public interface SharingController {

	void onCleared();

	@UiThread
	void add(ContactId c);

	@UiThread
	void addAll(Collection<ContactId> contacts);

	@UiThread
	void remove(ContactId c);

	LiveData<SharingInfo> getSharingInfo();

	class SharingInfo {
		public final int total, online;

		SharingInfo(int total, int online) {
			this.total = total;
			this.online = online;
		}
	}

}
