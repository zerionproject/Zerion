package com.professor.zerion.android.contact.add.remote;

import android.app.Application;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.UnsupportedVersionException;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.ContactType;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.InviteLink;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchPendingContactException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.keyagreement.PayloadParser;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.util.ZerionLinks;
import com.professor.zerion.android.contact.request.LinkErrors;
import com.professor.zerion.android.contact.request.ScannedCode;
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

@NotNullByDefault
public class AddContactViewModel extends DbViewModel {

	public static final class ScanRoute {

		public enum Kind {
			NEARBY, INVITE, IN_PERSON, REFUSED, IGNORED
		}

		private final Kind kind;
		private final String text;
		private final int message;

		ScanRoute(Kind kind, String text, int message) {
			this.kind = kind;
			this.text = text;
			this.message = message;
		}

		public Kind getKind() {
			return kind;
		}

		public String getText() {
			return text;
		}

		public int getMessage() {
			return message;
		}
	}

	private final ContactManager contactManager;
	private final ContactRequestManager contactRequestManager;
	private final PayloadParser payloadParser;
	private final PluginManager pluginManager;

	private final MutableLiveData<String> handshakeLink =
			new MutableLiveData<>();
	private final MutableLiveEvent<Boolean> remoteLinkEntered =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> qrExchangeChosen =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> sendInviteChosen =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<ScanRoute> scanRoute =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<ScanRoute> offlineScanRoute =
			new MutableLiveEvent<>();
	@Nullable
	private String invitePrefill;
	private final MutableLiveData<LiveResult<Boolean>> addContactResult =
			new MutableLiveData<>();
	@Nullable
	private String remoteHandshakeLink;
	private volatile boolean reAddExisting = false;
	private volatile boolean remoteLinkScannedInPerson = false;

	@Inject
	AddContactViewModel(Application application,
			ContactManager contactManager,
			ContactRequestManager contactRequestManager,
			PayloadParser payloadParser,
			PluginManager pluginManager,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.contactManager = contactManager;
		this.contactRequestManager = contactRequestManager;
		this.payloadParser = payloadParser;
		this.pluginManager = pluginManager;
	}

	boolean isTorConnected() {
		return isTorConnected(pluginManager);
	}

	static boolean isTorConnected(PluginManager plugins) {
		if (plugins.isOfflineMode()) return false;
		Plugin tor = plugins.getPlugin(TorConstants.ID);
		return tor != null && tor.getState() == Plugin.State.ACTIVE;
	}

	LiveEvent<ScanRoute> getOfflineScanRoute() {
		return offlineScanRoute;
	}

	void onLinkScannedWithoutInternet(String text) {
		runOnDbThread(() -> offlineScanRoute.postEvent(route(text)));
	}

	void setReAddExisting(boolean reAdd) {
		reAddExisting = reAdd;
	}

	boolean isReAddExisting() {
		return reAddExisting;
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

	void onSendInviteChosen() {
		sendInviteChosen.setEvent(true);
	}

	void onSendInviteChosen(String link) {
		invitePrefill = link;
		sendInviteChosen.setEvent(true);
	}

	void setInvitePrefill(String link) {
		invitePrefill = link;
	}

	@Nullable
	public String takeInvitePrefill() {
		String link = invitePrefill;
		invitePrefill = null;
		return link;
	}

	LiveEvent<ScanRoute> getScanRoute() {
		return scanRoute;
	}

	void onCodeScanned(String text) {
		runOnDbThread(() -> scanRoute.postEvent(route(text)));
	}

	ScanRoute route(String text) {
		if (ScannedCode.isNearbyCode(text, payloadParser)) {
			return new ScanRoute(ScanRoute.Kind.NEARBY, text, 0);
		}
		String link = ScannedCode.extractLink(text);
		if (link == null) return new ScanRoute(ScanRoute.Kind.IGNORED, text, 0);
		try {
			InviteLink parsed = contactRequestManager.parseLink(link);
			if (parsed.getFlow() == InviteLink.Flow.REQUEST) {
				return new ScanRoute(ScanRoute.Kind.INVITE, link, 0);
			}
			return new ScanRoute(ScanRoute.Kind.IN_PERSON, link, 0);
		} catch (FormatException e) {
			return new ScanRoute(ScanRoute.Kind.REFUSED, link,
					LinkErrors.messageFor(e));
		} catch (DbException e) {
			return new ScanRoute(ScanRoute.Kind.IGNORED, link, 0);
		}
	}

	LiveEvent<Boolean> getQrExchangeChosen() {
		return qrExchangeChosen;
	}

	LiveEvent<Boolean> getSendInviteChosen() {
		return sendInviteChosen;
	}

	@Nullable
	String getRemoteHandshakeLink() {
		return remoteHandshakeLink;
	}

	void setRemoteHandshakeLink(String link) {
		link = ZerionLinks.normalizeScheme(link);
		remoteHandshakeLink = link;
		lastRemoteHandshakeLink = link;
	}

	@Nullable
	private volatile String lastRemoteHandshakeLink;

	@Nullable
	String getLastRemoteHandshakeLink() {
		return lastRemoteHandshakeLink;
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
		final String shownOwnLink = handshakeLink.getValue();
		final boolean reAdd = reAddExisting;
		final boolean inPerson = remoteLinkScannedInPerson;
		remoteHandshakeLink = null;
		runOnDbThread(() -> {
			try {
				contactManager.addPendingContactBoundToSharedLink(linkSnapshot,
						nickname, shownOwnLink, reAdd, inPerson);
				addContactResult.postValue(new LiveResult<>(true));
			} catch (org.zerionproject.core.api.contact
					.OwnLinkChangedException e) {
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
