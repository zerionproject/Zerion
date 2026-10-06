package com.professor.zerion.android.contact.add.remote;

import android.app.Application;
import android.content.SharedPreferences;

import com.professor.zerion.android.AppModule;

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
	private final OwnLinkMemory ownLinkMemory;
	private volatile boolean reAddExisting = false;
	private volatile boolean remoteLinkScannedInPerson = false;

	@Inject
	AddContactViewModel(Application application,
			ContactManager contactManager,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			@AppModule.ProfilePrefs SharedPreferences profilePrefs) {
		this(application, contactManager, dbExecutor, lifecycleManager, db,
				androidExecutor, new OwnLinkMemory(profilePrefs));
	}

	AddContactViewModel(Application application,
			ContactManager contactManager,
			Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			OwnLinkMemory ownLinkMemory) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.contactManager = contactManager;
		this.ownLinkMemory = ownLinkMemory;
	}

	void setReAddExisting(boolean reAdd) {
		reAddExisting = reAdd;
	}

	boolean isReAddExisting() {
		return reAddExisting;
	}

	void onOwnLinkShared() {
		String link = handshakeLink.getValue();
		if (link != null) {
			ownLinkMemory.shared(link, System.currentTimeMillis());
		}
	}

	void setRemoteLinkScannedInPerson(boolean inPerson) {
		remoteLinkScannedInPerson = inPerson;
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
		lastRemoteHandshakeLink = link;
	}

	@Nullable
	private volatile String lastRemoteHandshakeLink;

	@Nullable
	String getLastRemoteHandshakeLink() {
		return lastRemoteHandshakeLink;
	}

	boolean isOwnLink(@Nullable CharSequence link) {
		if (link == null) return false;
		if (sameLinkKey(link, handshakeLink.getValue())) return true;
		return sameLinkKey(link,
				ownLinkMemory.lastShared(System.currentTimeMillis()));
	}

	private static boolean sameLinkKey(CharSequence link,
			@Nullable String own) {
		if (own == null) return false;
		java.util.regex.Matcher theirs = LINK_REGEX.matcher(link);
		java.util.regex.Matcher ours = LINK_REGEX.matcher(own);
		return theirs.find() && ours.find()
				&& theirs.group(1).equals(ours.group(1));
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
		final String current = handshakeLink.getValue();
		final String ownLink = current == null ? null
				: ownLinkMemory.linkToBind(current, System.currentTimeMillis());
		final boolean reAdd = reAddExisting;
		final boolean inPerson = remoteLinkScannedInPerson;
		remoteHandshakeLink = null;
		runOnDbThread(() -> {
			try {
				contactManager.addPendingContact(linkSnapshot, nickname,
						ownLink, reAdd, inPerson);
				ownLinkMemory.forget();
				addContactResult.postValue(new LiveResult<>(true));
			} catch (org.zerionproject.core.api.contact
					.OwnLinkChangedException e) {
				ownLinkMemory.forget();
				loadHandshakeLink();
				addContactResult.postValue(new LiveResult<>(e));
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
