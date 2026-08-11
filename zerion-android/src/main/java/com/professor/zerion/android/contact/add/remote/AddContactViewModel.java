package com.professor.zerion.android.contact.add.remote;

import android.app.Application;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.UnsupportedVersionException;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.ContactType;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchPendingContactException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.system.AndroidExecutor;
import com.professor.zerion.android.viewmodel.DbViewModel;
import com.professor.zerion.android.viewmodel.LiveEvent;
import com.professor.zerion.android.viewmodel.LiveResult;
import com.professor.zerion.android.viewmodel.MutableLiveEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static org.zerionproject.core.api.contact.HandshakeLinkConstants.LINK_REGEX;

@NotNullByDefault
public class AddContactViewModel extends DbViewModel {

	private final ContactManager contactManager;

	private final MutableLiveData<String> handshakeLink =
			new MutableLiveData<>();
	private final MutableLiveEvent<Boolean> remoteLinkEntered =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> qrExchangeChosen =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> linkExchangeChosen =
			new MutableLiveEvent<>();
	private final MutableLiveData<LiveResult<Boolean>> addContactResult =
			new MutableLiveData<>();
	@Nullable
	private String remoteHandshakeLink;

	@Inject
	AddContactViewModel(Application application,
			ContactManager contactManager,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.contactManager = contactManager;
	}

	void onCreate() {

		loadHandshakeLink();
	}

	private void loadHandshakeLink() {
		runOnDbThread(() -> {
			try {
				handshakeLink.postValue(
						contactManager.getHandshakeLink(ContactType.ZERION));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveData<String> getHandshakeLink() {
		return handshakeLink;
	}

	void onQrExchangeChosen() {
		qrExchangeChosen.setEvent(true);
	}

	void onLinkExchangeChosen() {
		linkExchangeChosen.setEvent(true);
	}

	LiveEvent<Boolean> getQrExchangeChosen() {
		return qrExchangeChosen;
	}

	LiveEvent<Boolean> getLinkExchangeChosen() {
		return linkExchangeChosen;
	}

	@Nullable
	String getRemoteHandshakeLink() {
		return remoteHandshakeLink;
	}

	void setRemoteHandshakeLink(String link) {
		remoteHandshakeLink = link;
	}

	boolean isValidRemoteContactLink(@Nullable CharSequence link) {
		return link != null && LINK_REGEX.matcher(link).find();
	}

	LiveEvent<Boolean> getRemoteLinkEntered() {
		return remoteLinkEntered;
	}

	void onRemoteLinkEntered() {
		if (remoteHandshakeLink == null) throw new IllegalStateException();
		remoteLinkEntered.setEvent(true);
	}

	void addContact(String nickname) {
		if (remoteHandshakeLink == null) throw new IllegalStateException();
		final String linkSnapshot = remoteHandshakeLink;
		remoteHandshakeLink = null;
		runOnDbThread(() -> {
			try {
				contactManager.addPendingContact(linkSnapshot, nickname);
				addContactResult.postValue(new LiveResult<>(true));
			} catch (UnsupportedVersionException e) {
				addContactResult.postValue(new LiveResult<>(e));
			} catch (DbException | FormatException
					| GeneralSecurityException e) {
				addContactResult.postValue(new LiveResult<>(e));
			}
		});
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		remoteHandshakeLink = null;
		handshakeLink.setValue(null);
		System.gc();
	}

	LiveData<LiveResult<Boolean>> getAddContactResult() {
		return addContactResult;
	}

	void updatePendingContact(String name, PendingContact p) {
		runOnDbThread(() -> {
			try {
				contactManager.removePendingContact(p.getId());
				addContact(name);
			} catch (NoSuchPendingContactException e) {

			} catch (DbException e) {
				addContactResult.postValue(new LiveResult<>(e));
			}
		});
	}

}
